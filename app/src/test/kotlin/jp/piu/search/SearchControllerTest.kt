// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.reading.ReadingToken
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.LearningPolicy
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executor

// 画面の状態。索引を読む前に打っても落ちず、索引が届いたら今の検索語で出し直すこと。
// Compose の状態（mutableStateOf）は Android なしで読み書きできるので、JVM のテストにする
class SearchControllerTest {
    private val pending = ArrayList<Runnable>()
    private val deferred = Executor { pending += it }
    private val direct = Executor { it.run() }

    @Test
    fun typingBeforeTheIndexArrivesShowsNothingThenResults() {
        val controller = SearchController(analyzer, direct, direct, clock = { NOW })

        controller.onQueryChange("のりかえ")
        assertEquals(SearchContent.Waiting, controller.content)

        controller.onIndexChanged(INDEX)
        assertEquals(listOf("乗換テスト"), controller.labels())
        assertEquals(TRANSIT, controller.topResult)
    }

    @Test
    fun kanjiQueryIsRefinedWithItsReading() {
        val controller = controllerWithIndex(background = deferred)

        // 「のり換え」は表記のままでは名前に当たらない。読みを待つ間は「見つかりません」を出さない
        controller.onQueryChange("のり換え")
        assertEquals(SearchContent.Waiting, controller.content)

        pending.removeAt(0).run()
        assertEquals(listOf("乗換テスト"), controller.labels())
    }

    @Test
    fun staleReadingDoesNotOverwriteNewerQuery() {
        val controller = controllerWithIndex(background = deferred)
        controller.onQueryChange("のり換え")
        controller.onQueryChange("ちず")

        pending.removeAt(0).run()

        assertEquals(listOf("地図テスト", "道テスト"), controller.labels())
    }

    @Test
    fun noMatchIsShownOnlyAfterTheIndexIsLoaded() {
        val controller = controllerWithIndex()
        controller.onQueryChange("ぴざ")
        assertEquals(SearchContent.NoMatch(), controller.content)
        assertNull(controller.topResult)
    }

    @Test
    fun reasonIsShownOnlyWhenTheNameDidNotMatch() {
        val controller = controllerWithIndex()

        controller.onQueryChange("乗換")
        assertNull(controller.rows().single().reason)

        // 読みの一致と、ジャンルの一致
        controller.onQueryChange("のりかえ")
        assertEquals(
            SearchKeyKind.READING,
            controller
                .rows()
                .single()
                .reason
                ?.keyKind,
        )
        controller.onQueryChange("ちず")
        assertEquals(
            listOf("地図テスト" to SearchKeyKind.READING, "道テスト" to SearchKeyKind.GENRE),
            controller.rows().map { it.label to it.reason?.keyKind },
        )
    }

    @Test
    fun emptyQueryShowsRecentAndFrequentApps() {
        val history =
            LaunchHistory.EMPTY
                .recorded(MAPS, at = NOW - 3)
                .recorded(MAPS, at = NOW - 2)
                .recorded(TRANSIT, at = NOW - 1)
        val controller = controllerWithIndex(history = history)

        val suggestions = controller.content as SearchContent.Suggestions
        assertEquals(listOf("乗換テスト", "地図テスト"), suggestions.recent.map { it.label })
        // 空の検索語では、確定キーで何も開かない（意図しない起動を避ける）
        assertNull(controller.topResult)
        assertEquals("地図テスト", controller.labelOf(MAPS))
    }

    @Test
    fun launchCountsReorderResultsWithinTheSameStage() {
        val controller = controllerWithIndex()
        controller.onQueryChange("てすと")
        // 3 つとも読みの語の頭からの一致。記録がなければ表示名の順
        assertEquals(listOf("乗換テスト", "地図テスト", "道テスト"), controller.labels())

        controller.onHistoryChanged(LaunchHistory.EMPTY.recorded(ROAD, at = NOW))
        assertEquals(listOf("道テスト", "乗換テスト", "地図テスト"), controller.labels())
        assertEquals(ROAD, controller.topResult)
    }

    @Test
    fun allAppsListsAppsInNameOrderAndKeepsTheQuery() {
        val controller = controllerWithIndex()
        controller.onQueryChange("ぴざ")

        controller.showAllApps(true)

        val all = controller.content as SearchContent.AllApps
        // 読みの五十音順: ちず → てすと… → のりかえ → みち
        assertEquals(listOf("地図テスト", "乗換テスト", "道テスト"), all.rows.map { it.label })
        assertEquals(listOf(AllAppsSection("た", 0), AllAppsSection("な", 1), AllAppsSection("ま", 2)), all.sections)
        assertEquals("ぴざ", controller.query)
        // すべてのアプリでは確定キーで開かない。開いたら経路と検索語がわかる（学習の救済の材料）
        assertNull(controller.topResult)
        assertEquals(OpenRequest(ROAD, OpenRoute.ALL_APPS, "ぴざ"), controller.openRequest(ROAD))

        // 打つと検索の結果に戻る
        controller.onQueryChange("ちず")
        assertEquals(false, controller.showingAllApps)
        assertEquals(OpenRoute.SEARCH_RESULT, controller.openRequest(MAPS).route)
        controller.onQueryChange("")
        assertEquals(OpenRoute.SUGGESTION, controller.openRequest(MAPS).route)
    }

    @Test
    fun workProfileAppsAreSearchedOnlyAfterSwitching() {
        val controller = SearchController(analyzer, direct, direct, PROFILE_INDEX, clock = { NOW })
        // 仕事用プロファイルを知るまでは、すべてを個人用として引く（取りこぼさない）
        controller.onQueryChange("めーる")
        assertEquals(listOf(MAIL_WORK), controller.rows().map { it.app })

        controller.onWorkProfileChanged(WorkProfileState(setOf(WORK_SERIAL), paused = false))
        assertEquals(ProfileTab.PERSONAL, controller.profileTab)
        // 個人用に一致がなく、仕事用に 1 件
        assertEquals(SearchContent.NoMatch(workMatches = 1), controller.content)

        controller.selectProfile(ProfileTab.WORK)
        assertEquals(listOf(MAIL_WORK), controller.rows().map { it.app })
        // 両方にあるアプリは、選んでいるプロファイルの分だけ出る
        controller.onQueryChange("ちず")
        assertEquals(listOf(MAPS_WORK), controller.rows().map { it.app })
        controller.selectProfile(ProfileTab.PERSONAL)
        assertEquals(listOf(MAPS), controller.rows().map { it.app })
        // 仕事用を見ているときは、個人用の件数を知らせない
        controller.selectProfile(ProfileTab.WORK)
        controller.onQueryChange("のりかえ")
        assertEquals(SearchContent.NoMatch(), controller.content)

        // すべてのアプリも選んでいるプロファイルだけ
        controller.showAllApps(true)
        assertEquals(listOf(MAPS_WORK, MAIL_WORK), (controller.content as SearchContent.AllApps).rows.map { it.app })
        controller.selectProfile(ProfileTab.PERSONAL)
        assertEquals(listOf(MAPS, TRANSIT), (controller.content as SearchContent.AllApps).rows.map { it.app })

        // 仕事用プロファイルが消えたら、切り替えをやめて個人用に戻る
        controller.selectProfile(ProfileTab.WORK)
        controller.onWorkProfileChanged(WorkProfileState.NONE)
        assertEquals(ProfileTab.PERSONAL, controller.profileTab)
        controller.selectProfile(ProfileTab.WORK)
        assertEquals(ProfileTab.PERSONAL, controller.profileTab)
    }

    @Test
    fun switchModeShowsTheSwitchAndThePausedNoticeOnlyOnTheWorkTab() {
        // 既定（切り替えて検索する）。検索の振る舞いは workProfileAppsAreSearchedOnlyAfterSwitching で確かめる
        val controller = SearchController(analyzer, direct, direct, PROFILE_INDEX, clock = { NOW })
        assertEquals(WorkProfileMode.SWITCH, controller.workProfileMode)
        assertEquals(false, controller.showsProfileSwitch)
        controller.onWorkProfileChanged(WorkProfileState(setOf(WORK_SERIAL), paused = true))
        assertEquals(true, controller.showsProfileSwitch)
        assertEquals(false, controller.workPausedVisible)
        controller.selectProfile(ProfileTab.WORK)
        assertEquals(true, controller.workPausedVisible)
    }

    @Test
    fun mixedModeSearchesWorkAppsTogetherWithoutTheSwitch() {
        val history = LaunchHistory.EMPTY.recorded(MAPS, at = NOW - 2).recorded(MAIL_WORK, at = NOW - 1)
        val controller = SearchController(analyzer, direct, direct, PROFILE_INDEX, history, clock = { NOW })
        controller.onWorkProfileChanged(WorkProfileState(setOf(WORK_SERIAL), paused = true))
        controller.selectProfile(ProfileTab.WORK)

        controller.onWorkProfileModeChanged(WorkProfileMode.MIXED)

        // 切り替えをやめ、個人用の側に戻る。その側で仕事用のアプリも引く
        assertEquals(false, controller.showsProfileSwitch)
        assertEquals(ProfileTab.PERSONAL, controller.profileTab)
        // 空の検索語の一覧も混ぜる
        assertEquals(listOf(MAIL_WORK, MAPS), (controller.content as SearchContent.Suggestions).recent.map { it.app })
        controller.onQueryChange("めーる")
        assertEquals(listOf(MAIL_WORK), controller.rows().map { it.app })
        // 両方にあるアプリは両方出る
        controller.onQueryChange("ちず")
        assertEquals(setOf(MAPS, MAPS_WORK), controller.rows().map { it.app }.toSet())
        // 一致がなくても「仕事用に N 件」は出さない（もう混ぜて引いている）
        controller.onQueryChange("ぴざ")
        assertEquals(SearchContent.NoMatch(workMatches = 0), controller.content)
        // 切り替えはできない
        controller.selectProfile(ProfileTab.WORK)
        assertEquals(ProfileTab.PERSONAL, controller.profileTab)
        // すべてのアプリも混ぜる
        controller.showAllApps(true)
        assertEquals(4, (controller.content as SearchContent.AllApps).rows.size)
        // 仕事用のアプリが結果に出うるので、一時停止の知らせは常に出す
        assertEquals(true, controller.workPausedVisible)
    }

    @Test
    fun hiddenModeLeavesWorkAppsOut() {
        val history = LaunchHistory.EMPTY.recorded(MAPS, at = NOW - 2).recorded(MAIL_WORK, at = NOW - 1)
        val controller = SearchController(analyzer, direct, direct, PROFILE_INDEX, history, clock = { NOW })
        controller.onWorkProfileModeChanged(WorkProfileMode.HIDDEN)
        controller.onWorkProfileChanged(WorkProfileState(setOf(WORK_SERIAL), paused = true))

        assertEquals(false, controller.showsProfileSwitch)
        assertEquals(listOf(MAPS), (controller.content as SearchContent.Suggestions).recent.map { it.app })
        // 仕事用にだけあるアプリは出ず、件数も知らせない
        controller.onQueryChange("めーる")
        assertEquals(SearchContent.NoMatch(workMatches = 0), controller.content)
        controller.onQueryChange("ちず")
        assertEquals(listOf(MAPS), controller.rows().map { it.app })
        controller.selectProfile(ProfileTab.WORK)
        assertEquals(ProfileTab.PERSONAL, controller.profileTab)
        controller.showAllApps(true)
        assertEquals(listOf(MAPS, TRANSIT), (controller.content as SearchContent.AllApps).rows.map { it.app })
        assertEquals(false, controller.workPausedVisible)

        // 切り替えに戻すと、また仕事用に件数が出る
        controller.showAllApps(false)
        controller.onWorkProfileModeChanged(WorkProfileMode.SWITCH)
        controller.onQueryChange("めーる")
        assertEquals(SearchContent.NoMatch(workMatches = 1), controller.content)
    }

    @Test
    fun workHintWaitsForTheReadingOfKanjiQueries() {
        val controller = SearchController(analyzer, deferred, direct, PROFILE_INDEX, clock = { NOW })
        controller.onWorkProfileChanged(WorkProfileState(setOf(WORK_SERIAL), paused = true))
        // 漢字を含む検索語は、読みを待つ間は「見つかりません」も件数も出さず、読みを付けてから数える（「道」はどちらにもない）
        controller.onQueryChange("道")
        assertEquals(SearchContent.Waiting, controller.content)
        pending.removeAt(0).run()
        assertEquals(SearchContent.NoMatch(workMatches = 0), controller.content)
        assertEquals(true, controller.workProfile.paused)
    }

    @Test
    fun suggestionsFollowTheSelectedProfile() {
        val history = LaunchHistory.EMPTY.recorded(MAPS, at = NOW - 2).recorded(MAIL_WORK, at = NOW - 1)
        val controller = SearchController(analyzer, direct, direct, PROFILE_INDEX, history, clock = { NOW })
        controller.onWorkProfileChanged(WorkProfileState(setOf(WORK_SERIAL), paused = false))
        assertEquals(listOf(MAPS), (controller.content as SearchContent.Suggestions).recent.map { it.app })
        controller.selectProfile(ProfileTab.WORK)
        assertEquals(listOf(MAIL_WORK), (controller.content as SearchContent.Suggestions).recent.map { it.app })
    }

    @Test
    fun resetReturnsToAFreshlyOpenedScreen() {
        val controller = SearchController(analyzer, direct, direct, PROFILE_INDEX, clock = { NOW })
        controller.onWorkProfileChanged(WorkProfileState(setOf(WORK_SERIAL), paused = false))
        controller.selectProfile(ProfileTab.WORK)
        controller.onQueryChange("めーる")
        controller.showAllApps(true)
        val before = controller.openCount

        // 画面が残っているところへ呼び出し直された: 空の検索語・個人用・検索の結果に戻り、検索窓に入力を向け直す
        controller.reset()

        assertEquals("", controller.query)
        assertEquals(ProfileTab.PERSONAL, controller.profileTab)
        assertEquals(false, controller.showingAllApps)
        assertEquals(before + 1, controller.openCount)
        assertEquals(SearchContent.Suggestions(emptyList(), emptyList()), controller.content)
        // 仕事用プロファイルの状態は呼び出しで変わらないので残す
        assertEquals(setOf(WORK_SERIAL), controller.workProfile.serials)
    }

    @Test
    fun menuOffersLearningItemsOnlyWhereTheyApply() {
        val controller = controllerWithIndex()
        // 検索の結果の行: アプリ情報と「この語では出さない」。学習で出ていないので「学習を消す」はない
        controller.onQueryChange("のりかえ")
        assertEquals(
            listOf(RowAction.APP_INFO, RowAction.SUPPRESS, RowAction.MANAGE_LEARNING),
            controller.menuFor(controller.rows().single()),
        )
        // 空の検索語の一覧とすべてのアプリの一覧には、検索語との組がない
        controller.onHistoryChanged(LaunchHistory.EMPTY.recorded(MAPS, at = NOW))
        controller.onQueryChange("")
        val suggestion = (controller.content as SearchContent.Suggestions).recent.single()
        assertEquals(listOf(RowAction.APP_INFO, RowAction.MANAGE_LEARNING), controller.menuFor(suggestion))
        controller.showAllApps(true)
        assertEquals(
            listOf(RowAction.APP_INFO, RowAction.MANAGE_LEARNING),
            controller.menuFor((controller.content as SearchContent.AllApps).rows.first()),
        )
    }

    @Test
    fun openingFromAllAppsRescuesTheQueryAndTheNextSearchShowsTheApp() {
        val tracker = SessionTracker()
        val controller = controllerWithIndex(tracker = tracker)
        controller.onQueryChange("ぴ")
        controller.onQueryChange("ぴざ")
        assertEquals(SearchContent.NoMatch(), controller.content)
        controller.showAllApps(true)

        val decision = controller.finishSession(controller.openRequest(ROAD))

        assertEquals(LearningDecision(null, listOf(LearnedPair(NormalizedText("ぴざ"), ROAD))), decision)
        // 判定したら、打った文字の列を捨てる
        assertEquals(0, tracker.size)

        // 記録が届くと、次の入力を待たずに今の検索語で出し直す。出た理由は「学習」
        controller.showAllApps(false)
        controller.onLearningChanged(LearnedMemory.EMPTY.recorded(decision, NOW))
        val row = controller.rows().single()
        assertEquals(ROAD to SearchKeyKind.LEARNED, row.app to row.reason?.keyKind)
        assertEquals(
            listOf(RowAction.APP_INFO, RowAction.SUPPRESS, RowAction.FORGET_LEARNING, RowAction.MANAGE_LEARNING),
            controller.menuFor(row),
        )
        assertEquals(LearnedPair(NormalizedText("ぴざ"), ROAD), controller.learnedPairOf(row))
        // 打っている途中でも出る。消すのは当たった学習の語
        controller.onQueryChange("ぴ")
        assertEquals(LearnedPair(NormalizedText("ぴざ"), ROAD), controller.learnedPairOf(controller.rows().single()))
    }

    @Test
    fun openingFromTheResultsIsASelection() {
        val controller = controllerWithIndex()
        controller.onQueryChange("ち")
        controller.onQueryChange("ちず")

        val decision = controller.finishSession(controller.openRequest(MAPS))

        // 地図テストは見えていたので救済にしない。「ち」は同じ試行
        assertEquals(LearningDecision(LearnedPair(NormalizedText("ちず"), MAPS), emptyList()), decision)
    }

    @Test
    fun leavingTheScreenDiscardsTheSession() {
        val tracker = SessionTracker()
        val controller = controllerWithIndex(tracker = tracker)
        controller.onQueryChange("ぴざ")
        controller.endSession()
        assertEquals(0, tracker.size)

        // 戻ってきて別の語で開いても、離れる前の「ぴざ」は救済の候補にならない
        controller.onQueryChange("ちず")
        assertEquals(emptyList<LearnedPair>(), controller.finishSession(controller.openRequest(MAPS)).rescues)

        // 戻ったときに画面に残っている検索語からは、新しいセッションを始める
        controller.onQueryChange("ぴざ")
        controller.endSession()
        controller.startSession()
        controller.showAllApps(true)
        assertEquals(
            listOf(LearnedPair(NormalizedText("ぴざ"), ROAD)),
            controller.finishSession(controller.openRequest(ROAD)).rescues,
        )
    }

    @Test
    fun suppressionHidesTheAppForThatQueryOnly() {
        val controller = controllerWithIndex()
        controller.onQueryChange("ちず")
        val road = controller.rows().first { it.app == ROAD }
        val pair = controller.suppressionOf(road)
        assertEquals(LearnedPair(NormalizedText("ちず"), ROAD), pair)

        controller.onLearningChanged(LearnedMemory.EMPTY.suppressed(pair, NOW))

        assertEquals(listOf("地図テスト"), controller.labels())
        controller.onQueryChange("みち")
        assertEquals(listOf("道テスト"), controller.labels())
    }

    @Test
    fun visibleResultsFollowTheRowsThatFitOnTheScreen() {
        val tracker = SessionTracker(LearningPolicy())
        val controller = controllerWithIndex(tracker = tracker, config = LearningConfig(minVisibleResults = 1))
        // 1 行しか収まらないなら、2 位の道テストは見えていなかった。消して別の語にしてから開くと救済になる
        controller.onRowCapacity(1)
        controller.onQueryChange("ちず")
        controller.onQueryChange("みち")
        assertEquals(
            listOf(LearnedPair(NormalizedText("ちず"), ROAD)),
            controller.finishSession(controller.openRequest(ROAD)).rescues,
        )
        // 2 行収まるなら見えていた
        controller.onRowCapacity(2)
        controller.onQueryChange("ちず")
        controller.onQueryChange("みち")
        assertEquals(emptyList<LearnedPair>(), controller.finishSession(controller.openRequest(ROAD)).rescues)
    }

    private fun controllerWithIndex(
        background: Executor = direct,
        history: LaunchHistory = LaunchHistory.EMPTY,
        tracker: SessionTracker = SessionTracker(),
        config: LearningConfig = LearningConfig.DEFAULT,
    ): SearchController =
        SearchController(analyzer, background, direct, INDEX, history, clock = { NOW }, tracker = tracker, learningConfig = config)

    private fun SearchController.rows(): List<ResultRow> = (content as? SearchContent.Results)?.rows.orEmpty()

    private fun SearchController.labels(): List<String> = rows().map { it.label }

    private companion object {
        const val NOW = 1_000_000_000L
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

        val INDEX =
            SearchIndex.build(
                listOf(
                    AppEntry(TRANSIT, "乗換テスト"),
                    AppEntry(MAPS, "地図テスト"),
                    AppEntry(ROAD, "道テスト", genres = listOf(GenreId("maps"))),
                ),
                SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList()))),
            )

        /** 個人用に乗換と地図、仕事用に地図とメール。 */
        val PROFILE_INDEX =
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
}
