// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ActivityNotFoundException
import android.content.Intent
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
import jp.piu.search.data.IndexSyncStatus

/**
 * 設定画面（[SettingsScreen]）。検索の画面の検索窓の中の歯車と、OS の「アプリ情報」の歯車（`ACTION_APPLICATION_PREFERENCES`）から開く。
 *
 * 値は [SettingsStore] に選んだその場で書く。検索の画面は開くたび（`onStart`）に読み直すので、戻ればすぐ効く。
 * 「索引の状態」の件数は、同期が終わるたびに読み直す。
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var entrySetup: EntrySetup
    private var settings by mutableStateOf(SearchSettings())
    private var indexSummary by mutableStateOf(IndexSummary(0, 0))
    private val statusListener: (IndexSyncStatus) -> Unit = {
        ContextCompat.getMainExecutor(this).execute(::readIndexSummary)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = appContainer.settings
        settings = store.search
        entrySetup = EntrySetup(this)
        val version = getString(R.string.settings_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        setContent {
            PiuTheme {
                SettingsScreen(
                    settings = settings,
                    version = version,
                    sourceUrl = BuildConfig.SOURCE_URL,
                    actions =
                        SettingsActions(
                            onSearchSettingsChange = { next ->
                                store.search = next
                                settings = next
                            },
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
                    indexSummary = indexSummary,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sync = appContainer.indexSync
        sync.addStatusListener(statusListener)
        readIndexSummary()
        // OS のアプリ情報から先に開いたときは、まだ保存した索引を読んでいない。学習の一覧と同じく読み込みを頼む
        sync.requestSync()
    }

    override fun onStop() {
        appContainer.indexSync.removeStatusListener(statusListener)
        super.onStop()
    }

    private fun readIndexSummary() {
        if (isDestroyed) return
        val sync = appContainer.indexSync
        val status = IndexStatus.of(sync.index, sync.materialIssues, sync.lastSavedAt, sync.status, appContainer.users.ownSerial)
        indexSummary = IndexSummary(status.totalApps, status.counts.total)
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
        appContainer.launchHistory.clear { cleared ->
            runOnUiThread {
                val message =
                    if (cleared is StorageResult.Success) R.string.settings_history_cleared else R.string.settings_history_clear_failed
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
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
