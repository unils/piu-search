// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import jp.piu.search.core.state.RecordsFailure
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
    private lateinit var model: LearnedListViewModel

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri -> uri?.let { model.export(it) } }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.import(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        model =
            ViewModelProvider(
                this,
                viewModelFactory {
                    initializer {
                        LearnedListViewModel(
                            container.recordsPort,
                            container.indexPort,
                            container.profiles.profiles,
                            container.backupOutcome,
                            container.learningTransfer,
                            container.appScope,
                        )
                    }
                },
            )[LearnedListViewModel::class.java]
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        setContent {
            PiuTheme {
                val state by model.state.collectAsState()
                LearnedListScreen(
                    entries =
                        state.learning?.let {
                            learnedEntries(
                                it,
                                state.index,
                                state.ownSerial,
                            ) { at -> dateFormat.format(Date(at)) }
                        },
                    onDelete = { model.delete(it.pair) },
                    onClearAll = model::clearAll,
                    onExport = ::chooseExportFile,
                    onImport = ::chooseImportFile,
                    message = state.outcome?.let { messageOf(it.value) },
                    onMessageShown = { state.outcome?.let(model::messageShown) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onScreenStarted()
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

    private fun messageOf(outcome: BackupOutcome): String =
        when (outcome) {
            is BackupOutcome.Failed -> {
                getString(if (outcome.reason == RecordsFailure.NOT_LOADED) R.string.learned_load_failed else R.string.learned_save_failed)
            }

            is BackupOutcome.Exported -> {
                outcome.count?.let { resources.getQuantityString(R.plurals.learned_exported, it, it) }
                    ?: getString(R.string.learned_export_failed)
            }

            is BackupOutcome.Imported -> {
                when (val result = outcome.result) {
                    is BackupReadResult.Success -> {
                        resources.getQuantityString(R.plurals.learned_imported, outcome.accepted, outcome.accepted, result.skippedCount)
                    }

                    is BackupReadResult.Failure -> {
                        getString(failureText(result.reason))
                    }
                }
            }
        }

    private companion object {
        const val JSON_MIME = "application/json"

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
