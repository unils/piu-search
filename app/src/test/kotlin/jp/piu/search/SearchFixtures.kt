// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.reading.ReadingToken
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.ProfileCheck
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.RecordsRules
import jp.piu.search.core.state.RecordsState
import jp.piu.search.core.state.UserRecords
import jp.piu.search.data.Profiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.coroutines.CoroutineContext
import jp.piu.search.data.index.IndexStatus as IndexWriterStatus

/** 頼まれた仕事を溜め、テストが進めたときだけ動かす Dispatcher（裏のスレッドの代わり）。 */
internal class ManualDispatcher : CoroutineDispatcher() {
    val queue = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.addLast(block)
    }

    /** 溜まった仕事を 1 つ動かす。 */
    fun runNext() {
        queue.removeFirst().run()
    }
}

/** 偽の記録の書き手。頼みを覚え、結果は [reply] で決める（既定はすぐ成功）。 */
internal class FakeRecords : RecordsPort {
    override val state = MutableStateFlow(loaded())
    override val rebuiltAfterCorruption = MutableStateFlow(false)
    val requests = ArrayList<RecordsRequest>()
    val consumed = ArrayList<Long>()
    var refreshes = 0
    var acknowledged = 0
    var reply: (RecordsRequest) -> Deferred<RecordsResult> = { CompletableDeferred(RecordsResult.Success()) }

    override fun submit(request: RecordsRequest): Deferred<RecordsResult> {
        requests += request
        return reply(request)
    }

    override fun refresh(): Deferred<Unit> {
        refreshes++
        return CompletableDeferred(Unit)
    }

    override fun consumeNotice(id: Long) {
        consumed += id
    }

    override fun acknowledgeRebuilt() {
        acknowledged++
    }

    /** 学習 [learning] と起動の記録 [history] を読めた写しにする。 */
    fun set(
        learning: LearnedMemory = LearnedMemory.EMPTY,
        history: LaunchHistory = LaunchHistory.EMPTY,
    ) {
        state.value = loaded(learning, history)
    }

    /** 今の写しに、アプリを開いた頼み [open] を書けたものとして当てる（知らせも作る）。 */
    fun commit(open: RecordsRequest.Open): RecordsResult {
        val change = RecordsRules.change(state.value, open, ProfileCheck.Unknown)
        state.value = RecordsRules.commit(change, written = true)
        return RecordsRules.resultOf(change, written = true)
    }

    /** 送った頼みのうち、アプリを開く頼み。 */
    val opens: List<RecordsRequest.Open> get() = requests.filterIsInstance<RecordsRequest.Open>()

    companion object {
        fun loaded(
            learning: LearnedMemory = LearnedMemory.EMPTY,
            history: LaunchHistory = LaunchHistory.EMPTY,
        ): RecordsState = RecordsRules.loaded(RecordsState.INITIAL, UserRecords(learning, history), emptySet(), ProfileCheck.Unknown)
    }
}

/** 偽の索引の書き手。 */
internal class FakeIndex : IndexPort {
    override val searchIndex = MutableStateFlow(SearchIndex.EMPTY)
    override val snapshot = MutableStateFlow(IndexSnapshot.EMPTY)
    override val status = MutableStateFlow(IndexWriterStatus(loaded = true))
    val foreground = ArrayList<Boolean>()

    /** 頼まれた同期の返事。テストが終わらせるまで終わらない。 */
    val syncReplies = ArrayList<CompletableDeferred<Unit>>()
    val syncs: Int get() = syncReplies.size

    override fun setForeground(visible: Boolean) {
        foreground += visible
    }

    override fun requestSync(): Deferred<Unit> = CompletableDeferred<Unit>().also(syncReplies::add)
}

/** 偽の起動。 */
internal class FakeLauncher : Launcher {
    var paused: (AppId) -> Boolean = { false }
    var accepts = true
    val launched = ArrayList<AppId>()
    var duringLaunch: () -> Unit = {}

    override fun prepare(app: AppId): PreparedLaunch =
        PreparedLaunch(recordsChoice = !paused(app)) {
            duringLaunch()
            if (accepts) launched += app
            accepts
        }
}

/** 出した知らせを覚える。 */
internal class FakeMessages : UserMessages {
    val shown = ArrayList<UserMessage>()

    override fun show(message: UserMessage) {
        shown += message
    }
}

/** 計測の印を覚える。 */
internal class FakeTiming : SearchTiming {
    var ready = 0
    val launches = ArrayList<Boolean>()

    override fun now(): Long = 0

    override fun searchReady() {
        ready++
    }

    override fun launchRequested(
        acceptedAt: Long,
        recorded: Boolean,
    ) {
        launches += recorded
    }
}

/**
 * 偽の部品で組んだ [SearchViewModel]。画面のスレッドは [scope] の仮想の時計の Dispatcher（`Dispatchers.setMain` で渡しておく）、
 * 読みと派生の索引は既定で同じ時計で動かし、止めたいときは [ManualDispatcher] を渡す。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SearchHarness(
    private val scope: TestScope,
    index: SearchIndex = SearchIndex.EMPTY,
    profiles: Profiles = Profiles(OWN, emptySet(), workPaused = false),
    settings: Settings = Settings(onboardingDismissed = true),
    analyzer: ReadingAnalyzer = SearchFixtures.analyzer,
    val reading: CoroutineDispatcher? = null,
    val derived: CoroutineDispatcher? = null,
    tracker: SessionTracker = SessionTracker(),
    config: LearningConfig = LearningConfig.DEFAULT,
    savedState: SavedStateHandle = SavedStateHandle(),
    learning: LearnedMemory = LearnedMemory.EMPTY,
    history: LaunchHistory = LaunchHistory.EMPTY,
    appScope: CoroutineScope = scope.backgroundScope,
) {
    val indexPort = FakeIndex().apply { searchIndex.value = index }
    val records = FakeRecords().apply { set(learning, history) }
    val profiles = MutableStateFlow(profiles)
    val settings = MutableStateFlow(settings)
    val launcher = FakeLauncher()
    val messages = FakeMessages()
    val timing = FakeTiming()
    val openRecordFailed = MutableStateFlow(false)
    val searchForeground = MutableStateFlow(false)
    var profileRefreshes = 0
    val screen = ScreenToken()

    private val store = ViewModelStore()

    private val created =
        SearchViewModel(
            SearchDependencies(
                index = indexPort,
                records = records,
                profiles = this.profiles,
                refreshProfiles = { profileRefreshes++ },
                settings = this.settings,
                dismissOnboarding = { this.settings.value = this.settings.value.copy(onboardingDismissed = true) },
                openRecordFailed = openRecordFailed,
                searchForeground = searchForeground,
                launcher = launcher,
                messages = messages,
                analyzer = analyzer,
                appScope = appScope,
                readingDispatcher = reading ?: kotlinx.coroutines.Dispatchers.Main,
                derivedDispatcher = derived ?: kotlinx.coroutines.Dispatchers.Main,
                clock = { NOW },
                timing = timing,
            ),
            savedState,
            tracker,
            config,
        )

    // 閉じる（onCleared）ために、ViewModelStore を通して持つ
    val vm: SearchViewModel =
        ViewModelProvider.create(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = created as T
            },
        )[SearchViewModel::class.java]

    /** ViewModel を閉じる（画面が終わった）。 */
    fun clear() = store.clear()

    init {
        settle()
    }

    /** 画面のスレッドと、止めていない裏の仕事を、することがなくなるまで進める。 */
    fun settle() {
        scope.runCurrent()
    }

    val state: SearchUiState get() = vm.uiState.value

    val content: SearchContent get() = state.content

    fun rows(): List<ResultRow> = (content as? SearchContent.Results)?.rows.orEmpty()

    fun labels(): List<String> = rows().map { it.label }

    /** 検索語を変えて進める。 */
    fun type(query: String) {
        vm.onQueryChange(query)
        settle()
    }

    /** [app] を開いて進める。 */
    fun open(app: AppId) {
        vm.open(app, screen)
        settle()
    }

    /** 学習を差し替えて進める。 */
    fun learn(memory: LearnedMemory) {
        records.set(
            memory,
            records.state.value.records
                ?.launches ?: LaunchHistory.EMPTY,
        )
        settle()
    }

    /** 起動の記録を差し替えて進める。 */
    fun launches(history: LaunchHistory) {
        records.set(
            records.state.value.records
                ?.learned ?: LearnedMemory.EMPTY,
            history,
        )
        settle()
    }

    /** 送った最後のアプリを開く頼みの判定。 */
    val lastDecision: LearningDecision get() = records.opens.last().decision

    companion object {
        const val OWN = 0L
        const val NOW = 1_000_000_000L
    }
}

/** 照合のテストが共有するアプリと索引。 */
internal object SearchFixtures {
    val TRANSIT = AppId("io.example.transit", "Main", 0)
    val MAPS = AppId("io.example.maps", "Main", 0)
    val ROAD = AppId("io.example.road", "Main", 0)
    const val WORK_SERIAL = 10L
    val MAPS_WORK = AppId("io.example.maps", "Main", WORK_SERIAL)
    val MAIL_WORK = AppId("io.example.mail", "Main", WORK_SERIAL)

    /** 表にある語にだけ読みを付ける偽物。 */
    val analyzer =
        ReadingAnalyzer { text ->
            val readings =
                mapOf("乗換" to "のりかえ", "のり" to "のり", "換え" to "かえ", "テスト" to "てすと", "地図" to "ちず", "道" to "みち")
            var start = 0
            buildList {
                while (start < text.length) {
                    val word = readings.keys.firstOrNull { text.startsWith(it, start) } ?: text.substring(start, start + 1)
                    add(ReadingToken(word, readings[word], start))
                    start += word.length
                }
            }
        }

    val INDEX: SearchIndex by lazy {
        SearchIndex.build(
            listOf(
                AppEntry(TRANSIT, "乗換テスト"),
                AppEntry(MAPS, "地図テスト"),
                AppEntry(ROAD, "道テスト", genres = listOf(GenreId("maps"))),
            ),
            SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList()))),
        )
    }

    /** 個人用に乗換と地図、仕事用に地図とメール。 */
    val PROFILE_INDEX: SearchIndex by lazy {
        SearchIndex.build(
            listOf(
                AppEntry(TRANSIT, "乗換テスト"),
                AppEntry(MAPS, "地図"),
                AppEntry(MAPS_WORK, "地図"),
                AppEntry(MAIL_WORK, "メール"),
            ),
            SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList()))),
        )
    }

    val WORK = Profiles(0, setOf(WORK_SERIAL), workPaused = false)
    val WORK_PAUSED = Profiles(0, setOf(WORK_SERIAL), workPaused = true)
}
