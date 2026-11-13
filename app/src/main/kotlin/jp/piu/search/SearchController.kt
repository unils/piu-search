// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LaunchRoute
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.MatchReason
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.search.SearchQuery
import jp.piu.search.core.search.SearchResult
import jp.piu.search.data.IndexListener
import java.util.concurrent.Executor

/**
 * 結果の 1 行。
 *
 * @property app アプリの識別子
 * @property label 表示名
 * @property reason 出た理由。名前で一致したとき・空の検索語の一覧では null（ui.md の「理由は名前で一致しなかったときだけ」）
 */
data class ResultRow(
    val app: AppId,
    val label: String,
    val reason: MatchReason? = null,
)

/** 検索の画面の、検索窓の上に出すもの。 */
sealed interface SearchContent {
    /** 何も出さない。索引を読む前と、漢字の検索語の読みを待つ間（「見つかりません」を一瞬出さないため）。 */
    data object Waiting : SearchContent

    /**
     * 空の検索語のとき: 最近使ったアプリとよく使うアプリ。どちらも先頭が最も上位（検索窓に近い）。
     * どちらも空（まだ何も開いていない）なら、探し方の案内を出す。
     *
     * @property recent 最近使ったアプリ
     * @property frequent よく使うアプリ（[recent] にあるものは除く）
     */
    data class Suggestions(
        val recent: List<ResultRow>,
        val frequent: List<ResultRow>,
    ) : SearchContent

    /**
     * 検索の結果。
     *
     * @property rows 並べた順の行。先頭が 1 位で、検索窓の直上に出す
     */
    data class Results(
        val rows: List<ResultRow>,
    ) : SearchContent

    /**
     * 一致がない。
     *
     * @property workMatches 個人用を見ていて一致がないとき、仕事用に当たる数（ui.md の「仕事用に N 件」）。ほかは 0
     */
    data class NoMatch(
        val workMatches: Int = 0,
    ) : SearchContent

    /**
     * すべてのアプリ（ui.md の「すべてのアプリ」）。選んでいるプロファイルのアプリを名前の順に並べる。
     *
     * @property rows 並べた順の行。先頭が一覧の上端
     * @property sections 頭文字の索引。[rows] の順に並ぶ
     */
    data class AllApps(
        val rows: List<ResultRow>,
        val sections: List<AllAppsSection>,
    ) : SearchContent
}

/**
 * 「すべてのアプリ」の頭文字の索引の 1 つ。
 *
 * @property label 見出し（`A`・`あ`・`#` など）
 * @property firstRow その見出しの最初の行の、[SearchContent.AllApps.rows] での位置
 */
data class AllAppsSection(
    val label: String,
    val firstRow: Int,
)

/** 検索の対象のプロファイル（ui.md の「仕事用プロファイル」）。 */
enum class ProfileTab {
    /** 個人用。このアプリが動いているプロファイル。 */
    PERSONAL,

    /** 仕事用。同じプロファイルグループのほかのプロファイル。 */
    WORK,
}

/**
 * 仕事用プロファイルの状態。
 *
 * @property serials 仕事用プロファイルのシリアル番号。空なら仕事用プロファイルはなく、切り替えを出さない
 * @property paused 一時停止中か
 */
data class WorkProfileState(
    val serials: Set<Long>,
    val paused: Boolean,
) {
    /** 定数。 */
    companion object {
        /** 仕事用プロファイルがない。 */
        val NONE = WorkProfileState(emptySet(), paused = false)
    }
}

/**
 * アプリを開いた経路。学習で、すべてのアプリから開いた（救済。learning.md の R2）かを見分けるのに使う。
 *
 * @property learning 学習の判定に渡す経路
 */
enum class OpenRoute(
    val learning: LaunchRoute,
) {
    /** 検索の結果（確定キーを含む）。 */
    SEARCH_RESULT(LaunchRoute.SEARCH_RESULT),

    /** 空の検索語の一覧（最近・よく使うアプリ）。 */
    SUGGESTION(LaunchRoute.SUGGESTION),

    /** すべてのアプリの一覧。 */
    ALL_APPS(LaunchRoute.ALL_APPS),
}

/**
 * 結果の行の長押しのメニューの項目（ui.md の「長押しのメニュー」）。[SearchController.menuFor] で行ごとに出し分ける。
 *
 * @property label メニューに出す文字
 */
enum class RowAction(
    @param:StringRes val label: Int,
) {
    /** OS の設定画面の、そのアプリのページ。アンインストールもここから行う。 */
    APP_INFO(R.string.menu_app_info),

    /** この検索語ではこのアプリを出さない（learning.md の R4）。検索の結果の行だけ。 */
    SUPPRESS(R.string.menu_suppress),

    /** この行を出した学習の記録を消す。学習のキーで出ている行だけ。 */
    FORGET_LEARNING(R.string.menu_forget_learning),

    /** 学習の一覧を開く。どの行にも出す（設定画面の「学習」と同じ画面）。 */
    MANAGE_LEARNING(R.string.menu_manage_learning),
}

/**
 * アプリを開く頼み。開いたら、経路と検索語で学習する（[SearchController.finishSession]）。
 *
 * @property app 開くアプリ
 * @property route 開いた経路
 * @property query 開いたときに検索窓にあった検索語（打ったまま。正規化しない）
 * @property learnedPair 行を出した学習のキーの組。起動の完了を待つ間に結果が変わっても、押した行の語を使う
 */
data class OpenRequest(
    val app: AppId,
    val route: OpenRoute,
    val query: String,
    val learnedPair: LearnedPair? = null,
)

/**
 * 検索の画面の状態。
 *
 * 照合は軽い（アプリ 500 個で 0.2 ms ほど）ので、入力のたびに画面のスレッドで行う。
 * 漢字の検索語は、まず表記のままで結果を出し、[background] で読みを付けてから結果を出し直す（matching.md の「検索語の扱い」）。
 * 索引と起動の記録の差し替え（[onIndexChanged]・[onHistoryChanged]）は裏のスレッドから届くので、[main] に移してから反映する。
 * 索引を読み込む前は空の索引で照らすので、開いてすぐ打っても何も出ないだけで落ちない。
 * 照らすのは選んでいるプロファイル（[profileTab]）のアプリだけ。個人用で一致がなければ、仕事用の件数を [SearchContent.NoMatch] に入れる。
 * 仕事用のアプリの扱いは設定（[workProfileMode]）で変わる: 切り替える（上の通り）・個人用と混ぜる・出さない。
 *
 * 学習（[learning]）は索引に重ねて照らす（[SearchIndex.withLearning]）。結果を出すたびに、検索語と見えていた上位を [tracker] に送り、
 * アプリを開いたら（[finishSession]）記録する組を判定する。
 *
 * @param clock 今の時刻（エポックからのミリ秒）。起動の記録の新しさと、学習の出来事の時刻に使う
 */
class SearchController(
    private val analyzer: ReadingAnalyzer,
    private val background: Executor,
    private val main: Executor,
    index: SearchIndex = SearchIndex.EMPTY,
    history: LaunchHistory = LaunchHistory.EMPTY,
    private val clock: () -> Long = System::currentTimeMillis,
    learning: LearnedMemory = LearnedMemory.EMPTY,
    private val tracker: SessionTracker = SessionTracker(),
    private val learningConfig: LearningConfig = LearningConfig.DEFAULT,
) : IndexListener,
    LaunchHistoryListener,
    LearningListener {
    private var index = index
    private var history = history
    private var learning = learning
    private var workSerials: Set<Long> = emptySet()

    /** 仕事用プロファイルのアプリの扱い（設定）。画面が切り替えと一時停止の知らせを出し分けるので、Compose の状態にする。 */
    var workProfileMode: WorkProfileMode by mutableStateOf(WorkProfileMode.SWITCH)
        private set

    // 学習を重ねた索引。index か learning が変わったときだけ作り直す（起動の記録が変わるたびには作らない）
    private var learnedFor: Pair<SearchIndex, LearnedMemory>? = null
    private var learnedIndex = index

    // 結果の一覧に収まる行の数（画面が測って知らせる）。「見えていた」上位の件数を決める
    private var rowCapacity = 0

    // 選んでいるプロファイルごとの索引と照合。index・history・workSerials が変わったら作り直す
    private var personalIndex = index
    private var workIndex = SearchIndex.EMPTY
    private var personalEngine = SearchEngine(index)
    private var workEngine = SearchEngine(SearchIndex.EMPTY)
    private var nameOrderCache: Pair<SearchIndex, SearchContent.AllApps>? = null

    /** 打たれている検索語。 */
    var query: String by mutableStateOf("")
        private set

    /** 検索窓の上に出すもの。 */
    var content: SearchContent by mutableStateOf(SearchContent.Waiting)
        private set

    /** 仕事用プロファイルの状態。 */
    var workProfile: WorkProfileState by mutableStateOf(WorkProfileState.NONE)
        private set

    /** 検索の対象のプロファイル。開いたときは常に個人用。 */
    var profileTab: ProfileTab by mutableStateOf(ProfileTab.PERSONAL)
        private set

    /** すべてのアプリを出しているか。 */
    var showingAllApps: Boolean by mutableStateOf(false)
        private set

    init {
        rebuildEngines()
        refresh()
    }

    /** 確定キー（IME の「検索」）で開くアプリ。検索窓の直上の結果。結果がない・検索語が空なら null。 */
    val topResult: AppId? get() = (content as? SearchContent.Results)?.rows?.firstOrNull()?.app

    /** 今出している行のうち [app] の表示名。出していなければ null。 */
    fun labelOf(app: AppId): String? =
        when (val shown = content) {
            is SearchContent.Results -> shown.rows
            is SearchContent.Suggestions -> shown.recent + shown.frequent
            is SearchContent.AllApps -> shown.rows
            else -> emptyList()
        }.firstOrNull { it.app == app }?.label

    /**
     * 呼び出されるたびに増える数。画面が残っていて呼び出し直されたとき（[reset]）に、検索窓へ入力を向け直す合図にする。
     */
    var openCount: Int by mutableIntStateOf(0)
        private set

    /**
     * 新しく呼び出されたときの状態に戻す: 空の検索語・個人用・検索の結果（ui.md の「画面の振る舞い」「仕事用プロファイル」）。
     * 画面が残っているところへアイコン・アシスタント・タイルから呼び出し直されたとき（`onNewIntent`）に使う。画面のスレッドから呼ぶ。
     */
    fun reset() {
        tracker.closed()
        query = ""
        showingAllApps = false
        profileTab = ProfileTab.PERSONAL
        openCount++
        refresh()
    }

    /**
     * [row] の長押しのメニューに出す項目。上から並べる順（ui.md）。
     *
     * 「この語では出さない」は検索の結果の行に、「この語での学習を消す」は学習のキーで出ている行にだけ出す。
     * 「学習の一覧」はどの行にも最後に出す。
     * 空の検索語の一覧とすべてのアプリの一覧の行には、検索語との組がないので出さない。
     */
    fun menuFor(row: ResultRow): List<RowAction> {
        if (content !is SearchContent.Results) return listOf(RowAction.APP_INFO, RowAction.MANAGE_LEARNING)
        return listOfNotNull(
            RowAction.APP_INFO,
            RowAction.SUPPRESS,
            RowAction.FORGET_LEARNING.takeIf { row.reason?.keyKind == SearchKeyKind.LEARNED },
            RowAction.MANAGE_LEARNING,
        )
    }

    /** [row] に「この語では出さない」を選んだときの組: 今の検索語の厳密形と、行のアプリ（R4 の印は厳密形が等しいときだけ効く）。 */
    fun suppressionOf(row: ResultRow): LearnedPair = LearnedPair(SearchQuery.of(query).strict, row.app)

    /** [row] を出した学習の組。学習のキーで出ていなければ null。今の検索語でなく、当たった学習の語（前方一致なら長いほう）を消す。 */
    fun learnedPairOf(row: ResultRow): LearnedPair? =
        row.reason?.takeIf { it.keyKind == SearchKeyKind.LEARNED }?.let { LearnedPair(it.matchedText, row.app) }

    /** 結果の一覧に [rows] 行が収まる。画面の大きさが変わるたびに画面から呼ぶ。 */
    fun onRowCapacity(rows: Int) {
        rowCapacity = rows
    }

    /** 結果をスクロールした。 */
    fun onScrolled() {
        tracker.scrolled(clock())
    }

    /**
     * [request] のアプリを開いた: セッションを終え、記録する組を返す（learning.md の R1・R2）。集めた出来事は捨てる。
     * 画面のスレッドから、アプリを開けたときだけ呼ぶ。
     */
    fun finishSession(request: OpenRequest): LearningDecision =
        tracker.opened(clock(), request.app, request.route.learning, request.query).copy(used = request.learnedPair)

    /** 何も開かずにセッションが終わった（画面を離れた）。集めた出来事を捨てる。画面に戻ったら [startSession] を呼ぶ。 */
    fun endSession() {
        tracker.closed()
    }

    /** 画面に戻った: 今出している検索語と結果から、新しいセッションを始める。 */
    fun startSession() {
        if (showingAllApps) tracker.allAppsShown(clock())
        recordShown(content)
    }

    /** [app] を開く頼み。今出しているものから経路を決める。 */
    fun openRequest(app: AppId): OpenRequest {
        val route =
            when (content) {
                is SearchContent.AllApps -> OpenRoute.ALL_APPS
                is SearchContent.Suggestions -> OpenRoute.SUGGESTION
                else -> OpenRoute.SEARCH_RESULT
            }
        val learned = (content as? SearchContent.Results)?.rows?.firstOrNull { it.app == app }?.let(::learnedPairOf)
        return OpenRequest(app, route, query, learnedPair = learned)
    }

    /** 検索語を [raw] にする。すべてのアプリを出していたら、検索の結果に戻る（ui.md）。画面のスレッドから呼ぶ。 */
    fun onQueryChange(raw: String) {
        query = raw
        showingAllApps = false
        refresh()
    }

    /** すべてのアプリを出すか（[show]）。検索語は残す。画面のスレッドから呼ぶ。 */
    fun showAllApps(show: Boolean) {
        if (showingAllApps == show) return
        showingAllApps = show
        if (show) tracker.allAppsShown(clock())
        refresh()
    }

    /**
     * 「個人用｜仕事用」の切り替えを出すか。仕事用プロファイルがあり、設定が「切り替えて検索する」のときだけ。
     * 混ぜるときと出さないときは、検索の対象は常に [ProfileTab.PERSONAL] の側（混ぜるなら仕事用も含む）。
     */
    val showsProfileSwitch: Boolean get() = workSerials.isNotEmpty() && workProfileMode == WorkProfileMode.SWITCH

    /**
     * 仕事用プロファイルが一時停止中で、そのアプリが今の結果に出うるか（一時停止の知らせを出すか）。
     * 切り替えるときは仕事用を選んでいる間、混ぜるときは常に。出さないときは出ない。
     */
    val workPausedVisible: Boolean get() =
        workProfile.paused &&
            when (workProfileMode) {
                WorkProfileMode.SWITCH -> profileTab == ProfileTab.WORK
                WorkProfileMode.MIXED -> workSerials.isNotEmpty()
                WorkProfileMode.HIDDEN -> false
            }

    /** 仕事用プロファイルのアプリの扱いを [mode] にする（設定画面から戻ったとき）。画面のスレッドから呼ぶ。 */
    fun onWorkProfileModeChanged(mode: WorkProfileMode) {
        if (workProfileMode == mode) return
        workProfileMode = mode
        if (mode != WorkProfileMode.SWITCH) profileTab = ProfileTab.PERSONAL
        rebuildEngines()
        refresh()
    }

    /** 検索の対象を [tab] にする。切り替えを出していなければ（[showsProfileSwitch]）個人用のまま。画面のスレッドから呼ぶ。 */
    fun selectProfile(tab: ProfileTab) {
        val next = if (!showsProfileSwitch) ProfileTab.PERSONAL else tab
        if (profileTab == next) return
        profileTab = next
        refresh()
    }

    /** 仕事用プロファイルの状態を [state] にする（画面を開いたとき・追加・削除・一時停止）。画面のスレッドから呼ぶ。 */
    fun onWorkProfileChanged(state: WorkProfileState) {
        if (workProfile == state) return
        workProfile = state
        if (state.serials != workSerials) {
            workSerials = state.serials
            if (!showsProfileSwitch) profileTab = ProfileTab.PERSONAL
            rebuildEngines()
        }
        refresh()
    }

    /** 索引を [index] に差し替え、今の検索語で結果を出し直す（architecture.md の「索引の作成と更新」の 5）。 */
    override fun onIndexChanged(index: SearchIndex) {
        main.execute {
            this.index = index
            rebuildEngines()
            refresh()
        }
    }

    /** 起動の記録を [history] に差し替え、加点と空の検索語の一覧を出し直す。 */
    override fun onHistoryChanged(history: LaunchHistory) {
        main.execute {
            this.history = history
            rebuildEngines()
            refresh()
        }
    }

    /** 学習を [memory] に差し替え、今の検索語で結果を出し直す（次の入力を待たずに効かせる）。 */
    override fun onLearningChanged(memory: LearnedMemory) {
        main.execute {
            if (learning === memory) return@execute
            learning = memory
            rebuildEngines()
            refresh()
        }
    }

    private fun rebuildEngines() {
        val signals = history.asRankingSignals(clock(), learning)
        if (learnedFor?.let { it.first !== index || it.second !== learning } != false) {
            learnedIndex = index.withLearning(learning)
            learnedFor = index to learning
        }
        val index = learnedIndex
        // 仕事用プロファイルがない・混ぜるなら絞らない。個人用はプロファイルの番号でなく「仕事用でない」で決め、取りこぼさない
        personalIndex =
            if (workSerials.isEmpty() || workProfileMode == WorkProfileMode.MIXED) {
                index
            } else {
                index.restrictedTo(workSerials, exclude = true)
            }
        workIndex = if (showsProfileSwitch) index.restrictedTo(workSerials) else SearchIndex.EMPTY
        personalEngine = SearchEngine(personalIndex, signals = signals)
        workEngine = SearchEngine(workIndex, signals = signals)
    }

    private val selectedIndex: SearchIndex get() = if (profileTab == ProfileTab.WORK) workIndex else personalIndex

    private val selectedEngine: SearchEngine get() = if (profileTab == ProfileTab.WORK) workEngine else personalEngine

    /** 「仕事用に N 件」を数える照合。切り替えを出していて、個人用を見ているときだけ。 */
    private val hintEngine: SearchEngine? get() = workEngine.takeIf { profileTab == ProfileTab.PERSONAL && showsProfileSwitch }

    private fun refresh() {
        if (showingAllApps) {
            content = allApps()
            return
        }
        val raw = query
        val current = selectedEngine
        val hint = hintEngine
        val searchQuery = SearchQuery.of(raw)
        if (searchQuery.isEmpty) {
            content = suggestions()
            recordShown(content)
            return
        }
        content = contentOf(current.search(searchQuery), hint, searchQuery, waitingForReading = searchQuery.needsReading)
        recordShown(content)
        if (!searchQuery.needsReading) return
        background.execute {
            val withReading =
                try {
                    searchQuery.withReading(analyzer)
                } catch (e: RuntimeException) {
                    // 辞書が読めなくても、表記のままの結果は出ている
                    Log.w(TAG, "検索語に読みを付けられなかった", e)
                    null
                }
            main.execute {
                // 待つ間に検索語・索引・プロファイル・表示が変わっていたら、古い結果で上書きしない
                if (query != raw || selectedEngine !== current || hintEngine !== hint || showingAllApps) return@execute
                val refined = withReading ?: searchQuery
                content = contentOf(current.search(refined), hint, refined, waitingForReading = false)
                // 読みを付けて結果が変わったら、同じ検索語でもう一度送る（後の結果で「見えていた」を置き換える）
                recordShown(content)
            }
        }
    }

    /** 今の検索語と、[shown] の上位の「見えていた」分を学習のセッションに送る。すべてのアプリの一覧は検索語の結果でないので送らない。 */
    private fun recordShown(shown: SearchContent) {
        if (shown is SearchContent.AllApps) return
        val visible =
            (shown as? SearchContent.Results)
                ?.rows
                .orEmpty()
                .take(learningConfig.visibleCount(rowCapacity))
                .map { it.app }
        tracker.queryShown(clock(), query, visible)
    }

    private fun contentOf(
        results: List<SearchResult>,
        hint: SearchEngine?,
        searchQuery: SearchQuery,
        waitingForReading: Boolean,
    ): SearchContent =
        when {
            results.isNotEmpty() -> SearchContent.Results(results.map { it.toRow() })
            waitingForReading || index.size == 0 -> SearchContent.Waiting
            else -> SearchContent.NoMatch(workMatches = hint?.search(searchQuery)?.size ?: 0)
        }

    private fun suggestions(): SearchContent {
        if (index.size == 0) return SearchContent.Waiting
        val suggestions = history.suggestions(selectedIndex)
        return SearchContent.Suggestions(suggestions.recent.map { it.toRow() }, suggestions.frequent.map { it.toRow() })
    }

    private fun allApps(): SearchContent {
        if (index.size == 0) return SearchContent.Waiting
        val target = selectedIndex
        nameOrderCache?.let { (cachedFor, list) -> if (cachedFor === target) return list }
        val ordered = target.inNameOrder()
        val sections = ArrayList<AllAppsSection>()
        ordered.forEachIndexed { row, entry ->
            if (sections.lastOrNull()?.label != entry.section) sections += AllAppsSection(entry.section, row)
        }
        val list = SearchContent.AllApps(ordered.map { it.app.toRow() }, sections)
        nameOrderCache = target to list
        return list
    }

    private companion object {
        const val TAG = "PiuSearch"

        fun SearchResult.toRow(): ResultRow = ResultRow(app, label, reason.takeUnless { it.keyKind == SearchKeyKind.NAME })

        fun IndexedApp.toRow(): ResultRow = ResultRow(id, label)
    }
}
