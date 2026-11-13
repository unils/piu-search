// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.data.IndexListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 学習の一覧の画面（ui.md の設定画面の「学習」）。一覧・個別の削除・全消去・書き出し・読み込み。
 *
 * 検索の画面の長押しのメニュー（「学習の一覧」）と、設定画面の「学習」から開く。
 * 書き出しと読み込みは SAF（`ACTION_CREATE_DOCUMENT`・`ACTION_OPEN_DOCUMENT`）で、保存先と読むファイルをユーザーが選ぶ。
 * 選んだファイルにだけ一時的に読み書きを許されるので、権限は要らない。
 */
class LearnedListActivity : ComponentActivity() {
    private var memory by mutableStateOf<LearnedMemory?>(null)
    private var index by mutableStateOf(SearchIndex.EMPTY)

    // 出す書き出し・読み込みの結果。預かりの箱（AppContainer.backupOutcome）の写し
    private var outcome by mutableStateOf<BackupOutcome?>(null)

    private val uiExecutor by lazy { ContextCompat.getMainExecutor(this) }

    private val learningListener = LearningListener { next -> uiExecutor.execute { memory = next } }
    private val indexListener = IndexListener { next -> uiExecutor.execute { index = next } }
    private val outcomeListener: (BackupOutcome) -> Unit = { next -> uiExecutor.execute { outcome = next } }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri -> uri?.let(::exportTo) }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importFrom) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        setContent {
            PiuTheme {
                LearnedListScreen(
                    entries = memory?.let { learnedEntries(it, index, container.users.ownSerial) { at -> dateFormat.format(Date(at)) } },
                    onDelete = { container.learning.remove(it.pair, ::reportFailure) },
                    onClearAll = { container.learning.clear(::reportFailure) },
                    onExport = ::chooseExportFile,
                    onImport = ::chooseImportFile,
                    message = outcome?.let(::messageOf),
                    onMessageShown = {
                        outcome?.let(container.backupOutcome::done)
                        outcome = null
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val container = appContainer
        container.learning.addListener(learningListener)
        container.indexSync.addListener(indexListener)
        // 作り直す前の画面が頼んだ書き出し・読み込みの結果も、ここで受ける
        container.backupOutcome.addListener(outcomeListener)
        outcome = container.backupOutcome.current
        // 索引は表示名を引くためだけに使う。設定画面から先に開いたとき（OS のアプリ情報から来たなど）のために、読み込みを頼む
        container.indexSync.requestSync()
        index = container.indexSync.index
        container.learning.requestLoad { result ->
            reportFailure(result)
            if (result is StorageResult.Success) {
                val loaded = container.learning.memory
                uiExecutor.execute { memory = loaded }
            }
        }
    }

    override fun onStop() {
        appContainer.learning.removeListener(learningListener)
        appContainer.indexSync.removeListener(indexListener)
        appContainer.backupOutcome.removeListener(outcomeListener)
        super.onStop()
    }

    private fun chooseExportFile() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        launchPicker { exportLauncher.launch("piu-learning-$date.json") }
    }

    private fun chooseImportFile() {
        // 端末やファイルの置き場によって .json の MIME 型が application/json にならない（octet-stream など）ので、絞らない。
        // 違うファイルを選んでも、書式の検査で何も変えずに知らせる
        launchPicker { importLauncher.launch(arrayOf("*/*")) }
    }

    private fun launchPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            // ファイルを選ぶ画面（DocumentsUI）を無効にした端末
            Toast.makeText(this, R.string.learned_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportTo(uri: Uri) {
        val container = appContainer
        // 裏のスレッドで使うので、この画面ではなくアプリの ContentResolver を持たせる（作り直した後も古い画面を握らない）
        val resolver = applicationContext.contentResolver
        container.learning.export(
            profiles = container.users.backupProfiles(),
            now = System.currentTimeMillis(),
            write = { text ->
                // 既にあるファイルを選んだときに、前の中身の後ろが残らないよう切り詰める
                val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("書き出し先を開けない")
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            },
            // 結果は預かりの箱に入れる。この画面（this）に直接渡すと、終わる前に向きを変えたとき、作り直した画面に届かない
            onDone = { count -> container.backupOutcome.post(BackupOutcome.Exported(count)) },
        )
    }

    private fun importFrom(uri: Uri) {
        val container = appContainer
        val resolver = applicationContext.contentResolver
        container.learning.import(
            profiles = container.users.backupProfiles(),
            now = System.currentTimeMillis(),
            read = { readText(resolver, uri) },
            onDone = { result -> container.backupOutcome.post(BackupOutcome.Imported(result)) },
        )
    }

    private fun reportFailure(result: StorageResult<Unit>) {
        if (result is StorageResult.Failure) appContainer.backupOutcome.post(BackupOutcome.Failed(result.reason))
    }

    private fun messageOf(outcome: BackupOutcome): String =
        when (outcome) {
            is BackupOutcome.Failed -> {
                getString(
                    if (outcome.reason.operation == StorageOperation.LOAD) R.string.learned_load_failed else R.string.learned_save_failed,
                )
            }

            is BackupOutcome.Exported -> {
                outcome.count?.let { resources.getQuantityString(R.plurals.learned_exported, it, it) }
                    ?: getString(R.string.learned_export_failed)
            }

            is BackupOutcome.Imported -> {
                when (val result = outcome.result) {
                    is BackupReadResult.Success -> {
                        resources.getQuantityString(
                            R.plurals.learned_imported,
                            result.records.size,
                            result.records.size,
                            result.skippedCount,
                        )
                    }

                    is BackupReadResult.Failure -> {
                        getString(failureText(result.reason))
                    }
                }
            }
        }

    private companion object {
        const val JSON_MIME = "application/json"
        const val BUFFER_BYTES = 16 * 1024

        /** [uri] を UTF-8 で読む。[LearningBackup.MAX_BYTES] を超えたら、そこで止めて null を返す（動画などを選んでもメモリを食わない）。 */
        fun readText(
            resolver: ContentResolver,
            uri: Uri,
        ): String? {
            val stream = resolver.openInputStream(uri) ?: throw IOException("ファイルを開けない")
            return stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > LearningBackup.MAX_BYTES) return null
                }
                out.toString(Charsets.UTF_8.name())
            }
        }

        fun failureText(reason: BackupFailure): Int =
            when (reason) {
                BackupFailure.DATABASE_READ -> R.string.learned_load_failed
                BackupFailure.DATABASE_WRITE -> R.string.learned_import_save_failed
                BackupFailure.UNREADABLE -> R.string.learned_import_unreadable
                BackupFailure.TOO_LARGE -> R.string.learned_import_too_large
                BackupFailure.INVALID -> R.string.learned_import_invalid
                BackupFailure.NEWER_VERSION -> R.string.learned_import_newer
            }
    }
}
