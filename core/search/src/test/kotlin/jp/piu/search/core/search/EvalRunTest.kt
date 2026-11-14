// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * 評価セットを照合にかけ、報告を `build/reports/eval/` に書き、合格の目安（[EvalReport]）と基準（[EvalBaseline]）を確かめる。
 *
 * 報告は `eval.md`（指標・外れた検索語・基準との差）と `results.tsv`・`results-learned.tsv`（検索語ごとの順位と上位）。
 * 基準を書き直すときは `./gradlew :core:search:updateEvalBaseline` で回す（`eval.updateBaseline` に基準のファイルのパスが入る）。
 */
class EvalRunTest {
    @Test
    fun `評価セットが合格の目安を満たし、基準より下がらない`() {
        val report = evaluation.plain
        val learned = evaluation.learned

        val dir = File(System.getProperty(REPORT_DIR_PROPERTY) ?: "build/reports/eval").apply { mkdirs() }
        val update = System.getProperty(UPDATE_BASELINE_PROPERTY)?.let(::File)
        if (update != null) update.writeText(EvalBaseline.format(report, learned))
        // 書き直すときは、書いた中身と比べて差のない節を出す
        val baseline =
            update?.let { EvalBaseline.parse(it.readText()) }
                ?: EvalSet.resourceOrNull(EvalBaseline.FILE)?.let(EvalBaseline::parse)
        val comparison = baseline?.compare(report, learned)
        val sections = listOfNotNull(learned.learnedSection(report), comparison?.let { baseline.section(it) })
        File(dir, "eval.md").writeText(report.markdown(sections.joinToString("\n")))
        File(dir, "results.tsv").writeText(report.tsv())
        File(dir, "results-learned.tsv").writeText(learned.tsv())
        println(
            "評価セット: 3 位以内（学習を除く）${EvalReport.percent(report.scoredTop3Rate)}、" +
                "学習の分類 ${EvalReport.percent(report.learningTop3Rate)} → 学習の後 ${EvalReport.percent(learned.learningTop3Rate)}。" +
                "報告 ${dir.absolutePath}/eval.md",
        )
        if (update != null) println("評価セットの基準を書き直した: ${update.absolutePath}")
        comparison?.improvements?.takeIf { it.isNotEmpty() }?.let {
            println("基準より上がった検索語が ${it.size} 件ある。:core:search:updateEvalBaseline で基準を書き直すと固定できる")
        }

        assertEquals(emptyList<String>(), report.failures(), "評価セットが合格の目安に届かない。${dir.absolutePath}/eval.md を見る")
        assertEquals(emptyList<String>(), learned.learnedFailures(report), "学習の目安に届かない。${dir.absolutePath}/eval.md を見る")
        if (comparison == null) fail("評価セットの基準 eval/${EvalBaseline.FILE} がない。$UPDATE_HINT で作る")
        if (comparison.regressions.isNotEmpty()) {
            fail(
                "評価セットの結果が基準より下がった（${comparison.regressions.size} 件）:\n" +
                    comparison.regressions.joinToString("\n") { "- $it" } +
                    "\n意図した変更なら $UPDATE_HINT で基準を書き直し、差分をレビューに出す。${dir.absolutePath}/eval.md を見る",
            )
        }
    }

    @Test
    fun `学習の後の比べから外すのは、教えた語そのものを打った行だけ`() {
        val outcomes = evaluation.learned.outcomes.associateBy { it.query.query to it.query.category }

        fun taught(
            query: String,
            category: String,
        ) = outcomes.getValue(query to category).taught
        // 教えた「ぎんこう」を、かな・漢字・ローマ字・打ちかけの形で打った行
        assertTrue(taught("ぎんこう", "ジャンル"))
        assertTrue(taught("銀行", "漢字の検索語"))
        assertTrue(taught("ginkou", "ローマ字"))
        assertTrue(taught("ginko", "ローマ字"))
        assertTrue(taught("ぎんk", "途中まで"))
        // 教えた「しんかんせんよやく」「らくてんしょうけん」「あまぞんぷらいむ」の頭にあたる別の語。学習の副作用を見たいので比べる
        assertFalse(taught("shinkansen", "ローマ字"))
        assertFalse(taught("sinkansen", "ローマ字"))
        assertFalse(taught("shinnkannsenn", "ローマ字"))
        assertFalse(taught("rakuten", "ローマ字"))
        assertFalse(taught("amazon", "英字のブランド"))
    }

    @Test
    fun `報告の分類の並びが評価セットの分類をすべて持つ`() {
        assertEquals(EvalSet.CATEGORIES, EvalReport.CATEGORY_ORDER.toSet())
    }

    // 以下は指標の数え方を小さな例で固定する
    @Test
    fun `any は最も上の正解の順位で測る`() {
        val o = outcome(Judge.ANY, 4, 2)
        assertEquals(2, o.effectiveRank)
        assertTrue(o.isTop3)
        assertFalse(o.isTop1)
        assertEquals(0.5, o.reciprocalRank)
    }

    @Test
    fun `all は正解がすべて max(3, 正解の数) 位以内で 3 位以内`() {
        // 正解 4 件が 1〜4 位: 隙間がないので 1 位正解
        val packed = outcome(Judge.ALL, 1, 2, 3, 4)
        assertEquals(1, packed.effectiveRank)
        assertTrue(packed.isTop1)
        assertTrue(packed.isTop3)
        // 正解 2 件が 1 位と 3 位: max(3, 2) = 3 位以内に入るが、隙間がある
        val gap = outcome(Judge.ALL, 1, 3)
        assertEquals(2, gap.effectiveRank)
        assertTrue(gap.isTop3)
        assertFalse(gap.isTop1)
        // 正解 2 件の片方が 4 位: 3 位以内に入らない
        assertFalse(outcome(Judge.ALL, 1, 4).isTop3)
        // 1 件でも出なければ外れ
        val missing = outcome(Judge.ALL, 1, null)
        assertEquals(null, missing.effectiveRank)
        assertFalse(missing.isTop3)
        assertEquals(0.0, missing.reciprocalRank)
    }

    @Test
    fun `合格の判定は学習を除き、既知の弱点は外れに数え、全問正解の例は印があっても外さない`() {
        // 学習を除く 20 件のうち 19 件が正解: 95.0% で合格。学習の外れは分母に入らない
        val outcomes =
            listOf(
                outcome(Judge.ANY, null, category = EvalSet.LEARNING_CATEGORY),
                outcome(Judge.ANY, null, weakness = "理由"),
            ) + List(19) { outcome(Judge.ANY, 1) }
        val passed = EvalReport(outcomes)
        assertEquals(0.95, passed.scoredTop3Rate)
        assertEquals(emptyList<String>(), passed.failures())

        // 既知の弱点を分母から外せば 18/18 で合格になる組。外れに数えるので 18/19 で落ちる
        val weakCounted = EvalReport(outcomes.drop(1).dropLast(1) + outcome(Judge.ANY, null, category = EvalSet.LEARNING_CATEGORY))
        assertEquals(18.0 / 19, weakCounted.scoredTop3Rate)
        assertTrue(weakCounted.failures().any { "3 位以内の正解率" in it }, weakCounted.failures().toString())

        val required = EvalReport(outcomes + outcome(Judge.ALL, null, category = EvalReport.REQUIRED_CATEGORY, weakness = "理由"))
        assertTrue(required.failures().any { "全問正解の例" in it }, required.failures().toString())

        val tooManyWeak = EvalReport(List(3) { outcome(Judge.ANY, null, weakness = "理由") } + List(17) { outcome(Judge.ANY, 1) })
        assertTrue(tooManyWeak.failures().any { "既知の弱点が 3 件" in it }, tooManyWeak.failures().toString())
    }

    @Test
    fun `学習の目安は学習の分類の 3 位以内と、教えた検索語を除く分類ごとの指標で見る`() {
        val learning = { rank: Int?, q: String -> outcome(Judge.ANY, rank, category = EvalSet.LEARNING_CATEGORY, query = q) }
        val before = EvalReport(listOf(learning(null, "a"), outcome(Judge.ANY, 1, query = "b"), outcome(Judge.ANY, 1, query = "c")))
        // 学習の分類が 1/1、b は教えた検索語なので下がってもよい
        val ok =
            EvalReport(
                listOf(learning(1, "a"), outcome(Judge.ANY, 2, query = "b", taught = true), outcome(Judge.ANY, 1, query = "c")),
            )
        assertEquals(emptyList<String>(), ok.learnedFailures(before))
        assertTrue("比べなかった" in ok.learnedSection(before))
        // c が下がった・学習の分類が届かない
        val bad = EvalReport(listOf(learning(4, "a"), outcome(Judge.ANY, 1, query = "b"), outcome(Judge.ANY, 2, query = "c")))
        val failures = bad.learnedFailures(before)
        assertTrue(failures.any { "3 位以内が 0.0%" in it }, failures.toString())
        assertTrue(failures.any { "「表記ゆれ」の指標が下がった" in it }, failures.toString())
        assertTrue("c（表記ゆれ）: 1 → 2" in bad.learnedSection(before))
    }

    @Test
    fun `基準と比べ、検索語ごとに下がった・上がった・食い違う行を出す`() {
        val before =
            EvalReport(
                listOf(
                    outcome(Judge.ANY, 5, query = "a"),
                    outcome(Judge.ANY, 1, query = "b"),
                    outcome(Judge.ANY, 2, query = "c"),
                    outcome(Judge.ANY, 1, query = "gone"),
                ),
            )
        val baseline = EvalBaseline.parse(EvalBaseline.format(before, before))
        assertEquals(4, baseline.rows.size)

        val after =
            EvalReport(
                listOf(
                    outcome(Judge.ANY, 1, query = "a"),
                    outcome(Judge.ANY, null, query = "b"),
                    outcome(Judge.ANY, 3, query = "c"),
                    outcome(Judge.ANY, 1, query = "new"),
                ),
            )
        val learnedAfter = EvalReport(after.outcomes.map { if (it.query.query == "c") outcome(Judge.ANY, 1, query = "c") else it })
        val comparison = baseline.compare(after, learnedAfter)
        assertEquals(
            listOf(
                "「b」（表記ゆれ）: 学習なしの順位 1 → -、3 位以内から外れた",
                "「b」（表記ゆれ）: 学習の後の順位 1 → -、3 位以内から外れた",
                "「c」（表記ゆれ）: 学習なしの順位 2 → 3",
                "「new」（表記ゆれ）: 基準にない（評価セットに足したなら、基準を書き直す）",
                "「gone」（表記ゆれ）: 基準にあるが評価セットにない（消したなら、基準を書き直す）",
            ),
            comparison.regressions,
        )
        assertEquals(
            listOf(
                "「a」（表記ゆれ）: 学習なしの順位 5 → 1、3 位以内に入った",
                "「a」（表記ゆれ）: 学習の後の順位 5 → 1、3 位以内に入った",
                "「c」（表記ゆれ）: 学習の後の順位 2 → 1",
            ),
            comparison.improvements,
        )
        // 同じ結果なら差はない
        val same = baseline.compare(before, before)
        assertEquals(emptyList<String>(), same.regressions)
        assertEquals(emptyList<String>(), same.improvements)
    }

    private fun outcome(
        judge: Judge,
        vararg ranks: Int?,
        category: String = "表記ゆれ",
        weakness: String? = null,
        query: String = "q",
        taught: Boolean = false,
    ): QueryOutcome =
        QueryOutcome(
            EvalQuery(query, ranks.indices.map { "app$it" }, judge, category, weakness),
            ranks.toList(),
            emptyList(),
            taught,
        )

    /** 評価セットを学習のない状態と学習の後で測った結果。 */
    private class Evaluation(
        val plain: EvalReport,
        val learned: EvalReport,
    )

    private companion object {
        // 辞書の読み込みと全件の照合に数秒かかるので、テストのメソッドの間で 1 回だけ測る
        val evaluation: Evaluation by lazy {
            val apps = EvalSet.apps(EvalSet.parse(EvalSet.resource("apps.tsv")))
            val queries = EvalSet.queries(EvalSet.parse(EvalSet.resource("queries.tsv")))
            val runner = EvalRunner(apps, TestAnalyzers.generator, TestAnalyzers.kuromoji)
            // 学習の効き目: 学習の分類の行（正解）は変えず、その行から作った記録を重ねて全体を測り直す
            val memory = runner.learnFrom(queries.filter { it.category == EvalSet.LEARNING_CATEGORY })
            val learned = EvalRunner(apps, TestAnalyzers.generator, TestAnalyzers.kuromoji, learning = memory)
            Evaluation(EvalReport(runner.run(queries)), EvalReport(learned.run(queries)))
        }

        const val REPORT_DIR_PROPERTY = "eval.reportDir"
        const val UPDATE_BASELINE_PROPERTY = "eval.updateBaseline"
        const val UPDATE_HINT = "./gradlew :core:search:updateEvalBaseline"
    }
}
