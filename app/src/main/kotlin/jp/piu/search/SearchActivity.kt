// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * 検索の画面。ランチャーのアイコン・デジタルアシスタント（`ACTION_ASSIST`）・クイック設定のタイル（[SearchTileService]）から開く。
 *
 * 起動のモードは singleTask で、どの入口から何度呼んでも画面は 1 つ。画面が残っているところへ呼び出し直されたら
 * [onNewIntent] で受け、新しく開いたときと同じ空の検索語・個人用に戻す。
 * 初回は、呼び出しの入口の設定の仕方を案内する（閉じるまで、検索語が空の間に出す）。
 *
 * 開くたびに索引の同期を頼み（裏のスレッド）、開いている間はアプリの変更を受ける（architecture.md の「索引の作成と更新」）。
 * 結果のアプリを開いたら、この画面を閉じてタスクごと消す。次に呼び出すと、空の検索語の新しい画面で開く（ui.md の「画面の振る舞い」）。
 * 仕事用プロファイルの状態（追加・削除・一時停止）は、開いたときと、開いている間に OS が知らせたときに読み直す（architecture.md の「プロファイル」）。
 *
 * 学習（learning.md）: 選んだ時点でセッションを判定し、保存の結果を待ってからアプリを開く。救済を保存できたら、開いたアプリの上に短く知らせ、
 * 次に画面を開いたときに［取り消す］つきで知らせる（開くとこの画面はすぐ閉じるので、その場では取り消しを出せない）。
 * 画面を離れたら、集めた出来事を捨てる。
 *
 * 設定（検索窓の位置・出た理由の表示・仕事用プロファイルの扱い）と初回の案内を出すかは、前に出るたび（[onStart] と [onResume]）に読み直す。
 * 設定画面は検索窓の中の歯車から、同じタスクに積んで開くので、戻ったときに変えた設定がすぐ効く。
 */
class SearchActivity : ComponentActivity() {
    private lateinit var controller: SearchController
    private lateinit var entrySetup: EntrySetup

    // 初回の案内を出しているか。閉じたら設定に覚え、設定画面で戻すまで出さない
    private var onboardingShown by mutableStateOf(false)

    // 検索の画面の設定。設定画面から戻ったときに読み直す
    private var searchSettings by mutableStateOf(SearchSettings())

    // 出す救済の知らせ（前に開いたときの分）
    private var notices by mutableStateOf(emptyList<RescueNotice>())

    private val noticeListener =
        RescueNoticeListener {
            val learning = appContainer.learning
            ContextCompat.getMainExecutor(this).execute { notices = notices.filter(learning::canUndo) }
        }

    // 保存を待つ間と OS へ渡す間の印。呼び直し・再生成の後に、古い完了で別の検索を閉じないため
    private var opening: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 状態バーとナビゲーションバーの裏まで描き、余白は insets で取る
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        notices = container.learning.takeNotices()
        entrySetup = EntrySetup(this)
        onboardingShown = !container.settings.onboardingDismissed
        searchSettings = container.settings.search
        // 前に開いたときの索引と起動の記録がメモリにあれば、それですぐ照らす
        controller =
            SearchController(
                container.analyzer,
                container.queryExecutor,
                ContextCompat.getMainExecutor(this),
                container.indexSync.index,
                container.launchHistory.history,
                learning = container.learning.memory,
            )
        controller.onWorkProfileModeChanged(searchSettings.workProfileMode)
        // 向きの変更などで作り直したときだけ戻す。新しく開いたときは、空の検索語・個人用（ui.md）
        savedInstanceState?.let { state ->
            controller.onWorkProfileChanged(container.users.workProfileState())
            state.getString(STATE_QUERY)?.let(controller::onQueryChange)
            if (state.getBoolean(STATE_WORK_TAB)) controller.selectProfile(ProfileTab.WORK)
            if (state.getBoolean(STATE_ALL_APPS)) controller.showAllApps(true)
        }
        setContent {
            PiuTheme {
                // すべてのアプリを出しているときの「戻る」は、画面を閉じずに検索の結果に戻す
                BackHandler(enabled = controller.showingAllApps) { controller.showAllApps(false) }
                SearchScreen(
                    query = controller.query,
                    onQueryChange = controller::onQueryChange,
                    content = controller.content,
                    icons = container.icons,
                    onOpen = { open(controller.openRequest(it)) },
                    onSearchAction = { controller.topResult?.let { open(controller.openRequest(it)) } },
                    showingAllApps = controller.showingAllApps,
                    onShowAllApps = controller::showAllApps,
                    profileSwitch = profileSwitchOf(controller),
                    onSelectProfile = controller::selectProfile,
                    rowMenu = controller::menuFor,
                    onRowAction = ::onRowAction,
                    onboarding = if (onboardingShown) onboardingActions() else null,
                    focusKey = controller.openCount,
                    notices = notices,
                    onUndoNotice = { notice ->
                        container.learning.undo(notice) { result ->
                            if (result is StorageResult.Failure) container.learning.returnNotices(listOf(notice))
                            reportStorageFailure(result)
                        }
                    },
                    onNoticeDone = { done -> notices = notices.filterNot { it === done } },
                    onRowCapacity = controller::onRowCapacity,
                    onScrolled = controller::onScrolled,
                    searchBarOnTop = searchSettings.searchBarPosition == SearchBarPosition.TOP,
                    showReasons = searchSettings.showReasons,
                    workPausedNotice = controller.workPausedVisible,
                    onOpenSettings = ::openSettings,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opening = null
        // 呼び出し直された。前の検索語やすべてのアプリの表示を残さず、新しく開いたときと同じにする（ui.md の「画面の振る舞い」）
        controller.reset()
    }

    override fun onStart() {
        super.onStart()
        val container = appContainer
        val sync = container.indexSync
        sync.addListener(controller)
        container.launchHistory.addListener(controller)
        container.learning.addListener(controller)
        container.learning.addNoticeListener(noticeListener)
        notices = notices.filter(container.learning::canUndo)
        sync.startWatching()
        sync.requestSync()
        // 忘却（R5）は画面を開くたびに見る。記録は多くても数百件で、読み込みの後に同じスレッドで走る
        container.launchHistory.requestLoad(::reportStorageFailure)
        container.learning.forgetExpired(System.currentTimeMillis(), ::reportStorageFailure)
        // 前に閉じた画面の保存がまだ走っていた場合も、成功後の知らせをこの画面で受け取る
        container.learning.runAfterPending {
            val pending = container.learning.takeNotices()
            ContextCompat.getMainExecutor(this).execute {
                if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    notices = (notices + pending).filter(container.learning::canUndo).distinctBy { it.undoKey }
                } else {
                    container.learning.returnNotices(pending)
                }
            }
        }
        readSettings()
        registerProfileReceiver()
        controller.onWorkProfileChanged(container.users.workProfileState())
        // 足すまでの間に差し替わった索引と記録を取りこぼさない
        controller.onIndexChanged(sync.index)
        controller.onHistoryChanged(container.launchHistory.history)
        controller.onLearningChanged(container.learning.memory)
        controller.startSession()
    }

    override fun onResume() {
        super.onResume()
        // onStart だけでは足りない。設定画面からすぐ戻ると、OS が onStop を出す前に戻り、onPause → onResume だけになる
        readSettings()
    }

    override fun onStop() {
        unregisterReceiver(profileReceiver)
        val container = appContainer
        val sync = container.indexSync
        sync.stopWatching()
        sync.removeListener(controller)
        container.launchHistory.removeListener(controller)
        container.learning.removeListener(controller)
        container.learning.removeNoticeListener(noticeListener)
        // 何も開かずに画面を離れた（アプリ情報を開いた・ホームに戻った）。打った文字の列を残さない
        if (opening == null) controller.endSession()
        super.onStop()
    }

    override fun onDestroy() {
        opening = null
        // 閉じるのではなく作り直す（向きの変更など）なら、まだ出し終えていない救済の知らせを戻し、作り直した画面で出す。
        // 戻さないと、取り出し済みの知らせが［取り消す］の機会ごと失われる
        if (!isFinishing) appContainer.learning.returnNotices(notices)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUERY, controller.query)
        outState.putBoolean(STATE_WORK_TAB, controller.profileTab == ProfileTab.WORK)
        outState.putBoolean(STATE_ALL_APPS, controller.showingAllApps)
    }

    /** 設定画面で変えた設定と「案内をもう一度見る」を効かせる。SharedPreferences のメモリ上の写しを読むだけなので、何度呼んでも軽い。 */
    private fun readSettings() {
        val settings = appContainer.settings
        searchSettings = settings.search
        onboardingShown = !settings.onboardingDismissed
        controller.onWorkProfileModeChanged(searchSettings.workProfileMode)
    }

    // 仕事用プロファイルの追加・削除・一時停止と再開。どれも OS だけが送る放送で、登録した受け手にだけ届く
    private val profileReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                controller.onWorkProfileChanged(appContainer.users.workProfileState())
                // 足された・再開したプロファイルのアプリを索引に入れ、消えたプロファイルの分を除く
                appContainer.indexSync.requestSync()
            }
        }

    private fun registerProfileReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(profileReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            // 旧い版の ContextCompat.registerReceiver は、ほかのアプリを締め出すために自前の権限をマニフェストに足すので使わない。
            // ここで受けるのは OS だけが送れる放送なので、締め出しは要らない
            registerReceiver(profileReceiver, filter)
        }
    }

    private fun onboardingActions() =
        OnboardingActions(
            onOpenAssistantSettings = {
                if (!entrySetup.openAssistantSettings()) {
                    Toast.makeText(this, R.string.settings_open_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onRequestTile = if (entrySetup.canRequestTile) entrySetup::requestAddTile else null,
            onDismiss = {
                appContainer.settings.dismissOnboarding()
                onboardingShown = false
            },
        )

    private fun onRowAction(
        row: ResultRow,
        action: RowAction,
    ) {
        when (action) {
            RowAction.APP_INFO -> {
                if (!appContainer.launcher.showDetails(row.app, this)) {
                    Toast.makeText(this, getString(R.string.app_info_failed, row.label), Toast.LENGTH_SHORT).show()
                    appContainer.indexSync.requestSync()
                }
            }

            RowAction.SUPPRESS -> {
                appContainer.learning.suppress(controller.suppressionOf(row), System.currentTimeMillis(), ::reportStorageFailure)
            }

            RowAction.FORGET_LEARNING -> {
                controller.learnedPairOf(row)?.let { appContainer.learning.remove(it, ::reportStorageFailure) }
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

    private fun open(request: OpenRequest) {
        if (opening != null) return
        val app = request.app
        val container = appContainer
        val now = System.currentTimeMillis()
        val label = controller.labelOf(app) ?: app.packageName
        val decision = controller.finishSession(request)
        val token = Any()
        opening = token
        val launch = container.launcher.prepare(app)
        val context = applicationContext
        val ui = ContextCompat.getMainExecutor(context)

        fun openSaved(notices: List<RescueNotice>) {
            ui.execute {
                if (!launch.launch()) {
                    Toast.makeText(context, context.getString(R.string.launch_failed, label), Toast.LENGTH_SHORT).show()
                    container.indexSync.requestSync()
                    // 保存済みの選択は残す。後の選択や消去を、失敗の巻き戻しで上書きしないため
                    if (opening === token && !isFinishing && !isDestroyed) {
                        opening = null
                        controller.startSession()
                    }
                    return@execute
                }
                notices.lastOrNull()?.let { notice ->
                    Toast
                        .makeText(
                            context,
                            context.getString(R.string.rescue_learned, notice.pair.query.value, notice.label),
                            Toast.LENGTH_LONG,
                        ).show()
                }
                // OS へ渡す最中にも呼び直されうる。閉じる直前に、同じ画面・検索の要求かを確かめる
                if (opening === token && !isFinishing && !isDestroyed) {
                    finishAndRemoveTask()
                }
            }
        }

        if (!launch.recordsChoice) {
            openSaved(emptyList())
            return
        }
        // AppContainer の記録用 Executor は共通の 1 本で、起動の記録 → 学習の順に結果が返る
        var historySaved = false
        container.launchHistory.record(app, now) { historySaved = it is StorageResult.Success }
        container.learning.record(decision, now, onDone = { learningResult ->
            // 片方だけ保存できた場合も開く。失敗は Repository が識別子を含めずログに残す
            openSaved(if (historySaved && learningResult is StorageResult.Success) learningResult.value else emptyList())
        }) { label }
    }

    private fun reportStorageFailure(result: StorageResult<Unit>) {
        if (result !is StorageResult.Failure) return
        val context = applicationContext
        ContextCompat.getMainExecutor(context).execute {
            val message =
                when (result.reason.operation) {
                    StorageOperation.LOAD -> R.string.search_record_load_failed
                    StorageOperation.SAVE -> R.string.search_record_failed
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val STATE_QUERY = "query"
        const val STATE_WORK_TAB = "work_tab"
        const val STATE_ALL_APPS = "all_apps"

        /** 仕事用プロファイルがあり、設定が「切り替えて検索する」のときだけ切り替えを出す。 */
        fun profileSwitchOf(controller: SearchController): ProfileSwitch? =
            if (controller.showsProfileSwitch) ProfileSwitch(controller.profileTab, controller.workProfile.paused) else null
    }
}
