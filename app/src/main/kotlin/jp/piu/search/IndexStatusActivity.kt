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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import java.text.DateFormat
import java.util.Date

/**
 * 「索引の状態」の画面（[IndexStatusScreen]）。設定の項目と、検索画面の索引の知らせから開く。
 *
 * 開くたびに同期を頼み、索引の写しと書き手の状態の流れで表示を新しくする（[IndexStatusViewModel]）。
 * 報告用の文は「報告用の文を見る」を押したときに裏のスレッドで作る（欠けのある組の事実を端末から読むため）。
 */
class IndexStatusActivity : ComponentActivity() {
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private lateinit var model: IndexStatusViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        val context = applicationContext
        model =
            ViewModelProvider(
                this,
                viewModelFactory {
                    initializer {
                        IndexStatusViewModel(
                            container.indexPort,
                            container.profiles.profiles,
                            createSavedStateHandle(),
                            Dispatchers.IO,
                            { keys -> PackageFactsReader(context).read(keys) },
                            DeviceInfo::current,
                        )
                    }
                },
            )[IndexStatusViewModel::class.java]
        setContent {
            PiuTheme {
                val status by model.status.collectAsState()
                val report by model.report.collectAsState()
                IndexStatusScreen(
                    status = status,
                    savedAtText = status.lastSavedAt?.let { dateFormat.format(Date(it)) },
                    report = report,
                    actions =
                        IndexStatusActions(
                            onShowReport = model::showReport,
                            onCopyReport = ::copy,
                            onCloseReport = model::closeReport,
                            onOpenIssues = ::openIssues,
                        ),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onScreenStarted()
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
        const val ISSUES_URL = BuildConfig.SOURCE_URL + "/issues"
    }
}
