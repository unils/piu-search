// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.search.SearchQuery
import jp.piu.search.core.search.SearchResult
import jp.piu.search.core.state.RecordsFailure
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.RecordsState
import jp.piu.search.core.state.RescueNotice
import jp.piu.search.data.Profiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [SearchViewModel] が使う部品。アプリの寿命の部品（[AppContainer]）が作り、テストでは偽物を入れる。
 *
 * @property index 索引の書き手
 * @property records 記録の書き手
 * @property profiles プロファイルの状態
 * @property refreshProfiles プロファイルの読み直しを頼む（前面への復帰）
 * @property settings 設定
 * @property dismissOnboarding 初回の案内を閉じたことを覚える
 * @property openRecordFailed 前にアプリを開いたときの記録の保存に失敗したか。開くと画面は閉じるので、次の画面にも出すためアプリの寿命に置く
 * @property searchForeground 検索画面が前面か。プロファイルの放送の後に同期するかの判断に使う
 * @property launcher OS への起動
 * @property messages 画面が閉じても出す知らせ
 * @property analyzer 漢字の検索語に読みを付ける形態素解析器
 * @property appScope アプリの寿命の scope。受理した起動と、結果を画面の寿命に結び付けない頼みをここで動かす（画面のスレッドの Dispatcher）
 * @property readingDispatcher 読みを付ける 1 本の裏のスレッド
 * @property derivedDispatcher 派生の索引を作る 1 本の裏のスレッド
 * @property clock 今の時刻（エポックからのミリ秒）。起動の記録の新しさと、学習の出来事の時刻
 * @property timing 計測の印
 */
class SearchDependencies(
    val index: IndexPort,
    val records: RecordsPort,
    val profiles: StateFlow<Profiles>,
    val refreshProfiles: () -> Unit,
    val settings: StateFlow<Settings>,
    val dismissOnboarding: () -> Unit,
    val openRecordFailed: MutableStateFlow<Boolean>,
    val searchForeground: MutableStateFlow<Boolean>,
    val launcher: Launcher,
    val messages: UserMessages,
    val analyzer: ReadingAnalyzer,
    val appScope: CoroutineScope,
    val readingDispatcher: CoroutineDispatcher,
    val derivedDispatcher: CoroutineDispatcher,
    val clock: () -> Long = System::currentTimeMillis,
    val timing: SearchTiming,
)

/**
 * 検索の画面の状態とセッションを構成の変更の間も保つ ViewModel。
 *
 * 索引・記録・プロファイル・設定の流れと、自分の持つ検索語・タブ・全アプリの表示から [uiState] を作る。
 * 入力の流れは画面のスレッドで受け、変化のたびに 1 か所（[refresh]）で作り直す。宣言的な `combine` にしないのは、
 * 作り直すたびに学習のセッションへ「見えていた上位」を送るので、計算の回数とスレッドを決めておく必要があるため。
 * 索引の状態と知らせだけの変化では照合をやり直さない（[publish]）。
 *
 * 照合は軽い（アプリ 500 個で 0.2 ms ほど）ので、入力のたびに画面のスレッドで行う。
 * 漢字の検索語は、まず表記のままで結果を出し、[SearchDependencies.readingDispatcher] で読みを付けてから出し直す（matching.md の「検索語の扱い」）。
 * 読みは検索語だけで確かめ、当てるときは最新の索引・学習・タブ・表示で照合し直す。
 *
 * 派生の索引（[IndexDeriver]）は裏で作り、採る直前に今の入力と比べる。完成を待つ間も、今のプロファイルと設定で出す行と起動の受付を絞る
 * （消えたプロファイルや「出さない」にした仕事用のアプリを、完成の前から出さない・開かない）。
 *
 * アプリを開く流れ（[open]）は、記録の保存と OS への要求をアプリの寿命の scope で最後まで行う。画面が閉じても、受理した起動は続ける。
 * [savedState] には今の検索語だけを置き、セッションの入力の列と起動中の印は保存しない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val deps: SearchDependencies,
    private val savedState: SavedStateHandle,
    private val tracker: SessionTracker = SessionTracker(),
    private val learningConfig: LearningConfig = LearningConfig.DEFAULT,
) : ViewModel() {
    private val records get() = deps.records

    // 以下は画面のスレッドだけで読み書きする
    private var query: String = savedState.get<String>(KEY_QUERY).orEmpty()
    private var showingAllApps = false
    private var profileTab = ProfileTab.PERSONAL
    private var sessionActive = true
    private var screenStarted = false
    private var cleared = false
    private var rowCapacity = 0
    private var focusKey = 0
    private var opening: OpenTicket? = null
    private var noticesDeferred = false
    private val undoing = HashSet<Long>()
    private val dismissed = HashSet<Long>()
    private var rebuiltShown = false

    private var reading: Pair<String, SearchQuery>? = null
    private var readingFor: String? = null
    private var readingJob: Job? = null
    private var wantedReading: Pair<String, SearchQuery>? = null

    private var profiles: Profiles = deps.profiles.value
    private var settings: Settings = deps.settings.value
    private var recordsState: RecordsState = records.state.value
    private var learning: LearnedMemory = learningOf(recordsState)
    private var history: LaunchHistory = historyOf(recordsState)

    private val deriver = IndexDeriver()
    private val derivationInputs = MutableStateFlow(currentInput())
    private var derived: DerivedIndexes? = null
    private var personalEngine = SearchEngine(SearchIndex.EMPTY)
    private var workEngine = SearchEngine(SearchIndex.EMPTY)
    private var nameOrderCache: Pair<SearchIndex, SearchContent.AllApps>? = null
    private var content: SearchContent = SearchContent.Waiting

    private val state = MutableStateFlow(SearchUiState())

    /** 画面に出すものすべて。 */
    val uiState: StateFlow<SearchUiState> = state.asStateFlow()

    private val finishes = MutableSharedFlow<ScreenToken>(extraBufferCapacity = 1)

    /** 受理した起動を OS が受け付けた後に、閉じる画面。自分の札のときだけ閉じる（回転で作り直した画面は閉じない）。 */
    val finishRequests: SharedFlow<ScreenToken> = finishes.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch {
            derivationInputs
                .mapLatest { input -> withContext(deps.derivedDispatcher) { deriver.build(input) } }
                .collect(::applyDerived)
        }
        viewModelScope.launch { deps.index.searchIndex.collect { requestDerivation() } }
        viewModelScope.launch { deps.index.status.collect { publish() } }
        viewModelScope.launch { deps.index.snapshot.collect { publish() } }
        viewModelScope.launch { records.state.collect(::onRecords) }
        viewModelScope.launch {
            records.rebuiltAfterCorruption.collect { rebuilt ->
                if (rebuilt && !rebuiltShown) {
                    rebuiltShown = true
                    publish()
                }
            }
        }
        viewModelScope.launch { deps.openRecordFailed.collect { publish() } }
        viewModelScope.launch { deps.profiles.collect(::onProfiles) }
        viewModelScope.launch { deps.settings.collect(::onSettings) }
    }

    /** 検索語を [raw] にする。すべてのアプリを出していたら、検索の結果に戻る（ui.md）。 */
    fun onQueryChange(raw: String) {
        sessionActive = true
        query = raw
        savedState[KEY_QUERY] = raw
        showingAllApps = false
        // 作り直しの知らせは、その画面で打ち始めるまで出す
        if (raw.isNotEmpty()) rebuiltShown = false
        refresh()
    }

    /** すべてのアプリを出すか（[show]）。検索語は残す。 */
    fun showAllApps(show: Boolean) {
        if (showingAllApps == show) return
        showingAllApps = show
        if (show && sessionActive) tracker.allAppsShown(deps.clock())
        refresh()
    }

    /** 検索の対象を [tab] にする。切り替えを出していなければ個人用のまま。 */
    fun selectProfile(tab: ProfileTab) {
        val next = if (!showsProfileSwitch) ProfileTab.PERSONAL else tab
        if (profileTab == next) return
        profileTab = next
        refresh()
    }

    /**
     * 新しく呼び出されたときの状態に戻す: 空の検索語・個人用・検索の結果（ui.md の「画面の振る舞い」「仕事用プロファイル」）。
     * 画面が残っているところへ呼び出し直されたとき（`onNewIntent`）に使う。起動中の印も解く（古い完了で新しい検索を閉じないため）。
     */
    fun reset() {
        tracker.closed()
        sessionActive = true
        opening = null
        noticesDeferred = false
        query = ""
        savedState[KEY_QUERY] = query
        showingAllApps = false
        profileTab = ProfileTab.PERSONAL
        focusKey++
        refresh()
    }

    /** 結果の一覧に [rows] 行が収まる。画面の大きさが変わるたびに画面から呼ぶ。 */
    fun onRowCapacity(rows: Int) {
        rowCapacity = rows
    }

    /** 結果をスクロールした。 */
    fun onScrolled() {
        if (sessionActive) tracker.scrolled(deps.clock())
    }

    /**
     * 検索画面が前に出た（`onStart`）。前面の知らせを書き手に送り、記録の読み直しと忘却（180 日）を頼み、プロファイルを読み直す。
     * 回転でも呼ぶ（0.2.0 と同じく、開くたびに一覧を観測する）。
     */
    fun onScreenStarted() {
        screenStarted = true
        deps.searchForeground.value = true
        deps.index.setForeground(true)
        deps.refreshProfiles()
        deps.appScope.launch {
            records.refresh().await()
            if (records.state.value.loadFailure != null) {
                deps.messages.show(UserMessage.RecordsFailed(RecordsFailure.NOT_LOADED))
            } else {
                report(records.request(RecordsRequest.Forget(deps.clock())))
            }
        }
        if (opening == null) startSession()
    }

    /** 検索画面が裏に回った（`onStop`）。構成の変更でなければ、打った文字の列を残さない。 */
    fun onScreenStopped(changingConfigurations: Boolean) {
        screenStarted = false
        deps.searchForeground.value = false
        deps.index.setForeground(false)
        if (!changingConfigurations) endSession()
    }

    /** 長押しのメニューに出す項目。上から並べる順（ui.md）。検索語との組がない一覧（空の検索語・すべてのアプリ）には学習の項目を出さない。 */
    fun menuFor(row: ResultRow): List<RowAction> {
        if (content !is SearchContent.Results) return listOf(RowAction.APP_INFO, RowAction.MANAGE_LEARNING)
        return listOfNotNull(
            RowAction.APP_INFO,
            RowAction.SUPPRESS,
            RowAction.FORGET_LEARNING.takeIf { row.reason?.keyKind == SearchKeyKind.LEARNED },
            RowAction.MANAGE_LEARNING,
        )
    }

    /** [row] に「この語では出さない」の印を付ける（今の検索語の厳密形と、行のアプリ）。 */
    fun suppress(row: ResultRow) {
        val pair = suppressionOf(row)
        deps.appScope.launch { report(records.request(RecordsRequest.Suppress(pair, deps.clock()))) }
    }

    /** [row] を出した学習の組を消す。学習のキーで出ていなければ何もしない。 */
    fun forgetLearning(row: ResultRow) {
        val pair = learnedPairOf(row) ?: return
        deps.appScope.launch { report(records.request(RecordsRequest.Remove(pair))) }
    }

    /** 救済の知らせ [notice] を取り消す。保存に失敗したら知らせを残し、もう一度取り消せる。 */
    fun undo(notice: RescueNotice) {
        undoing += notice.id
        deps.appScope.launch {
            report(records.request(RecordsRequest.Undo(notice.id)))
            undoing -= notice.id
            dismissed -= notice.id
            publish()
        }
    }

    /** [notice] を出し終えた（押した・時間が過ぎた・打ち始めた）。取り消しの保存中なら消費しない（取り消しの材料を保つため）。 */
    fun noticeDone(notice: RescueNotice) {
        dismissed += notice.id
        if (notice.id !in undoing) records.consumeNotice(notice.id)
        publish()
    }

    /** 初回の案内を閉じた。 */
    fun dismissOnboarding() {
        deps.dismissOnboarding()
    }

    /** 記録の DB を作り直した知らせを出した。書き手の印を下ろす（この画面では打ち始めるまで出し続ける）。 */
    fun rebuiltNoticeShown() {
        records.acknowledgeRebuilt()
    }

    /** [row] のアプリ情報を開けなかった。索引が古い見込みなので同期を頼む。 */
    fun appInfoFailed(row: ResultRow) {
        deps.messages.show(UserMessage.AppInfoFailed(row.label))
        deps.index.requestSync()
    }

    /** 確定キー（IME の「検索」）。検索窓の直上の結果を開く。 */
    fun openTopResult(screen: ScreenToken) {
        state.value.topResult?.let { open(it, screen) }
    }

    /**
     * [app] を開く（architecture.md の「起動」）。画面 [screen] が出していた行から経路と学習の組を決める。
     *
     * 選んだ時点で表示名・時刻・学習の判断を固定してセッションを終え、起動中の印を立てる。記録の保存（学習と起動の記録の 1 つの頼み）→
     * OS への要求はアプリの寿命の scope で最後まで行う。保存に失敗してもアプリは開き、救済の Toast は保存の成功と OS の受付の両方の後にだけ出す。
     * 一時停止中の仕事用アプリへの要求は、学習にも起動の記録にも数えない（OS の再開の確認で取り消されたかが分からないため）。
     * 成否によらず、その起動の印を解く。閉じるのは元の画面だけで、呼び直しの後の画面は古い完了で閉じない。
     */
    fun open(
        app: AppId,
        screen: ScreenToken,
    ) {
        if (cleared || opening != null) return
        // 派生の索引を待つ間に消えた・隠したプロファイルのアプリは開かない
        if (app.profileSerial !in visibleSerials()) return
        val accepted = deps.timing.now()
        val request = openRequest(app)
        val label = labelOf(app) ?: app.packageName
        val at = deps.clock()
        val decision = finishSession(request)
        val ticket = OpenTicket(screen)
        opening = ticket
        // 今出している前の知らせは、この画面で出し終えたものとして消費する。保留に入ると画面から消え、閉じたときにも消費されないため。
        // 今回の起動で生じる知らせだけを、次の検索に残す
        state.value.notices.filter { it.id !in undoing }.forEach {
            dismissed += it.id
            records.consumeNotice(it.id)
        }
        noticesDeferred = true
        publish()
        val prepared = deps.launcher.prepare(app)
        deps.appScope.launch {
            val result =
                if (prepared.recordsChoice) {
                    val labels = decision.rescues.associateWith { label }
                    records.submit(RecordsRequest.Open(decision, app, at, labels)).await()
                } else {
                    null
                }
            // 一時停止中の要求は保存しないので、前の失敗の知らせを変えない
            if (prepared.recordsChoice) deps.openRecordFailed.value = result !is RecordsResult.Success
            deps.timing.launchRequested(accepted, recorded = prepared.recordsChoice)
            if (!prepared.launch()) {
                deps.messages.show(UserMessage.LaunchFailed(label))
                deps.index.requestSync()
                // 保存済みの選択は残す。後の選択や消去を、失敗の巻き戻しで上書きしないため
                if (opening === ticket) {
                    opening = null
                    // 非表示中は終了したまま保ち、復帰時の onScreenStarted で入力と読みの要求を再開する
                    if (screenStarted) startSession() else publish()
                }
                return@launch
            }
            (result as? RecordsResult.Success)?.notices?.lastOrNull()?.let {
                deps.messages.show(UserMessage.RescueLearned(it.pair.query.value, it.label))
            }
            // OS へ渡す最中にも呼び直されうる。閉じる直前に、同じ起動かを確かめる
            if (opening === ticket) {
                opening = null
                finishes.tryEmit(ticket.screen)
            }
        }
    }

    override fun onCleared() {
        cleared = true
        sessionActive = false
        tracker.closed()
        readingJob?.cancel()
        // 画面を閉じたら、出しかけの知らせを消費する（作り直しではないので、次の画面に持ち越さない）
        state.value.notices
            .filter { it.id !in undoing }
            .forEach { records.consumeNotice(it.id) }
    }

    /** 今出している行のうち [app] の表示名。出していなければ null。 */
    internal fun labelOf(app: AppId): String? =
        when (val shown = content) {
            is SearchContent.Results -> shown.rows
            is SearchContent.Suggestions -> shown.recent + shown.frequent
            is SearchContent.AllApps -> shown.rows
            else -> emptyList()
        }.firstOrNull { it.app == app }?.label

    /** [app] を開く頼み。今出しているものから経路と、行を出した学習の組を決める。 */
    internal fun openRequest(app: AppId): OpenRequest {
        val route =
            when (content) {
                is SearchContent.AllApps -> OpenRoute.ALL_APPS
                is SearchContent.Suggestions -> OpenRoute.SUGGESTION
                else -> OpenRoute.SEARCH_RESULT
            }
        val learned = (content as? SearchContent.Results)?.rows?.firstOrNull { it.app == app }?.let(::learnedPairOf)
        return OpenRequest(app, route, query, learnedPair = learned)
    }

    /** [row] に「この語では出さない」を選んだときの組: 今の検索語の厳密形と、行のアプリ（R4 の印は厳密形が等しいときだけ効く）。 */
    internal fun suppressionOf(row: ResultRow): LearnedPair = LearnedPair(SearchQuery.of(query).strict, row.app)

    /** [row] を出した学習の組。学習のキーで出ていなければ null。今の検索語でなく、当たった学習の語（前方一致なら長いほう）を消す。 */
    internal fun learnedPairOf(row: ResultRow): LearnedPair? =
        row.reason?.takeIf { it.keyKind == SearchKeyKind.LEARNED }?.let { LearnedPair(it.matchedText, row.app) }

    // 選んだ時点で判定を固定する。使った学習の組（前方一致・ローマ字で当たった元の語）は、判定にない情報なので押した行から足す
    private fun finishSession(request: OpenRequest): LearningDecision {
        sessionActive = false
        invalidateReading()
        return tracker
            .opened(deps.clock(), request.app, request.route.learning, request.query)
            .copy(used = request.learnedPair)
    }

    private fun endSession() {
        sessionActive = false
        tracker.closed()
        invalidateReading()
    }

    private fun startSession() {
        if (cleared || sessionActive) return
        sessionActive = true
        if (showingAllApps) tracker.allAppsShown(deps.clock())
        refresh()
    }

    private fun report(result: RecordsResult) {
        if (result is RecordsResult.Failure) deps.messages.show(UserMessage.RecordsFailed(result.reason))
    }

    private fun onRecords(next: RecordsState) {
        recordsState = next
        val nextLearning = learningOf(next)
        val nextHistory = historyOf(next)
        val learningChanged = nextLearning.records != learning.records
        val historyChanged = nextHistory.records != history.records
        learning = nextLearning
        history = nextHistory
        when {
            learningChanged -> {
                // 順位の信号は学習も使うので、派生の完成を待たずに今の学習で作り直す
                updateSignals()
                requestDerivation()
                refresh()
            }

            historyChanged -> {
                // 起動の記録だけの変化では、派生の索引を作り直さず、順位の信号だけを更新する
                updateSignals()
                refresh()
            }

            else -> {
                publish()
            }
        }
    }

    private fun onProfiles(next: Profiles) {
        if (next == profiles) return
        val workChanged = next.work != profiles.work || next.own != profiles.own
        profiles = next
        if (workChanged) {
            if (!showsProfileSwitch) profileTab = ProfileTab.PERSONAL
            requestDerivation()
            refresh()
        } else {
            publish()
        }
    }

    private fun onSettings(next: Settings) {
        if (next == settings) return
        val modeChanged = next.search.workProfileMode != settings.search.workProfileMode
        settings = next
        if (modeChanged) {
            if (next.search.workProfileMode != WorkProfileMode.SWITCH) profileTab = ProfileTab.PERSONAL
            requestDerivation()
            refresh()
        } else {
            publish()
        }
    }

    private fun currentInput(): DerivationInput =
        DerivationInput(deps.index.searchIndex.value, learning, profiles.own, profiles.work, workProfileMode)

    private fun requestDerivation() {
        derivationInputs.value = currentInput()
    }

    private fun applyDerived(built: DerivedIndexes) {
        // 届くまでの間に入力が変わっていたら、古い結果を採らない（次の完成を待つ）
        if (cleared || built.input != currentInput()) return
        derived = built
        updateSignals()
        refresh()
        if (built.input.index.size > 0) deps.timing.searchReady()
    }

    private fun updateSignals() {
        val built = derived ?: return
        val signals = history.asRankingSignals(deps.clock(), learning)
        personalEngine = SearchEngine(built.personal, signals = signals)
        workEngine = SearchEngine(built.work, signals = signals)
    }

    private val workProfileMode: WorkProfileMode get() = settings.search.workProfileMode

    private val showsProfileSwitch: Boolean get() = profiles.work.isNotEmpty() && workProfileMode == WorkProfileMode.SWITCH

    private val workPausedVisible: Boolean get() =
        profiles.workPaused &&
            when (workProfileMode) {
                WorkProfileMode.SWITCH -> profileTab == ProfileTab.WORK
                WorkProfileMode.MIXED -> profiles.work.isNotEmpty()
                WorkProfileMode.HIDDEN -> false
            }

    private val hasIndex: Boolean get() = (derived?.input?.index?.size ?: 0) > 0

    // 今のプロファイルと設定で出してよい番号。派生の索引の完成を待たずに、行と起動の受付に効かせる
    private fun visibleSerials(): Set<Long> =
        if (profileTab == ProfileTab.WORK && showsProfileSwitch) {
            profiles.work
        } else {
            IndexDeriver.personalSerials(profiles.own, profiles.work, workProfileMode)
        }

    private val selectedIndex: SearchIndex get() =
        derived?.let { if (profileTab == ProfileTab.WORK) it.work else it.personal } ?: SearchIndex.EMPTY

    private val selectedEngine: SearchEngine get() = if (profileTab == ProfileTab.WORK) workEngine else personalEngine

    // 「仕事用に N 件」を数える照合。切り替えを出していて、個人用を見ているときだけ
    private val hintEngine: SearchEngine? get() = workEngine.takeIf { profileTab == ProfileTab.PERSONAL && showsProfileSwitch }

    private fun refresh() {
        if (cleared) return
        wantedReading = null
        content = computeContent()
        publish()
        // 出してから読みを頼む（読みがすぐ終わる Dispatcher でも、表記だけの結果で読みの結果を上書きしないため）
        wantedReading?.let { (raw, searchQuery) -> startReading(raw, searchQuery) }
    }

    private fun computeContent(): SearchContent {
        if (showingAllApps) return allApps()
        val raw = query
        val searchQuery = SearchQuery.of(raw)
        if (searchQuery.isEmpty) return suggestions().also(::recordShown)
        val refined = reading?.takeIf { it.first == raw }?.second
        val waiting = searchQuery.needsReading && refined == null
        val shown = contentOf(selectedEngine.search(refined ?: searchQuery), hintEngine, refined ?: searchQuery, waiting)
        recordShown(shown)
        if (waiting && sessionActive) wantedReading = raw to searchQuery
        return shown
    }

    // 読みは検索語だけの関数なので、検索語で確かめれば足りる。当てるときは refresh が最新の入力で照合し直す
    private fun startReading(
        raw: String,
        searchQuery: SearchQuery,
    ) {
        if (readingFor == raw && readingJob?.isActive == true) return
        readingJob?.cancel()
        readingFor = raw
        readingJob =
            viewModelScope.launch {
                val refined =
                    withContext(deps.readingDispatcher) {
                        try {
                            searchQuery.withReading(deps.analyzer)
                        } catch (e: RuntimeException) {
                            // 辞書が読めなくても、表記のままの結果を出す
                            Log.w(TAG, "検索語に読みを付けられなかった: ${e.javaClass.simpleName}")
                            searchQuery
                        }
                    }
                // 待つ間に検索語が変わった・セッションが終わったなら当てない
                if (query != raw || !sessionActive || cleared) return@launch
                reading = raw to refined
                refresh()
            }
    }

    private fun invalidateReading() {
        readingJob?.cancel()
        readingJob = null
        readingFor = null
    }

    // 今の検索語と、上位の「見えていた」分を学習のセッションに送る。すべてのアプリの一覧は検索語の結果でないので送らない
    private fun recordShown(shown: SearchContent) {
        if (!sessionActive || shown is SearchContent.AllApps) return
        val visible =
            (shown as? SearchContent.Results)
                ?.rows
                .orEmpty()
                .take(learningConfig.visibleCount(rowCapacity))
                .map { it.app }
        tracker.queryShown(deps.clock(), query, visible)
    }

    private fun contentOf(
        results: List<SearchResult>,
        hint: SearchEngine?,
        searchQuery: SearchQuery,
        waitingForReading: Boolean,
    ): SearchContent {
        val allowed = visibleSerials()
        val rows = results.filter { it.app.profileSerial in allowed }.map { it.toRow() }
        return when {
            rows.isNotEmpty() -> {
                SearchContent.Results(rows)
            }

            waitingForReading || !hasIndex -> {
                SearchContent.Waiting
            }

            else -> {
                val work = profiles.work
                SearchContent.NoMatch(workMatches = hint?.search(searchQuery)?.count { it.app.profileSerial in work } ?: 0)
            }
        }
    }

    private fun suggestions(): SearchContent {
        if (!hasIndex) return SearchContent.Waiting
        val allowed = visibleSerials()
        val suggestions = history.suggestions(selectedIndex)
        return SearchContent.Suggestions(
            suggestions.recent.filter { it.id.profileSerial in allowed }.map { it.toRow() },
            suggestions.frequent.filter { it.id.profileSerial in allowed }.map { it.toRow() },
        )
    }

    private fun allApps(): SearchContent {
        if (!hasIndex) return SearchContent.Waiting
        val target = selectedIndex
        val allowed = visibleSerials()
        val list =
            nameOrderCache?.takeIf { it.first === target }?.second ?: run {
                val ordered = target.inNameOrder()
                val sections = ArrayList<AllAppsSection>()
                ordered.forEachIndexed { row, entry ->
                    if (sections.lastOrNull()?.label != entry.section) sections += AllAppsSection(entry.section, row)
                }
                SearchContent.AllApps(ordered.map { it.app.toRow() }, sections).also { nameOrderCache = target to it }
            }
        if (list.rows.all { it.app.profileSerial in allowed }) return list
        // 派生の索引の完成を待つ間だけ、出してはいけない行を除いて頭文字の索引を作り直す
        val rows = list.rows.filter { it.app.profileSerial in allowed }
        val sections = ArrayList<AllAppsSection>()
        val sectionOf = list.sectionOfRows()
        rows.forEachIndexed { row, item ->
            val label = sectionOf.getValue(item.app)
            if (sections.lastOrNull()?.label != label) sections += AllAppsSection(label, row)
        }
        return SearchContent.AllApps(rows, sections)
    }

    private fun SearchContent.AllApps.sectionOfRows(): Map<AppId, String> {
        val result = HashMap<AppId, String>()
        sections.forEachIndexed { i, section ->
            val end = sections.getOrNull(i + 1)?.firstRow ?: rows.size
            for (row in section.firstRow until end) result[rows[row].app] = section.label
        }
        return result
    }

    private fun publish() {
        if (cleared) return
        val notices =
            if (noticesDeferred) {
                emptyList()
            } else {
                recordsState.notices.filter { it.id !in undoing && it.id !in dismissed }
            }
        val status = deps.index.status.value
        val snapshot = deps.index.snapshot.value
        state.value =
            SearchUiState(
                query = query,
                content = content,
                showingAllApps = showingAllApps,
                profileSwitch = if (showsProfileSwitch) ProfileSwitch(profileTab, profiles.workPaused) else null,
                workPausedNotice = workPausedVisible,
                searchBarOnTop = settings.search.searchBarPosition == SearchBarPosition.TOP,
                showReasons = settings.search.showReasons,
                onboardingShown = !settings.onboardingDismissed,
                notices = notices,
                indexNotice =
                    IndexNotice.of(
                        status,
                        snapshot.listings,
                        deps.index.searchIndex.value.size > 0,
                        profiles.own,
                        workProfileMode,
                    ),
                openRecordFailed = deps.openRecordFailed.value,
                recordsRebuilt = rebuiltShown,
                focusKey = focusKey,
            )
    }

    // 混ぜて出すときだけ、仕事用の行に印を付ける。切り替えで仕事用を選んでいる間は全部が仕事用なので、読み上げを冗長にしない
    private fun isMixedWork(app: AppId): Boolean = workProfileMode == WorkProfileMode.MIXED && app.profileSerial in profiles.work

    private fun SearchResult.toRow(): ResultRow =
        ResultRow(app, label, reason.takeUnless { it.keyKind == SearchKeyKind.NAME }, isMixedWork(app))

    private fun IndexedApp.toRow(): ResultRow = ResultRow(id, label, isWork = isMixedWork(id))

    // 受理した起動 1 つ。比べるのは参照（呼び直しで解いた後の古い完了を見分ける）
    private class OpenTicket(
        val screen: ScreenToken,
    )

    private companion object {
        const val TAG = "PiuSearch"
        const val KEY_QUERY = "query"

        fun learningOf(state: RecordsState): LearnedMemory = state.records?.learned ?: LearnedMemory.EMPTY

        fun historyOf(state: RecordsState): LaunchHistory = state.records?.launches ?: LaunchHistory.EMPTY
    }
}
