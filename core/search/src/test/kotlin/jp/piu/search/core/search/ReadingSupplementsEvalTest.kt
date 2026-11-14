// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.ReadingToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ReadingSupplementsEvalTest {
    @Test
    fun `かなだけの表示名への読みの補いを評価する`() {
        val names = listOf("つうちょう", "ゆうちょつうちょう", "いちば", "つうちょういちば", "かよいちょうかんり", "しじょうちょうさ", "つうちょうxyz")
        val apps = names.mapIndexed { i, name -> EvalApp(name, "", "org.example.supplement$i", emptyList()) }
        val table = EvalSet.parse(EvalSet.resource("reading-supplements.tsv"))
        assertEquals(emptyList<String>(), EvalSet.checkQueries(table, names.toSet()))
        val report = EvalReport(EvalRunner(apps, TestAnalyzers.generator, TestAnalyzers.kuromoji).run(EvalSet.queries(table)))
        val dir = File(System.getProperty("eval.reportDir") ?: "build/reports/eval").apply { mkdirs() }
        File(dir, "reading-supplements.md").writeText(report.markdown())
        println("読みの補いの評価: ${EvalReport.percent(report.scoredTop3Rate)}")
        assertEquals(1.0, report.scoredTop3Rate, report.markdown())
    }

    @Test
    fun `検索語の表記と辞書の読みと補いの読みをすべて残す`() {
        val entries =
            listOf("通帳", "かよいちょう", "つうちょう").mapIndexed { i, label ->
                AppEntry(AppId("org.example.reading$i", "main", 0), label)
            }
        val engine = SearchEngine(SearchIndex.build(entries, TestAnalyzers.generator))
        val results = engine.search(SearchQuery.of("通帳").withReading(TestAnalyzers.kuromoji))
        assertEquals(entries.map { it.label }.toSet(), results.map { it.label }.toSet())
        val spelling = results.single { it.label == "通帳" }.reason
        assertEquals(SearchKeyKind.NAME, spelling.keyKind)
        assertEquals(MatchStage.EXACT, spelling.stage)
        assertFalse(spelling.viaQueryReading)
        for (result in results.filterNot { it.label == "通帳" }) {
            assertEquals(MatchStage.EXACT, result.reason.stage)
            assertTrue(result.reason.viaQueryReading)
        }
    }

    @Test
    fun `検索語の読みは辞書の候補を先に残し、補いの組み合わせを上限まで作る`() {
        val query = SearchQuery.of("通帳 市場").withReading(TestAnalyzers.kuromoji)
        assertEquals("かよいちょうしじょう", query.reading?.value)
        assertEquals(
            listOf("かよいちょうしじょう", "かよいちょういちば", "つうちょうしじょう", "つうちょういちば"),
            query.formSets.drop(1).map { it.strict },
        )
        assertFalse(query.needsReading)
        assertTrue(query.withReading(TestAnalyzers.kuromoji) === query)
        val repeated = SearchQuery.of("通帳 通帳 通帳 通帳").withReading(TestAnalyzers.kuromoji)
        assertEquals(SearchKeyGenerator.DEFAULT_MAX_READING_COMBINATIONS, repeated.formSets.size - 1)
        val compound = SearchQuery.of("市場調査").withReading { listOf(ReadingToken("市場調査", "しじょうちょうさ", 0)) }
        assertEquals(listOf("しじょうちょうさ"), compound.formSets.drop(1).map { it.strict })
    }
}
