// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.data.IndexListener
import jp.piu.search.data.IndexSyncStatus
import java.text.DateFormat
import java.util.Date

/**
 * 「索引の状態」の画面（[IndexStatusScreen]）。設定の項目と、検索画面の索引の知らせから開く。
 *
 * 開くたびに同期を頼み、終わったら表示を新しくする。設定から開いたときは、このプロセスでまだ同期していないことがあるため。
 * 報告用の文は「報告用の文を見る」を押したときに裏のスレッドで作る（欠けのある組の事実を端末から読むため）。
 */
class IndexStatusActivity : ComponentActivity() {
    private var status by mutableStateOf(IndexStatus())
    private var report by mutableStateOf<String?>(null)
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    private val statusListener: (IndexSyncStatus) -> Unit = { post() }
    private val indexListener = IndexListener { _: SearchIndex -> post() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 向きを変えても、見せている文を作り直さずに残す
        report = savedInstanceState?.getString(STATE_REPORT)
        setContent {
            PiuTheme {
                IndexStatusScreen(
                    status = status,
                    savedAtText = status.lastSavedAt?.let { dateFormat.format(Date(it)) },
                    report = report,
                    actions =
                        IndexStatusActions(
                            onShowReport = ::buildReport,
                            onCopyReport = ::copy,
                            onCloseReport = { report = null },
                            onOpenIssues = ::openIssues,
                        ),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sync = appContainer.indexSync
        sync.addStatusListener(statusListener)
        sync.addListener(indexListener)
        refresh()
        sync.requestSync()
    }

    override fun onStop() {
        val sync = appContainer.indexSync
        sync.removeStatusListener(statusListener)
        sync.removeListener(indexListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        report?.let { outState.putString(STATE_REPORT, it) }
    }

    // 受け取り手は同期のスレッドと書き手から呼ばれる。画面のスレッドで読み直す
    private fun post() = ContextCompat.getMainExecutor(this).execute(::refresh)

    private fun refresh() {
        if (isDestroyed) return
        status = currentStatus()
    }

    private fun currentStatus(): IndexStatus {
        val container = appContainer
        val sync = container.indexSync
        return IndexStatus.of(sync.index, sync.materialIssues, sync.lastSavedAt, sync.status, container.users.ownSerial)
    }

    private fun buildReport() {
        val snapshot = currentStatus()
        val context = applicationContext
        val ui = ContextCompat.getMainExecutor(context)
        appContainer.derivedIndexExecutor.execute {
            val facts = PackageFactsReader(context).read(snapshot.issues.map { it.issue.key })
            val text = IndexReport.text(snapshot, DeviceInfo.current(), facts)
            ui.execute { if (!isDestroyed) report = text }
        }
    }

    private fun copy(text: String) {
        // 送らない。クリップボードに入れるだけで、貼るかどうかは利用者が決める（PRIVACY.md の「報告用の文」）
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.index_report_title), text))
        // Android 13 以上は OS がコピーしたことを画面に出すので、二重に知らせない
        // （https://developer.android.com/develop/ui/views/touch-and-input/copy-paste の「Avoid duplicate notifications」。2026-11-15 確認）
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.index_report_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openIssues() {
        // 通信はブラウザが行う。このアプリは INTERNET の権限を持たない
        try {
            startActivity(Intent(Intent.ACTION_VIEW, ISSUES_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_source_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val STATE_REPORT = "report"
        const val ISSUES_URL = BuildConfig.SOURCE_URL + "/issues"
    }
}
