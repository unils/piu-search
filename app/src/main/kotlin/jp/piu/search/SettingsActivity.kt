// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import kotlinx.coroutines.launch

/**
 * 設定画面（[SettingsScreen]）。検索の画面の検索窓の中の歯車と、OS の「アプリ情報」の歯車（`ACTION_APPLICATION_PREFERENCES`）から開く。
 *
 * 値は [SettingsStore] に選んだその場で書く。検索の画面は設定の流れを購読するので、戻ればすぐ効く。
 * 「索引の状態」の件数は、索引の写しと書き手の状態の流れから作る。
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var entrySetup: EntrySetup

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        val store = container.settings
        entrySetup = EntrySetup(this)
        val version = getString(R.string.settings_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        setContent {
            PiuTheme {
                val settings by store.settings.collectAsState()
                val snapshot by container.indexPort.snapshot.collectAsState()
                val indexStatus by container.indexPort.status.collectAsState()
                val profiles by container.profiles.profiles.collectAsState()
                val summary =
                    remember(snapshot, indexStatus, profiles.own) {
                        IndexStatus.of(snapshot, indexStatus, profiles.own).let { IndexSummary(it.totalApps, it.counts.total) }
                    }
                SettingsScreen(
                    settings = settings.search,
                    version = version,
                    sourceUrl = BuildConfig.SOURCE_URL,
                    actions =
                        SettingsActions(
                            onSearchSettingsChange = { next -> store.search = next },
                            onOpenLearned = { startActivity(Intent(this, LearnedListActivity::class.java)) },
                            onOpenAssistantSettings = {
                                if (!entrySetup.openAssistantSettings()) {
                                    Toast.makeText(this, R.string.settings_open_failed, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onRequestTile = if (entrySetup.canRequestTile) entrySetup::requestAddTile else null,
                            onShowOnboardingAgain = ::showOnboardingAgain,
                            onOpenSource = ::openSource,
                            onOpenLicenses = { startActivity(Intent(this, LicensesActivity::class.java)) },
                            onClearLaunchHistory = ::clearLaunchHistory,
                            onOpenIndexStatus = { startActivity(Intent(this, IndexStatusActivity::class.java)) },
                        ),
                    indexSummary = summary,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // OS のアプリ情報から先に開いたときは、このプロセスでまだ同期していない。学習の一覧と同じく同期を頼む
        appContainer.indexPort.requestSync()
    }

    /**
     * 初回の案内を戻し、検索の画面を開いて見せる。検索の画面から来たなら、同じタスクの検索の画面に戻る（singleTask が上を閉じる）。
     * OS のアプリ情報から来たなら、検索の画面を新しく開く。
     */
    private fun showOnboardingAgain() {
        appContainer.settings.showOnboardingAgain()
        startActivity(Intent(this, SearchActivity::class.java))
        finish()
    }

    private fun clearLaunchHistory() {
        val context = applicationContext
        // 画面を閉じても消去は最後まで行い、結果はアプリの Context で知らせる
        appContainer.appScope.launch {
            val cleared = appContainer.records.request(RecordsRequest.ClearLaunches)
            val message =
                if (cleared is RecordsResult.Success) R.string.settings_history_cleared else R.string.settings_history_clear_failed
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSource() {
        // 通信はブラウザが行う。このアプリは INTERNET の権限を持たない
        try {
            startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.SOURCE_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_source_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
