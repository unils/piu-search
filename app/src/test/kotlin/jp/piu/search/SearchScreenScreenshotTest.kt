// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor

// 検索の画面の主な状態のスクリーンショット（testing.md の 4）。正解の画像は src/test/screenshots にあり、check で比べる。
// 見た目を変えたら ./gradlew :app:recordRoborazziDebug で撮り直し、差分を見てからコミットする。
// 結果は本物の照合（Kuromoji とブランド表）で作り、出た理由の文も本物の経路で出す。アイコンは色の丸の偽物
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ja-rJP-w360dp-h640dp-xhdpi")
class SearchScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyQueryShowsRecentAndFrequentApps() {
        capture("empty_query", query = "")
    }

    @Test
    fun emptyQueryWithoutHistoryShowsHint() {
        capture("empty_query_no_history", query = "", history = LaunchHistory.EMPTY)
    }

    @Test
    fun resultsWithReasons() {
        capture("results", query = "ちず")
    }

    @Test
    fun resultsWithSearchBarOnTop() {
        // 設定で検索窓を上に置いた。結果は上から並べ、1 位を検索窓の直下に置く
        capture("results_top", query = "ちず", searchBarOnTop = true)
    }

    @Test
    fun emptyQueryWithSearchBarOnTop() {
        // 最近使ったアプリが検索窓に近い側（上）に来る。検索窓の中に設定の歯車
        capture("empty_query_top", query = "", searchBarOnTop = true)
    }

    @Test
    fun workProfileWithSearchBarOnTop() {
        // 切り替えと一時停止の知らせも検索窓の側に寄せる
        capture(
            "profile_work_top",
            query = "きんたい",
            index = profileIndex,
            work = WORK.copy(paused = true),
            tab = ProfileTab.WORK,
            searchBarOnTop = true,
        )
    }

    @Test
    fun resultsWithoutReasons() {
        // 設定で出た理由を出さない。行は名前だけになる
        capture("results_no_reasons", query = "ちず", showReasons = false)
    }

    @Test
    fun noMatch() {
        capture("no_match", query = "ぴざ")
    }

    @Test
    @Config(qualifiers = "+night")
    fun resultsInDarkTheme() {
        capture("results_dark", query = "ちず")
    }

    @Test
    fun resultsWithFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        capture("results_font_200", query = "ちず")
    }

    @Test
    fun allApps() {
        // 検索語を残したまま、すべてのアプリを名前の順に出す。右端は頭文字の索引
        capture("all_apps", query = "ぴざ", allApps = true)
    }

    @Test
    fun allAppsWithFontScale200() {
        // 文字を大きくしても、索引は高さに収め、行は名前を 2 行まで出す
        RuntimeEnvironment.setFontScale(2f)
        capture("all_apps_font_200", query = "ぴざ", allApps = true)
    }

    @Test
    @Config(qualifiers = "ja-rJP-w640dp-h360dp-land-xhdpi")
    fun resultsInLandscape() {
        capture("results_landscape", query = "ちず")
    }

    @Test
    @Config(qualifiers = "ja-rJP-w640dp-h360dp-land-xhdpi")
    fun allAppsInLandscapeWithFontScale200() {
        // 低い画面に大きな文字。索引は何個かおきに出す
        RuntimeEnvironment.setFontScale(2f)
        capture("all_apps_landscape_font_200", query = "ぴざ", allApps = true)
    }

    @Test
    @Config(qualifiers = "ja-rJP-w640dp-h360dp-land-xhdpi")
    fun onboardingInLandscapeWithFontScale200() {
        // 本文は縦に送り、「閉じる」と検索窓は見えたまま
        RuntimeEnvironment.setFontScale(2f)
        capture("onboarding_landscape_font_200", query = "", onboarding = true)
    }

    @Test
    @Config(qualifiers = "ja-rJP-w800dp-h1280dp-mdpi")
    fun resultsOnATablet() {
        capture("results_tablet", query = "ちず")
    }

    @Test
    @Config(qualifiers = "ja-rJP-w1280dp-h800dp-land-mdpi")
    fun allAppsOnATabletInLandscape() {
        capture("all_apps_tablet_landscape", query = "ぴざ", allApps = true)
    }

    @Test
    fun noMatchWithWorkMatches() {
        // 個人用に一致がなく、仕事用にある。切り替えと「仕事用に N 件」
        capture("profile_work_matches", query = "きんたい", index = profileIndex, work = WORK)
    }

    @Test
    fun workProfilePaused() {
        capture("profile_work_paused", query = "きんたい", index = profileIndex, work = WORK.copy(paused = true), tab = ProfileTab.WORK)
    }

    @Test
    fun onboarding() {
        // 初回の案内。検索語が空の間、最近・よく使うアプリの代わりに出す
        capture("onboarding", query = "", onboarding = true)
    }

    @Test
    fun onboardingWithFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        capture("onboarding_font_200", query = "", onboarding = true)
    }

    @Test
    fun onboardingWithoutTileRequest() {
        // Android 12 以前は OS にタイルの追加を頼めないので、足し方の文だけを出す
        capture("onboarding_manual_tile", query = "", onboarding = true, tileRequest = false)
    }

    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun rowMenu() {
        // 長押しのメニューは別の窓（ポップアップ）に出るので、画面全体を撮る
        render(query = "ちず", menu = true)
        // 指で長押しすると、押した跡（ripple）の模様が回すたびに変わる。跡の出ない長押しの操作（TalkBack と同じ経路）で開く
        compose
            .onNode(hasText("Google マップ") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnLongClick)
        // メニューの出る動きを終えてから撮る
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/row_menu.png")
    }

    @Test
    fun learnedRow() {
        // すべてのアプリから開いて学んだ語。名前でも読みでも当たらず、出た理由は「学習」
        capture("learned_row", query = "でんしゃ", learning = LEARNING)
    }

    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun learnedRowMenu() {
        // 学習で出た行の長押し: アプリ情報・この語では出さない・この語での学習を消す
        render(query = "でんしゃ", menu = true, learning = LEARNING)
        compose
            .onNode(hasText("乗換案内") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/learned_row_menu.png")
    }

    @Test
    fun rescueNotice() {
        // 前に開いたときの救済の知らせ。検索窓の直上に［取り消す］つきで出す。
        // 知らせは数秒で下がるので、時計を止めて、出る動きの後で撮る
        compose.mainClock.autoAdvance = false
        render(query = "", learning = LEARNING, notices = listOf(RescueNotice(DENSHA, "乗換案内", null, NOW)))
        compose.mainClock.advanceTimeBy(NOTICE_SETTLE_MILLIS)
        compose.onRoot().captureRoboImage("src/test/screenshots/rescue_notice.png")
    }

    @Test
    fun resultRowIsReadAsNameThenReason() {
        // TalkBack は、行（押せる部品）にまとめた子の文を順に読む: 「Google マップ、ジャンル: 地図」
        render(query = "ちず")
        assertEquals(listOf("Google マップ", "ジャンル: 地図"), rowTexts("Google マップ"))
        // 名前で当たった行は名前だけ
        assertEquals(listOf("ちずぶらり"), rowTexts("ちずぶらり"))
    }

    @Test
    fun resultRowIsReadAsNameOnlyWithoutReasons() {
        render(query = "ちず", showReasons = false)
        assertEquals(listOf("Google マップ"), rowTexts("Google マップ"))
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun sectionIndexIsOneWideTargetWithJumpActions() {
        render(query = "ぴざ", allApps = true)
        val index = compose.onNodeWithTag(SECTION_INDEX_TAG).fetchSemanticsNode()
        val width = with(compose.density) { index.touchBoundsInRoot.width.toDp() }
        assertTrue("索引の幅: $width", width >= MIN_TOUCH)
        // 1 字ずつを TalkBack の項目にしない（48 dp に届かないため）。見出しへ飛ぶ操作を索引にまとめる
        assertEquals("頭文字の索引", index.config[SemanticsProperties.ContentDescription].single())
        val actions = index.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("「G」へ移動", "「L」へ移動", "「Y」へ移動", "「か」へ移動", "「た」へ移動", "「な」へ移動"), actions)
        assertEquals(0, compose.onAllNodes(hasAnyAncestor(hasTestTag(SECTION_INDEX_TAG))).fetchSemanticsNodes().size)

        // 最後の見出しへ飛ぶと、その見出しが一覧に出る
        compose.onNodeWithTag(SECTION_INDEX_TAG).performCustomAccessibilityActionWithLabel("「な」へ移動")
        compose.onNode(hasText("な") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun everyTouchTargetIsAtLeast48dp() {
        // ui.md の「押せる部分は 48 dp 以上」。主な状態の、押せる部品をすべて確かめる（文字の大きさ 200% でも）
        val states: List<() -> @Composable () -> Unit> =
            listOf(
                { screen(query = "") },
                { screen(query = "ちず", menu = true) },
                { screen(query = "ぴざ") },
                { screen(query = "ぴざ", allApps = true) },
                { screen(query = "きんたい", index = profileIndex, work = WORK) },
                { screen(query = "", onboarding = true) },
                { screen(query = "", searchBarOnTop = true, index = profileIndex, work = WORK) },
            )
        // 1 つのテストで setContent は 1 回だけなので、出すものと文字の大きさを状態で差し替える
        // （RuntimeEnvironment.setFontScale は Activity を作り直すので、ここでは LocalDensity で変える）
        var shown by mutableStateOf(states.first()())
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { shown() }
        }
        for (scale in listOf(1f, 2f)) {
            fontScale = scale
            for (state in states) {
                shown = state()
                compose.waitForIdle()
                assertTouchTargets("文字の大きさ ${scale * 100}%")
            }
        }
    }

    private fun assertTouchTargets(what: String) {
        val small =
            compose
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
                // 押せる範囲（見た目より広げたものを含む）と、画面の外に送られて切れる前の大きさ（縦に送る案内の中など）の大きいほう
                .map { node ->
                    val touch = node.touchBoundsInRoot
                    node to
                        with(compose.density) {
                            DpSize(
                                maxOf(touch.width, node.size.width.toFloat()).toDp(),
                                maxOf(touch.height, node.size.height.toFloat()).toDp(),
                            )
                        }
                }.filter { (_, size) -> size.width < MIN_TOUCH || size.height < MIN_TOUCH }
        assertEquals("$what: 48 dp に届かない部品", emptyList<String>(), small.map { (node, size) -> "${node.config} $size" })
    }

    /** [label] の行の、TalkBack が読む文の並び（まとめた木の Text）。 */
    private fun rowTexts(label: String): List<String> =
        compose
            .onNode(hasText(label, substring = false) and hasClickAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }

    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        query: String,
        history: LaunchHistory = HISTORY,
        index: SearchIndex = Companion.index,
        work: WorkProfileState = WorkProfileState.NONE,
        tab: ProfileTab = ProfileTab.PERSONAL,
        allApps: Boolean = false,
        onboarding: Boolean = false,
        tileRequest: Boolean = true,
        learning: LearnedMemory = LearnedMemory.EMPTY,
        searchBarOnTop: Boolean = false,
        showReasons: Boolean = true,
    ) {
        render(
            query,
            history,
            index,
            work,
            tab,
            allApps,
            onboarding = onboarding,
            tileRequest = tileRequest,
            learning = learning,
            searchBarOnTop = searchBarOnTop,
            showReasons = showReasons,
        )
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** [screen] の画面を出す。引数は [screen] と同じ。 */
    @Suppress("LongParameterList")
    private fun render(
        query: String,
        history: LaunchHistory = HISTORY,
        index: SearchIndex = Companion.index,
        work: WorkProfileState = WorkProfileState.NONE,
        tab: ProfileTab = ProfileTab.PERSONAL,
        allApps: Boolean = false,
        menu: Boolean = false,
        onboarding: Boolean = false,
        tileRequest: Boolean = true,
        learning: LearnedMemory = LearnedMemory.EMPTY,
        notices: List<RescueNotice> = emptyList(),
        searchBarOnTop: Boolean = false,
        showReasons: Boolean = true,
    ) {
        val content =
            screen(query, history, index, work, tab, allApps, menu, onboarding, tileRequest, learning, notices, searchBarOnTop, showReasons)
        compose.setContent { content() }
    }

    @Suppress("LongParameterList")
    private fun screen(
        query: String,
        history: LaunchHistory = HISTORY,
        index: SearchIndex = Companion.index,
        work: WorkProfileState = WorkProfileState.NONE,
        tab: ProfileTab = ProfileTab.PERSONAL,
        allApps: Boolean = false,
        menu: Boolean = false,
        onboarding: Boolean = false,
        tileRequest: Boolean = true,
        learning: LearnedMemory = LearnedMemory.EMPTY,
        notices: List<RescueNotice> = emptyList(),
        searchBarOnTop: Boolean = false,
        showReasons: Boolean = true,
    ): @Composable () -> Unit {
        val controller = SearchController(analyzer, direct, direct, index, history, clock = { NOW }, learning = learning)
        controller.onWorkProfileChanged(work)
        controller.selectProfile(tab)
        controller.onQueryChange(query)
        controller.showAllApps(allApps)
        return {
            PiuTheme {
                SearchScreen(
                    query = controller.query,
                    onQueryChange = controller::onQueryChange,
                    content = controller.content,
                    icons = FakeIcons,
                    onOpen = {},
                    onSearchAction = {},
                    showingAllApps = controller.showingAllApps,
                    profileSwitch = if (work.serials.isEmpty()) null else ProfileSwitch(controller.profileTab, work.paused),
                    autoFocus = false,
                    rowMenu = if (menu) controller::menuFor else { _ -> emptyList() },
                    onboarding = if (onboarding) OnboardingActions({}, if (tileRequest) ({}) else null, {}) else null,
                    notices = notices,
                    searchBarOnTop = searchBarOnTop,
                    showReasons = showReasons,
                    onOpenSettings = {},
                )
            }
        }
    }

    /** アプリごとに決まった色の丸。本物のアイコンは端末にしかない。 */
    private object FakeIcons : AppIcons {
        private val colors = intArrayOf(0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF8E24AA.toInt(), 0xFFFDD835.toInt())
        private val made = HashMap<AppId, ImageBitmap>()

        override fun cached(app: AppId): ImageBitmap =
            made.getOrPut(app) {
                val size = 80
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[Math.floorMod(app.packageName.hashCode(), colors.size)] }
                Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f, paint)
                bitmap.asImageBitmap()
            }

        override suspend fun load(app: AppId): ImageBitmap = cached(app)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val SETTLE_MILLIS = 5_000L
        const val NOTICE_SETTLE_MILLIS = 1_000L
        val MIN_TOUCH = 48.dp
        val direct = Executor { it.run() }
        val analyzer by lazy { KuromojiReadingAnalyzer() }

        fun app(
            pkg: String,
            label: String,
            vararg genres: String,
            serial: Long = 0,
        ) = AppEntry(AppId(pkg, "$pkg.Main", serial), label, genres = genres.map(::GenreId))

        const val WORK_SERIAL = 10L
        val WORK = WorkProfileState(setOf(WORK_SERIAL), paused = false)

        /** 個人用の APPS に、仕事用プロファイルの勤怠とメールを足したもの。 */
        val profileIndex: SearchIndex by lazy {
            SearchIndex.build(
                APPS +
                    listOf(
                        app("jp.example.kintai", "勤怠管理", serial = WORK_SERIAL),
                        app("com.google.android.gm", "Gmail", "email", serial = WORK_SERIAL),
                    ),
                SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.loadDefault())),
            )
        }

        val APPS =
            listOf(
                app("com.google.android.apps.maps", "Google マップ", "maps"),
                app("jp.co.yahoo.android.apps.map", "Yahoo!マップ", "maps"),
                app("jp.example.chizunavi", "地図ナビ"),
                app("jp.example.chizuburari", "ちずぶらり"),
                app("jp.co.jorudan.nrkj", "乗換案内"),
                app("com.google.android.gm", "Gmail", "email"),
                app("jp.naver.line.android", "LINE"),
                app("com.google.android.calendar", "カレンダー", "calendar"),
                app("jp.co.yahoo.android.weather.type1", "Yahoo!天気", "weather"),
            )

        /** 「でんしゃ」で乗換案内を、すべてのアプリから開いて学んだ。 */
        val DENSHA by lazy { LearnedPair(NormalizedText("でんしゃ"), APPS[4].id) }
        val LEARNING by lazy { LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(DENSHA)), NOW - 60_000) }

        val index: SearchIndex by lazy {
            SearchIndex.build(APPS, SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.loadDefault())))
        }

        /** 乗換案内を最近開き、LINE と Gmail をよく開いている。Google マップは何度か開いたので、同じジャンルの一致の中で上に出る。 */
        val HISTORY: LaunchHistory by lazy {
            var history = LaunchHistory.EMPTY

            fun open(
                index: Int,
                times: Int,
                at: Long,
            ) = repeat(times) { history = history.recorded(APPS[index].id, at) }
            open(index = 4, times = 2, at = NOW - 60_000)
            open(index = 0, times = 3, at = NOW - 3_600_000)
            open(index = 6, times = 30, at = NOW - 86_400_000 * 2)
            open(index = 5, times = 12, at = NOW - 86_400_000 * 3)
            // 最近の 4 件より古いもの。よく使うアプリに回る
            open(index = 8, times = 5, at = NOW - 86_400_000 * 10)
            open(index = 7, times = 1, at = NOW - 86_400_000 * 5)
            history
        }
    }
}
