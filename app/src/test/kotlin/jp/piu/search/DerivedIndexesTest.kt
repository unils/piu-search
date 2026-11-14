// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.system.measureNanoTime

class DerivedIndexesTest {
    private val direct = Executor(Runnable::run)
    private val pending = ArrayList<Runnable>()
    private val background = Executor { pending += it }
    private val app = AppId("io.example.paypay", "Main", 0)
    private val workApp = app.copy(profileSerial = 10)
    private val keys = listOf(SearchKey(NormalizedText("paypay"), SearchKeyKind.NAME))
    private val index = SearchIndex(listOf(IndexedApp(app, "PayPay", keys), IndexedApp(workApp, "PayPay", keys)))

    @Test
    fun onlyChangedMaterialsScheduleBackgroundWorkAndHistoryUsesTheSameIndexes() {
        val controller = SearchController(ReadingAnalyzer { emptyList() }, direct, direct, index, indexBackground = background)
        controller.onWorkProfileChanged(WorkProfileState(setOf(10), paused = false))
        controller.onIndexChanged(index)
        controller.onLearningChanged(LearnedMemory.EMPTY)
        assertEquals(1, pending.size)
        assertEquals(0, profileIndex(controller).size)
        pending.removeAt(0).run()
        assertEquals(1, profileIndex(controller).size)
        val personal = profileIndex(controller)
        val work = profileIndex(controller, "workIndex")
        val learned = profileIndex(controller, "learnedIndex")

        controller.onHistoryChanged(LaunchHistory.EMPTY.recorded(app, 1))
        controller.onWorkProfileChanged(WorkProfileState(setOf(10), paused = true))
        controller.onQueryChange("paypay")
        controller.selectProfile(ProfileTab.WORK)
        assertEquals(0, pending.size)
        assertSame(personal, profileIndex(controller))
        assertSame(work, profileIndex(controller, "workIndex"))
        assertEquals(workApp, controller.topResult)

        controller.onWorkProfileModeChanged(WorkProfileMode.MIXED)
        assertEquals(1, pending.size)
        pending.removeAt(0).run()
        assertSame(learned, profileIndex(controller, "learnedIndex"))
        assertEquals(2, profileIndex(controller).size)
    }

    @Test
    fun staleBuildCannotReplaceNewerMaterialsAndApplicationUsesTheLatestHistory() {
        val ui = ArrayList<Runnable>()
        val controller =
            SearchController(ReadingAnalyzer { emptyList() }, direct, Executor { ui += it }, index, indexBackground = background)
        pending.removeAt(0).run()
        val pair = LearnedPair(NormalizedText("おかね"), workApp)
        controller.onLearningChanged(LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(pair)), 1))
        // 古い生成結果を反映する前に、新しい材料を受ける順序を作る
        ui.removeAt(1).run()
        ui.removeAt(0).run()
        assertEquals(0, profileIndex(controller).size)
        controller.onHistoryChanged(LaunchHistory.EMPTY.recorded(workApp, 2))
        ui.removeAt(0).run()
        controller.onQueryChange("おかね")
        pending.removeAt(0).run()
        ui.removeAt(0).run()
        assertEquals(workApp, controller.topResult)
        controller.onQueryChange("")
        assertEquals(workApp, (controller.content as SearchContent.Suggestions).recent.single().app)
    }

    @Test
    fun measuresScreenThreadSubmissionSeparatelyFromBuildingFiveHundredEntries() {
        val apps =
            (0 until 500).map { i ->
                IndexedApp(
                    AppId("io.example.app$i", "Main", i % 2 * 10L),
                    "アプリ$i",
                    listOf(SearchKey(NormalizedText("あぷり$i"), SearchKeyKind.NAME)),
                )
            }
        val source = SearchIndex(apps)
        val pairs = apps.map { LearnedPair(NormalizedText("あ".repeat(64)), it.id) }
        val memory = LearnedMemory.EMPTY.recorded(LearningDecision(null, pairs), 1)
        val controller = SearchController(ReadingAnalyzer { emptyList() }, direct, direct, indexBackground = background)
        val screenNanos =
            measureNanoTime {
                controller.onIndexChanged(source)
                controller.onLearningChanged(memory)
                controller.onWorkProfileChanged(WorkProfileState(setOf(10), paused = false))
            }
        assertEquals(1, pending.size)
        assertEquals(0, profileIndex(controller).size)
        val buildNanos = measureNanoTime { pending.removeAt(0).run() }
        assertEquals(250, profileIndex(controller).size)
        val personal = profileIndex(controller)
        val historyNanos = measureNanoTime { controller.onHistoryChanged(LaunchHistory.EMPTY.recorded(apps.first().id, 2)) }
        assertTrue(pending.isEmpty())
        assertSame(personal, profileIndex(controller))
        println(
            "R5 500件・学習500件64字: 画面の要求=${screenNanos / 1_000_000.0}ms, 裏の生成と反映=${buildNanos / 1_000_000.0}ms, 起動記録の反映=${historyNanos / 1_000_000.0}ms",
        )
    }

    private fun profileIndex(
        controller: SearchController,
        name: String = "personalIndex",
    ): SearchIndex =
        SearchController::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(controller) as SearchIndex
}
