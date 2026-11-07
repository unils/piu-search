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
import androidx.core.net.toUri

/**
 * 設定画面（[SettingsScreen]）。検索の画面の検索窓の中の歯車と、OS の「アプリ情報」の歯車（`ACTION_APPLICATION_PREFERENCES`）から開く。
 *
 * 値は [SettingsStore] に選んだその場で書く。検索の画面は開くたび（`onStart`）に読み直すので、戻ればすぐ効く。
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var entrySetup: EntrySetup
    private var settings by mutableStateOf(SearchSettings())

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
                        ),
                )
            }
        }
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

    private fun openSource() {
        // 通信はブラウザが行う。このアプリは INTERNET の権限を持たない
        try {
            startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.SOURCE_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_source_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
