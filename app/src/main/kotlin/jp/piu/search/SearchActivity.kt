// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import jp.piu.search.core.search.AppId
import kotlinx.coroutines.launch

/**
 * 検索の画面。ランチャーのアイコン・デジタルアシスタント（`ACTION_ASSIST`）・クイック設定のタイル（[SearchTileService]）から開く。
 *
 * 状態は [SearchViewModel] が持ち、この画面は描画と、OS の画面（アプリ情報・設定・学習の一覧・索引の状態・アシスタントの設定）を開く操作だけを持つ。
 * `onStart`/`onStop` は前面の知らせを ViewModel に渡すだけ。設定は流れで受けるので、`onResume` で読み直さない。
 *
 * 起動のモードは singleTask で、どの入口から何度呼んでも画面は 1 つ。画面が残っているところへ呼び出し直されたら
 * [onNewIntent] で受け、新しく開いたときと同じ空の検索語・個人用に戻す。
 * 結果のアプリを開いたら、OS が受け付けた後にこの画面を閉じてタスクごと消す。閉じるのは、起動を受け付けた画面（札 [screen]）だけ。
 * 閉じかけ・壊れかけの画面への操作は受けない（閉じる前の追加のタップで、記録と起動を重ねないため）。
 */
class SearchActivity : ComponentActivity() {
    private lateinit var viewModel: SearchViewModel
    private lateinit var entrySetup: EntrySetup

    // この画面の札。作り直した画面は別の札になり、作り直す前の起動の完了で閉じない
    private val screen = ScreenToken()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 状態バーとナビゲーションバーの裏まで描き、余白は insets で取る
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        entrySetup = EntrySetup(this)
        // 構成の変更では同じ状態とセッションを使い、プロセスの作り直しでは検索語だけを戻す
        viewModel =
            ViewModelProvider(
                this,
                viewModelFactory { initializer { SearchViewModel(container.searchDependencies(), createSavedStateHandle()) } },
            )[SearchViewModel::class.java]
        // 止まっている（onStop の後の）元の画面も閉じるよう、作ってから壊すまで受ける
        lifecycleScope.launch {
            viewModel.finishRequests.collect { finished ->
                if (finished === screen && !isFinishing && !isDestroyed) finishAndRemoveTask()
            }
        }
        setContent {
            PiuTheme {
                val state by viewModel.uiState.collectAsState()
                // すべてのアプリを出しているときの「戻る」は、画面を閉じずに検索の結果に戻す
                BackHandler(enabled = state.showingAllApps) { viewModel.showAllApps(false) }
                SearchScreen(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    content = state.content,
                    icons = container.icons,
                    onOpen = ::openApp,
                    onSearchAction = { if (accepting()) viewModel.openTopResult(screen) },
                    showingAllApps = state.showingAllApps,
                    onShowAllApps = viewModel::showAllApps,
                    profileSwitch = state.profileSwitch,
                    onSelectProfile = viewModel::selectProfile,
                    rowMenu = viewModel::menuFor,
                    onRowAction = ::onRowAction,
                    onboarding = if (state.onboardingShown) onboardingActions() else null,
                    focusKey = state.focusKey,
                    notices = state.notices,
                    onUndoNotice = viewModel::undo,
                    onNoticeDone = viewModel::noticeDone,
                    onRowCapacity = viewModel::onRowCapacity,
                    onScrolled = viewModel::onScrolled,
                    searchBarOnTop = state.searchBarOnTop,
                    showReasons = state.showReasons,
                    workPausedNotice = state.workPausedNotice,
                    indexNotice = state.indexNotice,
                    onIndexNotice = ::openIndexStatus,
                    openRecordFailed = state.openRecordFailed,
                    recordsRebuilt = state.recordsRebuilt,
                    onRecordsRebuiltShown = viewModel::rebuiltNoticeShown,
                    onOpenSettings = ::openSettings,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 呼び出し直された。前の検索語やすべてのアプリの表示を残さず、新しく開いたときと同じにする（ui.md の「画面の振る舞い」）
        viewModel.reset()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenStarted()
    }

    override fun onStop() {
        // 何も開かずに画面を離れた（アプリ情報を開いた・ホームに戻った）なら、打った文字の列を残さない
        viewModel.onScreenStopped(isChangingConfigurations)
        super.onStop()
    }

    /** 結果の行を押した。閉じかけ・壊れかけの画面では受けない（閉じる前の追加のタップで、記録と起動を重ねないため）。 */
    internal fun openApp(app: AppId) {
        if (accepting()) viewModel.open(app, screen)
    }

    private fun accepting(): Boolean = !isFinishing && !isDestroyed

    private fun onboardingActions() =
        OnboardingActions(
            onOpenAssistantSettings = {
                if (!entrySetup.openAssistantSettings()) {
                    Toast.makeText(this, R.string.settings_open_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onRequestTile = if (entrySetup.canRequestTile) entrySetup::requestAddTile else null,
            onDismiss = viewModel::dismissOnboarding,
        )

    private fun onRowAction(
        row: ResultRow,
        action: RowAction,
    ) {
        if (!accepting()) return
        when (action) {
            RowAction.APP_INFO -> {
                if (!appContainer.launcher.showDetails(row.app, this)) viewModel.appInfoFailed(row)
            }

            RowAction.SUPPRESS -> {
                viewModel.suppress(row)
            }

            RowAction.FORGET_LEARNING -> {
                viewModel.forgetLearning(row)
            }

            RowAction.MANAGE_LEARNING -> {
                // 同じタスク（履歴画面に残さない）に積む。「戻る」で検索の画面に、打っていた検索語のまま戻る
                startActivity(Intent(this, LearnedListActivity::class.java))
            }
        }
    }

    private fun openSettings() {
        // 学習の一覧と同じく、同じタスク（履歴画面に残さない）に積む。「戻る」で検索の画面に戻る
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openIndexStatus() {
        // 設定画面と同じく、同じタスクに積む。「戻る」で検索の画面に戻る
        startActivity(Intent(this, IndexStatusActivity::class.java))
    }
}
