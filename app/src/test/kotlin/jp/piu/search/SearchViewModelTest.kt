// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.SearchFixtures.INDEX
import jp.piu.search.SearchFixtures.MAIL_WORK
import jp.piu.search.SearchFixtures.MAPS
import jp.piu.search.SearchFixtures.MAPS_WORK
import jp.piu.search.SearchFixtures.PROFILE_INDEX
import jp.piu.search.SearchFixtures.ROAD
import jp.piu.search.SearchFixtures.TRANSIT
import jp.piu.search.SearchFixtures.WORK
import jp.piu.search.SearchFixtures.WORK_PAUSED
import jp.piu.search.SearchFixtures.analyzer
import jp.piu.search.SearchHarness.Companion.NOW
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.LearningPolicy
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.search.SearchQuery
import jp.piu.search.core.state.RecordsFailure
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.RecordsRules
import jp.piu.search.core.state.RecordsState
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.Profiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 検索の画面の状態（ui.md の「検索画面」「画面の振る舞い」）。索引を読む前に打っても落ちず、索引が届いたら今の検索語で出し直すこと。
// 画面のスレッドは仮想の時計の Dispatcher、読みと派生の索引の裏の仕事は、止めたい場面だけ ManualDispatcher で溜める
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {
    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            block()
        }

    @Test
    fun typingBeforeTheIndexArrivesShowsNothingThenResults() =
        test {
            val search = SearchHarness(this)
            search.type("のりかえ")
            assertEquals(SearchContent.Waiting, search.content)

            search.indexPort.searchIndex.value = INDEX
            search.settle()
            assertEquals(listOf("乗換テスト"), search.labels())
            assertEquals(TRANSIT, search.state.topResult)
            assertEquals(1, search.timing.ready)
        }

    @Test
    fun kanjiQueryIsRefinedWithItsReading() =
        test {
            val reading = ManualDispatcher()
            val search = SearchHarness(this, INDEX, reading = reading)
            // 「のり換え」は表記のままでは名前に当たらない。読みを待つ間は「見つかりません」を出さない
            search.type("のり換え")
            assertEquals(SearchContent.Waiting, search.content)
            reading.runNext()
            search.settle()
            assertEquals(listOf("乗換テスト"), search.labels())
        }

    @Test
    fun staleReadingDoesNotOverwriteNewerQuery() =
        test {
            val reading = ManualDispatcher()
            val search = SearchHarness(this, INDEX, reading = reading)
            search.type("のり換え")
            search.type("ちず")
            while (reading.queue.isNotEmpty()) reading.runNext()
            search.settle()
            assertEquals(listOf("地図テスト", "道テスト"), search.labels())
        }

    @Test
    fun obsoleteReadingsAreDiscardedBeforeAnalysisAndOnlyTheLatestIsQueued() =
        test {
            val analyzed = ArrayList<String>()
            val reading = ManualDispatcher()
            val search =
                SearchHarness(
                    this,
                    INDEX,
                    analyzer =
                        ReadingAnalyzer {
                            analyzed += it
                            emptyList()
                        },
                    reading = reading,
                )
            search.vm.onQueryChange("古い")
            search.vm.onQueryChange("新しい")
            search.vm.onQueryChange("最新")
            search.settle()
            assertEquals(1, reading.queue.size)
            while (reading.queue.isNotEmpty()) reading.runNext()
            assertEquals(listOf("最新"), analyzed)
        }

    @Test
    fun closingTheSessionInvalidatesAnUnstartedReading() =
        test {
            val analyzed = ArrayList<String>()
            val reading = ManualDispatcher()
            val search =
                SearchHarness(
                    this,
                    INDEX,
                    analyzer =
                        ReadingAnalyzer {
                            analyzed += it
                            emptyList()
                        },
                    reading = reading,
                )
            search.type("未開始")
            search.vm.onScreenStopped(changingConfigurations = false)
            while (reading.queue.isNotEmpty()) reading.runNext()
            search.settle()
            assertEquals(emptyList<String>(), analyzed)
        }

    @Test
    fun requestsArrivingDuringAnalysisKeepOnlyTheLatestAndDiscardTheOldResult() =
        test {
            val analyzed = ArrayList<String>()
            lateinit var search: SearchHarness
            val reading = ManualDispatcher()
            search =
                SearchHarness(
                    this,
                    INDEX,
                    analyzer =
                        ReadingAnalyzer { text ->
                            analyzed += text
                            if (text == "開始") {
                                search.vm.onQueryChange("中間")
                                search.vm.onQueryChange("最新")
                            }
                            emptyList()
                        },
                    reading = reading,
                )
            search.type("開始")
            reading.runNext()
            search.settle()
            while (reading.queue.isNotEmpty()) reading.runNext()
            search.settle()
            assertEquals(listOf("開始", "最新"), analyzed)
            assertEquals("最新", search.state.query)
            assertEquals(SearchContent.NoMatch(), search.content)
            assertEquals(0, reading.queue.size)
        }

    @Test
    fun aLateIndexBuildDoesNotRestartReadingOrRecordInputAfterClosing() =
        test {
            val analyzed = ArrayList<String>()
            val tracker = SessionTracker()
            val derived = ManualDispatcher()
            val reading = ManualDispatcher()
            val search =
                SearchHarness(
                    this,
                    analyzer =
                        ReadingAnalyzer {
                            analyzed += it
                            emptyList()
                        },
                    reading = reading,
                    derived = derived,
                    tracker = tracker,
                )
            search.indexPort.searchIndex.value = INDEX
            search.settle()
            search.type("未開始")
            search.vm.onScreenStopped(changingConfigurations = false)
            while (derived.queue.isNotEmpty() || reading.queue.isNotEmpty()) {
                if (derived.queue.isNotEmpty()) derived.runNext() else reading.runNext()
                search.settle()
            }
            assertEquals(emptyList<String>(), analyzed)
            assertEquals(0, tracker.size)
        }

    @Test
    fun historyChangesAndRepeatedSnapshotsReuseBothProfileIndexes() =
        test {
            val derived = ManualDispatcher()
            val search = SearchHarness(this, PROFILE_INDEX, profiles = WORK, derived = derived)
            while (derived.queue.isNotEmpty()) {
                derived.runNext()
                search.settle()
            }
            // 起動の記録だけ・同じ学習の中身・知らせだけの変化では、派生の索引を作り直さない
            search.launches(LaunchHistory.EMPTY.recorded(MAPS_WORK, NOW))
            search.learn(LearnedMemory.EMPTY)
            search.indexPort.searchIndex.value = PROFILE_INDEX
            search.settle()
            assertEquals(0, derived.queue.size)
            search.vm.selectProfile(ProfileTab.WORK)
            assertEquals(listOf(MAPS_WORK), (search.content as SearchContent.Suggestions).recent.map { it.app })
        }

    @Test
    fun aStaleDerivedIndexIsNotTakenAndTheLatestHistoryIsUsed() =
        test {
            val derived = ManualDispatcher()
            val search = SearchHarness(this, INDEX, derived = derived)
            // 1 回目の派生を走らせたまま、学習が変わる。古い入力の完成は採らない
            search.learn(LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(LearnedPair(NormalizedText("ぴざ"), ROAD))), NOW))
            search.launches(LaunchHistory.EMPTY.recorded(ROAD, NOW))
            while (derived.queue.isNotEmpty()) {
                derived.runNext()
                search.settle()
            }
            search.type("ぴざ")
            // 学習を重ねた索引で引け、起動の記録は反映の時点の最新を使う
            assertEquals(ROAD to SearchKeyKind.LEARNED, search.rows().single().let { it.app to it.reason?.keyKind })
            search.type("")
            assertEquals(listOf(ROAD), (search.content as SearchContent.Suggestions).recent.map { it.app })
        }

    @Test
    fun aRemovedWorkProfileIsHiddenAndCannotBeOpenedBeforeTheDerivedIndexIsRebuilt() =
        test {
            val derived = ManualDispatcher()
            val mixed = Settings(SearchSettings(workProfileMode = WorkProfileMode.MIXED), onboardingDismissed = true)
            val search = SearchHarness(this, PROFILE_INDEX, profiles = WORK, settings = mixed, derived = derived)
            while (derived.queue.isNotEmpty()) {
                derived.runNext()
                search.settle()
            }
            search.type("めーる")
            assertEquals(listOf(MAIL_WORK), search.rows().map { it.app })

            // 仕事用が消えた。派生の索引を作り直す仕事は止めたまま
            search.profiles.value = Profiles(0, emptySet(), workPaused = false)
            search.settle()
            assertEquals(SearchContent.NoMatch(), search.content)
            search.vm.showAllApps(true)
            assertEquals(listOf(MAPS, TRANSIT), (search.content as SearchContent.AllApps).rows.map { it.app })
            search.open(MAIL_WORK)
            assertTrue(search.records.requests.isEmpty())
            assertTrue(search.launcher.launched.isEmpty())

            // 「出さない」にしたときも、完成を待たずに隠す
            search.profiles.value = WORK
            search.settings.value = Settings(SearchSettings(workProfileMode = WorkProfileMode.HIDDEN), onboardingDismissed = true)
            search.settle()
            assertTrue((search.content as SearchContent.AllApps).rows.none { it.app.profileSerial == SearchFixtures.WORK_SERIAL })
        }

    @Test
    fun noMatchIsShownOnlyAfterTheIndexIsLoaded() =
        test {
            val search = SearchHarness(this, INDEX)
            search.type("ぴざ")
            assertEquals(SearchContent.NoMatch(), search.content)
            assertNull(search.state.topResult)
        }

    @Test
    fun reasonIsShownOnlyWhenTheNameDidNotMatch() =
        test {
            val search = SearchHarness(this, INDEX)
            search.type("乗換")
            assertNull(search.rows().single().reason)
            search.type("のりかえ")
            assertEquals(
                SearchKeyKind.READING,
                search
                    .rows()
                    .single()
                    .reason
                    ?.keyKind,
            )
            search.type("ちず")
            assertEquals(
                listOf("地図テスト" to SearchKeyKind.READING, "道テスト" to SearchKeyKind.GENRE),
                search.rows().map { it.label to it.reason?.keyKind },
            )
        }

    @Test
    fun emptyQueryShowsRecentAndFrequentApps() =
        test {
            val history =
                LaunchHistory.EMPTY
                    .recorded(MAPS, at = NOW - 3)
                    .recorded(MAPS, at = NOW - 2)
                    .recorded(TRANSIT, at = NOW - 1)
            val search = SearchHarness(this, INDEX, history = history)
            val suggestions = search.content as SearchContent.Suggestions
            assertEquals(listOf("乗換テスト", "地図テスト"), suggestions.recent.map { it.label })
            // 空の検索語では、確定キーで何も開かない（意図しない起動を避ける）
            assertNull(search.state.topResult)
            assertEquals("地図テスト", search.vm.labelOf(MAPS))
        }

    @Test
    fun launchCountsReorderResultsWithinTheSameStage() =
        test {
            val search = SearchHarness(this, INDEX)
            search.type("てすと")
            assertEquals(listOf("乗換テスト", "地図テスト", "道テスト"), search.labels())
            search.launches(LaunchHistory.EMPTY.recorded(ROAD, at = NOW))
            assertEquals(listOf("道テスト", "乗換テスト", "地図テスト"), search.labels())
            assertEquals(ROAD, search.state.topResult)
        }

    @Test
    fun allAppsListsAppsInNameOrderAndKeepsTheQuery() =
        test {
            val search = SearchHarness(this, INDEX)
            search.type("ぴざ")
            search.vm.showAllApps(true)
            val all = search.content as SearchContent.AllApps
            // 読みの五十音順: ちず → てすと… → のりかえ → みち
            assertEquals(listOf("地図テスト", "乗換テスト", "道テスト"), all.rows.map { it.label })
            assertEquals(listOf(AllAppsSection("た", 0), AllAppsSection("な", 1), AllAppsSection("ま", 2)), all.sections)
            assertEquals("ぴざ", search.state.query)
            assertNull(search.state.topResult)
            assertEquals(OpenRequest(ROAD, OpenRoute.ALL_APPS, "ぴざ"), search.vm.openRequest(ROAD))
            search.type("ちず")
            assertEquals(false, search.state.showingAllApps)
            assertEquals(OpenRoute.SEARCH_RESULT, search.vm.openRequest(MAPS).route)
            search.type("")
            assertEquals(OpenRoute.SUGGESTION, search.vm.openRequest(MAPS).route)
        }

    @Test
    fun workProfileAppsAreSearchedOnlyAfterSwitching() =
        test {
            val search = SearchHarness(this, PROFILE_INDEX)
            // ProfileWatcher が仕事用と知らない番号のアプリは出さない（消えたプロファイルの組は次の同期まで索引に残るため）
            search.type("めーる")
            assertEquals(SearchContent.NoMatch(), search.content)

            search.profiles.value = WORK
            search.settle()
            search.type("めーる")
            assertEquals(ProfileTab.PERSONAL, search.state.profileSwitch?.selected)
            // 個人用に一致がなく、仕事用に 1 件
            assertEquals(SearchContent.NoMatch(workMatches = 1), search.content)

            search.vm.selectProfile(ProfileTab.WORK)
            assertEquals(listOf(MAIL_WORK), search.rows().map { it.app })
            search.type("ちず")
            assertEquals(listOf(MAPS_WORK), search.rows().map { it.app })
            search.vm.selectProfile(ProfileTab.PERSONAL)
            assertEquals(listOf(MAPS), search.rows().map { it.app })
            search.vm.selectProfile(ProfileTab.WORK)
            search.type("のりかえ")
            assertEquals(SearchContent.NoMatch(), search.content)

            search.vm.showAllApps(true)
            assertEquals(listOf(MAPS_WORK, MAIL_WORK), (search.content as SearchContent.AllApps).rows.map { it.app })
            search.vm.selectProfile(ProfileTab.PERSONAL)
            assertEquals(listOf(MAPS, TRANSIT), (search.content as SearchContent.AllApps).rows.map { it.app })

            // 仕事用プロファイルが消えたら、切り替えをやめて個人用に戻る
            search.vm.selectProfile(ProfileTab.WORK)
            search.profiles.value = Profiles(0, emptySet(), workPaused = false)
            search.settle()
            assertNull(search.state.profileSwitch)
            search.vm.selectProfile(ProfileTab.WORK)
            assertNull(search.state.profileSwitch)
            assertEquals(listOf(MAPS, TRANSIT), (search.content as SearchContent.AllApps).rows.map { it.app })
        }

    @Test
    fun switchModeShowsTheSwitchAndThePausedNoticeOnlyOnTheWorkTab() =
        test {
            val search = SearchHarness(this, PROFILE_INDEX)
            assertNull(search.state.profileSwitch)
            search.profiles.value = WORK_PAUSED
            search.settle()
            assertEquals(ProfileSwitch(ProfileTab.PERSONAL, workPaused = true), search.state.profileSwitch)
            assertEquals(false, search.state.workPausedNotice)
            search.vm.selectProfile(ProfileTab.WORK)
            assertEquals(true, search.state.workPausedNotice)
        }

    @Test
    fun mixedModeSearchesWorkAppsTogetherWithoutTheSwitch() =
        test {
            val history = LaunchHistory.EMPTY.recorded(MAPS, at = NOW - 2).recorded(MAIL_WORK, at = NOW - 1)
            val search = SearchHarness(this, PROFILE_INDEX, profiles = WORK_PAUSED, history = history)
            search.vm.selectProfile(ProfileTab.WORK)
            search.settings.value = Settings(SearchSettings(workProfileMode = WorkProfileMode.MIXED), onboardingDismissed = true)
            search.settle()

            assertNull(search.state.profileSwitch)
            assertEquals(listOf(MAIL_WORK, MAPS), (search.content as SearchContent.Suggestions).recent.map { it.app })
            search.type("めーる")
            assertEquals(listOf(MAIL_WORK), search.rows().map { it.app })
            search.type("ちず")
            assertEquals(setOf(MAPS, MAPS_WORK), search.rows().map { it.app }.toSet())
            // 混ぜて出すときだけ、仕事用の行に印を付ける（読み上げで同名のアプリを区別するため）
            assertEquals(setOf(MAPS to false, MAPS_WORK to true), search.rows().map { it.app to it.isWork }.toSet())
            search.type("ぴざ")
            assertEquals(SearchContent.NoMatch(workMatches = 0), search.content)
            search.vm.selectProfile(ProfileTab.WORK)
            assertNull(search.state.profileSwitch)
            search.vm.showAllApps(true)
            assertEquals(4, (search.content as SearchContent.AllApps).rows.size)
            // 仕事用のアプリが結果に出うるので、一時停止の知らせは常に出す
            assertEquals(true, search.state.workPausedNotice)
        }

    @Test
    fun hiddenModeLeavesWorkAppsOut() =
        test {
            val history = LaunchHistory.EMPTY.recorded(MAPS, at = NOW - 2).recorded(MAIL_WORK, at = NOW - 1)
            val hidden = Settings(SearchSettings(workProfileMode = WorkProfileMode.HIDDEN), onboardingDismissed = true)
            val search = SearchHarness(this, PROFILE_INDEX, profiles = WORK_PAUSED, settings = hidden, history = history)

            assertNull(search.state.profileSwitch)
            assertEquals(listOf(MAPS), (search.content as SearchContent.Suggestions).recent.map { it.app })
            search.type("めーる")
            assertEquals(SearchContent.NoMatch(workMatches = 0), search.content)
            search.type("ちず")
            assertEquals(listOf(MAPS), search.rows().map { it.app })
            search.vm.showAllApps(true)
            assertEquals(listOf(MAPS, TRANSIT), (search.content as SearchContent.AllApps).rows.map { it.app })
            assertEquals(false, search.state.workPausedNotice)

            // 切り替えに戻すと、また仕事用に件数が出る
            search.vm.showAllApps(false)
            search.settings.value = Settings(onboardingDismissed = true)
            search.settle()
            search.type("めーる")
            assertEquals(SearchContent.NoMatch(workMatches = 1), search.content)
        }

    @Test
    fun workHintWaitsForTheReadingOfKanjiQueries() =
        test {
            val reading = ManualDispatcher()
            val search = SearchHarness(this, PROFILE_INDEX, profiles = WORK_PAUSED, reading = reading)
            search.type("道")
            assertEquals(SearchContent.Waiting, search.content)
            reading.runNext()
            search.settle()
            assertEquals(SearchContent.NoMatch(workMatches = 0), search.content)
        }

    @Test
    fun suggestionsFollowTheSelectedProfile() =
        test {
            val history = LaunchHistory.EMPTY.recorded(MAPS, at = NOW - 2).recorded(MAIL_WORK, at = NOW - 1)
            val search = SearchHarness(this, PROFILE_INDEX, profiles = WORK, history = history)
            assertEquals(listOf(MAPS), (search.content as SearchContent.Suggestions).recent.map { it.app })
            search.vm.selectProfile(ProfileTab.WORK)
            assertEquals(listOf(MAIL_WORK), (search.content as SearchContent.Suggestions).recent.map { it.app })
        }

    @Test
    fun resetReturnsToAFreshlyOpenedScreen() =
        test {
            val search = SearchHarness(this, PROFILE_INDEX, profiles = WORK)
            search.vm.selectProfile(ProfileTab.WORK)
            search.type("めーる")
            search.vm.showAllApps(true)
            val before = search.state.focusKey

            search.vm.reset()

            assertEquals("", search.state.query)
            assertEquals(ProfileTab.PERSONAL, search.state.profileSwitch?.selected)
            assertEquals(false, search.state.showingAllApps)
            assertEquals(before + 1, search.state.focusKey)
            assertEquals(SearchContent.Suggestions(emptyList(), emptyList()), search.content)
        }

    @Test
    fun menuOffersLearningItemsOnlyWhereTheyApply() =
        test {
            val search = SearchHarness(this, INDEX)
            search.type("のりかえ")
            assertEquals(
                listOf(RowAction.APP_INFO, RowAction.SUPPRESS, RowAction.MANAGE_LEARNING),
                search.vm.menuFor(search.rows().single()),
            )
            search.launches(LaunchHistory.EMPTY.recorded(MAPS, at = NOW))
            search.type("")
            val suggestion = (search.content as SearchContent.Suggestions).recent.single()
            assertEquals(listOf(RowAction.APP_INFO, RowAction.MANAGE_LEARNING), search.vm.menuFor(suggestion))
            search.vm.showAllApps(true)
            assertEquals(
                listOf(RowAction.APP_INFO, RowAction.MANAGE_LEARNING),
                search.vm.menuFor((search.content as SearchContent.AllApps).rows.first()),
            )
        }

    @Test
    fun openingFromAllAppsRescuesTheQueryAndTheNextSearchShowsTheApp() =
        test {
            val tracker = SessionTracker()
            val search = SearchHarness(this, INDEX, tracker = tracker)
            search.type("ぴ")
            search.type("ぴざ")
            assertEquals(SearchContent.NoMatch(), search.content)
            search.vm.showAllApps(true)

            search.open(ROAD)

            val rescue = LearnedPair(NormalizedText("ぴざ"), ROAD)
            assertEquals(LearningDecision(null, listOf(rescue)), search.lastDecision)
            // 学習と起動の記録を 1 つの頼みにする。救済の知らせの表示名は、選んだ時点の行の名前
            val open = search.records.opens.single()
            assertEquals(ROAD, open.launched)
            assertEquals(mapOf(rescue to "道テスト"), open.labels)
            // 判定したら、打った文字の列を捨てる
            assertEquals(0, tracker.size)

            // 記録が届くと、次の入力を待たずに今の検索語で出し直す。出た理由は「学習」
            search.vm.reset()
            search.type("ぴざ")
            search.learn(LearnedMemory.EMPTY.recorded(search.lastDecision, NOW))
            val row = search.rows().single()
            assertEquals(ROAD to SearchKeyKind.LEARNED, row.app to row.reason?.keyKind)
            assertEquals(
                listOf(RowAction.APP_INFO, RowAction.SUPPRESS, RowAction.FORGET_LEARNING, RowAction.MANAGE_LEARNING),
                search.vm.menuFor(row),
            )
            assertEquals(rescue, search.vm.learnedPairOf(row))
            search.type("ぴ")
            assertEquals(rescue, search.vm.learnedPairOf(search.rows().single()))
        }

    @Test
    fun openingFromTheResultsIsASelection() =
        test {
            val search = SearchHarness(this, INDEX)
            search.type("ち")
            search.type("ちず")
            search.open(MAPS)
            // 地図テストは見えていたので救済にしない。「ち」は同じ試行
            assertEquals(LearningDecision(LearnedPair(NormalizedText("ちず"), MAPS), emptyList()), search.lastDecision)
        }

    @Test
    fun leavingTheScreenDiscardsTheSession() =
        test {
            val tracker = SessionTracker()
            val search = SearchHarness(this, INDEX, tracker = tracker)
            search.vm.onScreenStarted()
            search.type("ぴざ")
            search.vm.onScreenStopped(changingConfigurations = false)
            assertEquals(0, tracker.size)

            // 別の語を打って開いても、離れる前の「ぴざ」は救済の候補にならない
            search.type("ちず")
            search.open(MAPS)
            assertEquals(emptyList<LearnedPair>(), search.lastDecision.rescues)

            // 戻ったときに画面に残っている検索語からは、新しいセッションを始める
            search.vm.reset()
            search.type("ぴざ")
            search.vm.onScreenStopped(changingConfigurations = false)
            search.vm.onScreenStarted()
            search.settle()
            search.vm.showAllApps(true)
            search.open(ROAD)
            assertEquals(listOf(LearnedPair(NormalizedText("ぴざ"), ROAD)), search.lastDecision.rescues)
        }

    @Test
    fun suppressionHidesTheAppForThatQueryOnly() =
        test {
            val search = SearchHarness(this, INDEX)
            search.type("ちず")
            val road = search.rows().first { it.app == ROAD }
            val pair = search.vm.suppressionOf(road)
            assertEquals(LearnedPair(NormalizedText("ちず"), ROAD), pair)
            search.vm.suppress(road)
            search.settle()
            assertEquals(RecordsRequest.Suppress(pair, NOW), search.records.requests.single())

            search.learn(LearnedMemory.EMPTY.suppressed(pair, NOW))
            assertEquals(listOf("地図テスト"), search.labels())
            search.type("みち")
            assertEquals(listOf("道テスト"), search.labels())
        }

    @Test
    fun visibleResultsFollowTheRowsThatFitOnTheScreen() =
        test {
            val tracker = SessionTracker(LearningPolicy())
            val search = SearchHarness(this, INDEX, tracker = tracker, config = LearningConfig(minVisibleResults = 1))
            search.vm.onRowCapacity(1)
            search.type("ちず")
            search.type("みち")
            search.open(ROAD)
            assertEquals(listOf(LearnedPair(NormalizedText("ちず"), ROAD)), search.lastDecision.rescues)

            search.vm.reset()
            search.vm.onRowCapacity(2)
            search.type("ちず")
            search.type("みち")
            search.open(ROAD)
            assertEquals(emptyList<LearnedPair>(), search.lastDecision.rescues)
        }

    @Test
    fun openingALearnedPrefixRefreshesTheOriginalRecordWithoutAddingAnotherUse() {
        val learned = LearnedPair(NormalizedText("おかね"), ROAD)
        for (query in listOf("おか", "oka", "おかn", "おかね")) {
            test {
                val memory = LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(learned)), NOW - 1)
                val search = SearchHarness(this, INDEX, learning = memory)
                search.type(query)
                assertEquals(learned, search.vm.learnedPairOf(search.rows().single()))
                search.open(ROAD)
                // 送った判定は、当たった元の学習の組を持つ（前方一致・ローマ字でも元の記録の時刻が進み、忘れられない）
                assertEquals(learned, search.lastDecision.used)
                val after = memory.recorded(search.lastDecision, NOW)
                val original = after.records.getValue(learned)
                assertEquals(NOW, original.lastUsedAt)
                assertEquals(if (query == "おかね") 2 else 1, original.count)
                if (query != "おかね") assertEquals(1, after.records.getValue(LearnedPair(SearchQuery.of(query).strict, ROAD)).count)
                assertEquals(after.records.keys, after.forgotten(NOW + LearningConfig.DEFAULT.forgetAfterMillis - 1).records.keys)
            }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun openingFromAllAppsOrWithAnotherMatchingKeyDoesNotRefreshTheLearnedPair() =
        test {
            val learned = LearnedPair(NormalizedText("みちのり"), ROAD)
            val memory = LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(learned)), NOW - 1)
            val search = SearchHarness(this, INDEX, learning = memory)
            search.type("みち")
            search.open(ROAD)
            assertNull(search.lastDecision.used)
            search.vm.reset()
            search.vm.showAllApps(true)
            search.open(ROAD)
            assertNull(search.lastDecision.used)
        }

    // 以下、アプリを開く流れ（ui.md の「アプリを開くとき」）

    @Test
    fun savesBeforeLaunchingAndOnlyThenShowsTheRescueToastAndClosesTheOriginalScreen() =
        test {
            val reply = CompletableDeferred<RecordsResult>()
            val search = SearchHarness(this, INDEX)
            search.records.reply = { reply }
            val finished = ArrayList<ScreenToken>()
            backgroundScope.launchCollect(search.vm.finishRequests, finished)
            search.type("ぴざ")
            search.vm.showAllApps(true)
            search.open(ROAD)
            // 保存を待つ間は開かず、知らせも出さず、閉じない
            assertTrue(search.launcher.launched.isEmpty())
            assertTrue(search.messages.shown.isEmpty())
            assertTrue(finished.isEmpty())
            // 連打しても、頼みと起動は 1 回
            search.open(ROAD)
            assertEquals(1, search.records.requests.size)

            reply.complete(search.records.commit(search.records.opens.single()))
            search.settle()
            assertEquals(listOf(ROAD), search.launcher.launched)
            assertEquals(listOf(UserMessage.RescueLearned("ぴざ", "道テスト")), search.messages.shown)
            assertEquals(listOf(search.screen), finished)
            assertFalse(search.openRecordFailed.value)
            assertEquals(listOf(true), search.timing.launches)
            // 今回の救済の知らせは、次の検索まで出さない
            assertTrue(search.state.notices.isEmpty())
            search.vm.reset()
            assertEquals(1, search.state.notices.size)
        }

    @Test
    fun aFailedSaveStillLaunchesWithoutALearnedToastAndIsShownNextTime() =
        test {
            val search = SearchHarness(this, INDEX)
            search.records.reply = { CompletableDeferred(RecordsResult.Failure(RecordsFailure.WRITE_FAILED)) }
            search.vm.showAllApps(true)
            search.open(ROAD)
            assertEquals(listOf(ROAD), search.launcher.launched)
            assertTrue(search.messages.shown.isEmpty())
            assertTrue(search.openRecordFailed.value)
            assertTrue(search.state.openRecordFailed)
            // 次の保存が成功したら下ろす
            search.records.reply = { CompletableDeferred(RecordsResult.Success()) }
            search.vm.reset()
            search.vm.showAllApps(true)
            search.open(ROAD)
            assertFalse(search.state.openRecordFailed)
        }

    @Test
    fun aPausedWorkRequestIsNeitherRecordedNorCountedAndKeepsTheEarlierFailure() =
        test {
            val search = SearchHarness(this, PROFILE_INDEX, profiles = WORK_PAUSED)
            search.openRecordFailed.value = true
            search.launcher.paused = { it.profileSerial == SearchFixtures.WORK_SERIAL }
            search.vm.selectProfile(ProfileTab.WORK)
            search.vm.showAllApps(true)
            search.open(MAIL_WORK)
            assertTrue(search.records.requests.isEmpty())
            assertEquals(listOf(MAIL_WORK), search.launcher.launched)
            assertTrue(search.openRecordFailed.value)
            assertEquals(listOf(false), search.timing.launches)
            // ほかのプロファイルの一時停止で、個人用の選択を除かない
            search.vm.reset()
            search.vm.showAllApps(true)
            search.open(MAPS)
            assertEquals(
                MAPS,
                search.records.opens
                    .single()
                    .launched,
            )
        }

    @Test
    fun aRefusedLaunchKeepsTheSavedChoiceClearsTheMarkAndRestartsOnlyAVisibleSession() =
        test {
            val tracker = SessionTracker()
            val search = SearchHarness(this, INDEX, tracker = tracker)
            search.launcher.accepts = false
            search.vm.onScreenStarted()
            search.settle()
            search.vm.showAllApps(true)
            search.open(ROAD)
            assertEquals(UserMessage.LaunchFailed("道テスト"), search.messages.shown.last())
            assertEquals(1, search.indexPort.syncs)
            assertEquals(1, search.records.opens.size)
            // 表示中なら新しいセッションで再試行を受け付ける
            search.open(ROAD)
            assertEquals(2, search.records.opens.size)

            // 非表示の間に失敗したら、入力の記録は復帰まで再開しない
            val reply = CompletableDeferred<RecordsResult>()
            search.records.reply = { reply }
            search.open(ROAD)
            search.vm.onScreenStopped(changingConfigurations = false)
            reply.complete(RecordsResult.Success())
            search.settle()
            assertEquals(0, tracker.size)
            // 復帰すると、新しいセッションを始める（すべてのアプリを出していた）
            search.vm.onScreenStarted()
            search.settle()
            assertEquals(1, tracker.size)
        }

    @Test
    fun aCompletionAfterTheScreenWasCalledAgainDoesNotCloseTheNewSearch() =
        test {
            val reply = CompletableDeferred<RecordsResult>()
            val search = SearchHarness(this, INDEX)
            search.records.reply = { reply }
            val finished = ArrayList<ScreenToken>()
            backgroundScope.launchCollect(search.vm.finishRequests, finished)
            search.type("ぴざ")
            search.vm.showAllApps(true)
            search.open(ROAD)
            // 保存中に呼び直された。判定は選んだ時点のまま
            search.vm.reset()
            search.type("ちず")
            reply.complete(RecordsResult.Success())
            search.settle()
            assertEquals(listOf(ROAD), search.launcher.launched)
            assertTrue(finished.isEmpty())
            assertEquals(
                listOf(LearnedPair(NormalizedText("ぴざ"), ROAD)),
                search.records.opens
                    .single()
                    .decision.rescues,
            )
            assertEquals("ちず", search.state.query)
            // 新しい検索から別のアプリを開ける
            search.records.reply = { CompletableDeferred(RecordsResult.Success()) }
            search.open(MAPS)
            assertEquals(listOf(search.screen), finished)
        }

    @Test
    fun aCompletionAfterRecreationIsSentToTheOriginalScreenAndAnotherChoiceIsAccepted() =
        test {
            val reply = CompletableDeferred<RecordsResult>()
            val search = SearchHarness(this, INDEX)
            search.records.reply = { reply }
            val finished = ArrayList<ScreenToken>()
            backgroundScope.launchCollect(search.vm.finishRequests, finished)
            search.vm.showAllApps(true)
            search.open(ROAD)
            // 回転で作り直した画面は別の札。古い完了は元の画面の札にだけ届く
            val recreated = ScreenToken()
            reply.complete(RecordsResult.Success())
            search.settle()
            assertEquals(listOf(search.screen), finished)
            search.records.reply = { CompletableDeferred(RecordsResult.Success()) }
            search.vm.onScreenStarted()
            search.settle()
            search.vm.open(MAPS, recreated)
            search.settle()
            assertEquals(listOf(search.screen, recreated), finished)
        }

    // 以下、救済の知らせと記録の失敗の知らせ

    @Test
    fun noticesAreShownUndoneAndConsumedWithoutLosingTheUndoWhileSaving() =
        test {
            val search = SearchHarness(this, INDEX)
            val decision = LearningDecision(null, listOf(LearnedPair(NormalizedText("ぴざ"), ROAD)))
            search.records.commit(RecordsRequest.Open(decision, ROAD, NOW, mapOf(decision.rescues.single() to "道テスト")))
            search.settle()
            val notice = search.state.notices.single()
            assertEquals("道テスト", notice.label)

            // 取り消しの保存中に表示が終わっても、消費しない（材料を保つ）
            val reply = CompletableDeferred<RecordsResult>()
            search.records.reply = { reply }
            search.vm.undo(notice)
            search.vm.noticeDone(notice)
            assertTrue(search.state.notices.isEmpty())
            assertTrue(search.records.consumed.isEmpty())
            // 失敗したら知らせを残し、もう一度取り消せる
            reply.complete(RecordsResult.Failure(RecordsFailure.WRITE_FAILED))
            search.settle()
            assertEquals(UserMessage.RecordsFailed(RecordsFailure.WRITE_FAILED), search.messages.shown.last())
            assertEquals(listOf(notice), search.state.notices)

            search.vm.noticeDone(notice)
            assertEquals(listOf(notice.id), search.records.consumed)
        }

    @Test
    fun noticesOnScreenAreConsumedWhenTheScreenCloses() =
        test {
            val search = SearchHarness(this, INDEX)
            val decision = LearningDecision(null, listOf(LearnedPair(NormalizedText("ぴざ"), ROAD)))
            search.records.commit(RecordsRequest.Open(decision, ROAD, NOW))
            search.settle()
            val notice = search.state.notices.single()
            // ViewModel を閉じる（Activity が終わった）。出しかけの知らせを消費する
            search.clear()
            assertEquals(listOf(notice.id), search.records.consumed)
        }

    @Test
    fun returningToTheForegroundRefreshesRecordsForgetsOldLearningAndReportsALoadFailure() =
        test {
            val search = SearchHarness(this, INDEX)
            search.vm.onScreenStarted()
            search.settle()
            assertEquals(listOf(true), search.indexPort.foreground)
            assertTrue(search.searchForeground.value)
            assertEquals(1, search.records.refreshes)
            assertEquals(1, search.profileRefreshes)
            assertEquals(RecordsRequest.Forget(NOW), search.records.requests.single())

            search.records.state.value = RecordsRules.loadFailed(RecordsState.INITIAL, "java.io.IOException")
            search.vm.onScreenStopped(changingConfigurations = true)
            search.vm.onScreenStarted()
            search.settle()
            assertEquals(listOf(true, false, true), search.indexPort.foreground)
            assertEquals(UserMessage.RecordsFailed(RecordsFailure.NOT_LOADED), search.messages.shown.single())
            // 読めない間も、学習なしで検索は続ける
            search.type("ちず")
            assertEquals(listOf("地図テスト", "道テスト"), search.labels())
        }

    @Test
    fun theRebuiltRecordsNoticeIsShownUntilTypingAndAcknowledgedOnce() =
        test {
            val search = SearchHarness(this, INDEX)
            search.records.rebuiltAfterCorruption.value = true
            search.settle()
            assertTrue(search.state.recordsRebuilt)
            search.vm.rebuiltNoticeShown()
            search.records.rebuiltAfterCorruption.value = false
            search.settle()
            // 書き手の印を下ろしても、この画面では打ち始めるまで出す
            assertTrue(search.state.recordsRebuilt)
            assertEquals(1, search.records.acknowledged)
            search.type("ち")
            assertFalse(search.state.recordsRebuilt)
        }

    @Test
    fun anIndexStatusOnlyChangeUpdatesTheNoticeWithoutRebuildingTheDerivedIndex() =
        test {
            val derived = ManualDispatcher()
            val search = SearchHarness(this, INDEX, derived = derived)
            while (derived.queue.isNotEmpty()) {
                derived.runNext()
                search.settle()
            }
            val failed = IndexStatusFixtures.writer(syncFailure = "java.lang.IllegalStateException")
            search.indexPort.status.value = failed
            search.settle()
            assertEquals(IndexNotice.SYNC_FAILED, search.state.indexNotice)
            // 同じ写しのまま回復した
            search.indexPort.status.value = IndexStatusFixtures.writer()
            search.settle()
            assertNull(search.state.indexNotice)
            assertEquals(0, derived.queue.size)
        }

    @Test
    fun onlyTheQueryIsRestoredFromTheSavedState() =
        test {
            val saved = SavedStateHandle()
            val first = SearchHarness(this, INDEX, savedState = saved)
            first.type("おかね")
            first.type("paypay")
            val tracker = SessionTracker()
            val second =
                SearchHarness(this, INDEX, savedState = SavedStateHandle(mapOf("query" to saved.get<String>("query"))), tracker = tracker)
            assertEquals("paypay", second.state.query)
            second.vm.showAllApps(true)
            second.open(ROAD)
            assertTrue(second.lastDecision.rescues.none { it.query.value == "おかね" })
        }
}

private fun <T> CoroutineScope.launchCollect(
    flow: Flow<T>,
    into: MutableList<T>,
) {
    launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { into += it } }
}
