// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.state.Environment
import jp.piu.search.core.state.IndexRules
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.KeyJob
import jp.piu.search.core.state.KeyOutcome
import jp.piu.search.core.state.PackageKey
import jp.piu.search.data.CountingAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 本物のキーの生成（GeneratorKeyFactory）の失敗の単位と、欠けに残すもの（architecture.md の「失敗の分け方」）。
// 生成に失敗した組の扱い（前の値か代わりのキー・再試行しない）は core:state の IndexRulesTest が確かめる
@RunWith(AndroidJUnit4::class)
class GeneratorKeyFactoryTest {
    private val reader = FakeReader()
    private val analyzer = CountingAnalyzer()

    // 辞書の初期化の失敗の単位は、キーを作る入口すべて。入口ごとに読み込みを試し直さない
    @Test
    fun aWarmUpFailureFailsEveryJobOnceWithoutGeneratingAndErrorsAreNotCaught() {
        reader.install(PackageKey(OWN, "io.example.maps"), app("地図テスト"))
        reader.install(PackageKey(WORK, "io.example.transit"), app("乗換テスト"))
        var warmUps = 0
        var failing: Throwable = IllegalStateException("辞書を読めない")
        val factory =
            factory(warmUp = {
                warmUps++
                throw failing
            })
        val outcomes = factory.build(jobs())
        assertEquals(2, outcomes.size)
        assertEquals(1, warmUps)
        assertEquals(0, analyzer.calls)
        for (outcome in outcomes.values) {
            val failure = (outcome as KeyOutcome.Failed).failure
            assertEquals("java.lang.IllegalStateException", failure.cause)
            assertEquals(IssueCategory.SUSPECTED_BUG, failure.category)
            assertNotNull(failure.frames)
            assertEquals("unknown", failure.inputSummary)
        }

        failing = OutOfMemoryError()
        assertThrows(OutOfMemoryError::class.java) { factory.build(jobs()) }
    }

    // 生成の失敗は入口ごと。落ちた入力の種類と形だけを残し、文字列そのものは残さない
    @Test
    fun aGenerationFailureFailsOnlyItsEntryAndNamesTheInputWithoutItsText() {
        analyzer.failOn = "爆"
        reader.install(PackageKey(OWN, "io.example.maps"), app("地図テスト"))
        reader.install(PackageKey(OWN, "io.example.bomb"), app("爆発テスト"))
        val outcomes = factory().build(jobs()).mapKeys { it.key.entry.id.packageName }
        assertTrue(outcomes.getValue("io.example.maps") is KeyOutcome.Built)
        val failure = (outcomes.getValue("io.example.bomb") as KeyOutcome.Failed).failure
        assertEquals(IssueCategory.SUSPECTED_BUG, failure.category)
        assertEquals("label:len5:kanji,kana", failure.inputSummary)
        assertTrue(failure.frames.orEmpty().isNotEmpty())
        assertTrue("爆" !in failure.frames.orEmpty())
    }

    @Test
    fun noJobsNeedNoWarmUp() {
        var warmUps = 0
        assertEquals(emptyMap<KeyJob, KeyOutcome>(), factory(warmUp = { warmUps++ }).build(emptyList()))
        assertEquals(0, warmUps)
    }

    private fun factory(warmUp: () -> Unit = {}) =
        GeneratorKeyFactory(warmUp) { SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList()))) }

    private fun jobs(): List<KeyJob> {
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, reader.observe(emptySet()), ENV)
        return IndexRules.prepare(plan, plan.profileReads.values.map(reader::readMaterials), "2026-11-24").jobs
    }

    private companion object {
        val ENV = Environment("ja-JP", "build-1", 1, 7, "brand-1", 1, 5)
    }
}
