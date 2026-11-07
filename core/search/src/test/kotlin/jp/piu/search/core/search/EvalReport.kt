// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import java.util.Locale

/** 検索語の集まりの指標。 */
internal class EvalMetrics(
    outcomes: List<QueryOutcome>,
) {
    val count: Int = outcomes.size
    val top1: Int = outcomes.count { it.isTop1 }
    val top3: Int = outcomes.count { it.isTop3 }
    val mrr: Double = if (count == 0) 0.0 else outcomes.sumOf { it.reciprocalRank } / count
    val weak: Int = outcomes.count { it.query.knownWeakness != null }

    fun top1Rate(): Double = rate(top1)

    fun top3Rate(): Double = rate(top3)

    private fun rate(n: Int): Double = if (count == 0) 0.0 else n.toDouble() / count
}

/**
 * 評価の結果と、合格の目安（testing.md の「評価セット」、M1 のタスクカードの M1-06b）の判定。
 *
 * 目安:
 *
 * 1. 「学習」の分類を除いて、3 位以内の正解率が [MIN_TOP3_RATE] 以上。「既知の弱点」の印の付いた検索語も分母に残し、
 *    外れとして数える（testing.md の「数え方」。印で数値をよく見せないため）
 * 2. T2（[T2_QUERY]）と「送り仮名」の分類は全問 3 位以内（印を付けても外さない）
 * 3. 「既知の弱点」は検索語の全体の [MAX_WEAK_RATE] まで
 */
internal class EvalReport(
    val outcomes: List<QueryOutcome>,
) {
    private val scored = outcomes.filter { it.query.category != EvalSet.LEARNING_CATEGORY }
    private val required = outcomes.filter { it.isRequired() }

    /** 合格の目安の 1 の値。 */
    val scoredTop3Rate: Double = EvalMetrics(scored).top3Rate()

    /** 合格の目安を満たさない理由。空なら合格。 */
    fun failures(): List<String> {
        val result = mutableListOf<String>()
        if (scoredTop3Rate < MIN_TOP3_RATE) {
            result += "3 位以内の正解率（学習を除く）が ${percent(scoredTop3Rate)}（目安 ${percent(MIN_TOP3_RATE)} 以上）"
        }
        required.filterNot { it.isTop3 }.forEach { result += "全問正解の例が外れた: ${it.query.query}（${it.query.category}）" }
        val weak = outcomes.count { it.query.knownWeakness != null }
        val maxWeak = (outcomes.size * MAX_WEAK_RATE).toInt()
        if (weak > maxWeak) result += "既知の弱点が $weak 件（全体の ${percent(MAX_WEAK_RATE)} = $maxWeak 件まで）"
        return result
    }

    /** Markdown の報告。[extra] は末尾に足す節（学習の後・前回との差。なければ null）。 */
    fun markdown(extra: String? = null): String =
        buildString {
            appendLine("# 評価セットの結果")
            appendLine()
            appendLine("`core:search` の `EvalRunTest` が書く。指標の定義は `EvalRunner.kt` の `QueryOutcome`。")
            appendLine()
            appendLine("## 合格の目安")
            appendLine()
            val failures = failures()
            appendLine(if (failures.isEmpty()) "満たす。" else "**満たさない。**")
            appendLine()
            appendLine(
                "- 3 位以内の正解率（学習を除く ${scored.size} 件。既知の弱点は外れに数える）: " +
                    "${percent(scoredTop3Rate)}（目安 ${percent(MIN_TOP3_RATE)} 以上）",
            )
            appendLine("- 全問正解の例（「$T2_QUERY」と送り仮名、${required.size} 件）: ${required.count { it.isTop3 }} 件正解")
            appendLine(
                "- 既知の弱点: ${outcomes.count { it.query.knownWeakness != null }} 件（全体 ${outcomes.size} 件の ${percent(MAX_WEAK_RATE)} まで）",
            )
            failures.forEach { appendLine("- 失敗: $it") }
            appendLine()
            appendLine("## 分類ごとの指標")
            appendLine()
            appendLine("既知の弱点も外れとして数える。all の判定は「正解がすべて max(3, 正解の数) 位以内」で 3 位以内とする。")
            appendLine()
            appendLine("| 分類 | 件数 | 1 位 | 3 位以内 | MRR | 既知の弱点 |")
            appendLine("|---|---:|---:|---:|---:|---:|")
            for (category in CATEGORY_ORDER) {
                metricsRow(category, outcomes.filter { it.query.category == category })
            }
            metricsRow("**学習を除く計**", scored)
            metricsRow("**全体**", outcomes)
            appendLine()
            appendLine("## 3 位以内に入らなかった検索語")
            appendLine()
            outcomeTable(outcomes.filterNot { it.isTop3 })
            appendLine()
            appendLine("## 3 位以内だが 1 位でない検索語")
            appendLine()
            outcomeTable(outcomes.filter { it.isTop3 && !it.isTop1 })
            val fixed = outcomes.filter { it.isTop3 && it.query.knownWeakness != null }
            if (fixed.isNotEmpty()) {
                appendLine()
                appendLine("## 既知の弱点の印を外せる検索語（3 位以内に入った）")
                appendLine()
                fixed.forEach { appendLine("- ${it.query.query}（${it.query.category}）") }
            }
            if (extra != null) {
                appendLine()
                append(extra)
            }
        }

    /** 検索語ごとの結果の TSV。次の回に前回の結果として渡し、差を出す（[diffAgainst]）。 */
    fun tsv(): String =
        buildString {
            appendLine(TSV_HEADER)
            for (o in outcomes) {
                appendLine(
                    listOf(
                        o.query.query,
                        o.query.category,
                        o.effectiveRank?.toString() ?: "-",
                        if (o.isTop3) "1" else "0",
                        o.top.joinToString(EvalSet.LIST_SEPARATOR),
                    ).joinToString("\t"),
                )
            }
        }

    /**
     * 前回の [tsv] の中身 [previous] との差の Markdown。検索語と分類の組で突き合わせる。
     */
    fun diffAgainst(previous: String): String {
        val before =
            previous
                .lines()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { it.split('\t') }
                .associate { (it[0] to it[1]) to it }
        val gained = mutableListOf<String>()
        val lost = mutableListOf<String>()
        val moved = mutableListOf<String>()
        for (o in outcomes) {
            val old = before[o.query.query to o.query.category] ?: continue
            val oldTop3 = old[TSV_TOP3] == "1"
            val oldRank = old[TSV_RANK]
            val newRank = o.effectiveRank?.toString() ?: "-"
            val line = "${o.query.query}（${o.query.category}）: $oldRank → $newRank"
            when {
                !oldTop3 && o.isTop3 -> gained += line
                oldTop3 && !o.isTop3 -> lost += line
                oldRank != newRank -> moved += line
            }
        }
        return buildString {
            appendLine("## 前回との差")
            appendLine()
            appendLine("順位は指標に使う順位（`-` は当たらない）。")
            appendLine()
            appendLine("- 新たに 3 位以内に入った: ${gained.size} 件")
            gained.forEach { appendLine("  - $it") }
            appendLine("- 新たに 3 位以内から外れた: ${lost.size} 件")
            lost.forEach { appendLine("  - $it") }
            appendLine("- 順位だけ変わった: ${moved.size} 件")
            moved.forEach { appendLine("  - $it") }
        }
    }

    /** 「学習」の分類の 3 位以内の正解率。学習の効き目の物差し（M4-01）。 */
    val learningTop3Rate: Double = EvalMetrics(outcomes.filter { it.query.category == EvalSet.LEARNING_CATEGORY }).top3Rate()

    /**
     * この報告を「学習の後」とみなし、学習のない状態 [before] と比べた、学習の目安（M4-01）を満たさない理由。空なら満たす。
     *
     * 1. 「学習」の分類の 3 位以内が [MIN_LEARNED_TOP3_RATE] 以上
     * 2. 学習を除く分類ごとに、1 位・3 位以内・MRR のどれも [before] より下がらない。
     *    ただし学習の分類で同じ検索語を教えた検索語（[QueryOutcome.taught]）は比べない。
     *    「ぎんこう」は学習の分類で名前に銀行のない 2 つを、ジャンルの分類で名前に銀行のある 6 つを、ともにすべて上位に求め、両方は満たせないため
     */
    fun learnedFailures(before: EvalReport): List<String> {
        val result = mutableListOf<String>()
        if (learningTop3Rate < MIN_LEARNED_TOP3_RATE) {
            result += "学習の後の「学習」の分類の 3 位以内が ${percent(learningTop3Rate)}（目安 ${percent(MIN_LEARNED_TOP3_RATE)} 以上）"
        }
        val taught = taughtKeys()
        for (category in CATEGORY_ORDER - EvalSet.LEARNING_CATEGORY) {
            fun compared(list: List<QueryOutcome>) = list.filter { it.query.category == category && it.key() !in taught }
            val old = EvalMetrics(compared(before.outcomes))
            val new = EvalMetrics(compared(outcomes))
            if (new.top1 < old.top1 || new.top3 < old.top3 || new.mrr < old.mrr - MRR_EPSILON) {
                result += "学習の後に「$category」の指標が下がった（1 位 ${old.top1} → ${new.top1}、3 位以内 ${old.top3} → ${new.top3}、" +
                    "MRR ${"%.3f".format(Locale.ROOT, old.mrr)} → ${"%.3f".format(Locale.ROOT, new.mrr)}）"
            }
        }
        return result
    }

    /** 「学習の後」の節の Markdown。この報告が学習の後、[before] が学習のない状態。 */
    fun learnedSection(before: EvalReport): String =
        buildString {
            appendLine("## 学習の後")
            appendLine()
            appendLine(
                "「学習」の分類の検索語ごとに「正解のアプリをすべてのアプリの一覧から 1 回開いた」場面を LearningPolicy に通し、" +
                    "すべての記録を重ねた索引で、全体を測り直した（M4-01）。",
            )
            appendLine()
            val failures = learnedFailures(before)
            appendLine(if (failures.isEmpty()) "目安を満たす。" else "**目安を満たさない。**")
            appendLine()
            val learningBefore = before.learningTop3Rate
            appendLine(
                "- 「学習」の分類の 3 位以内: ${percent(learningBefore)} → ${percent(learningTop3Rate)}" +
                    "（目安 ${percent(MIN_LEARNED_TOP3_RATE)} 以上）",
            )
            appendLine("- 学習を除く分類: 分類ごとに 1 位・3 位以内・MRR が学習のない状態より下がらないこと")
            failures.forEach { appendLine("- 失敗: $it") }
            appendLine()
            appendLine("表は比べなかった検索語（下記）も含めて数える。")
            appendLine()
            appendLine("| 分類 | 件数 | 1 位（前 → 後） | 3 位以内（前 → 後） | MRR（前 → 後） |")
            appendLine("|---|---:|---:|---:|---:|")
            for (category in CATEGORY_ORDER) {
                val old = EvalMetrics(before.outcomes.filter { it.query.category == category })
                val new = EvalMetrics(outcomes.filter { it.query.category == category })
                appendLine(
                    "| $category | ${new.count} | ${percent(old.top1Rate())} → ${percent(new.top1Rate())} | " +
                        "${percent(old.top3Rate())} → ${percent(new.top3Rate())} | " +
                        "${"%.3f".format(Locale.ROOT, old.mrr)} → ${"%.3f".format(Locale.ROOT, new.mrr)} |",
                )
            }
            appendLine()
            appendLine("### 学習の後も 3 位以内に入らなかった「学習」の検索語")
            appendLine()
            outcomeTable(outcomes.filter { it.query.category == EvalSet.LEARNING_CATEGORY && !it.isTop3 })
            appendLine()
            appendLine("### 学習の分類で同じ検索語を教えたので比べなかった、学習を除く検索語")
            appendLine()
            val taught = outcomes.filter { it.query.category != EvalSet.LEARNING_CATEGORY && it.taught }
            if (taught.isEmpty()) appendLine("なし。")
            val beforeRanks = before.outcomes.associate { it.key() to it.effectiveRank }
            taught.forEach { o ->
                appendLine(
                    "- ${o.query.query}（${o.query.category}）: ${beforeRanks[o.key()] ?: "-"} → ${o.effectiveRank ?: "-"}" +
                        "（上位: ${o.top.joinToString("、")}）",
                )
            }
            appendLine()
            appendLine("### 学習の後に順位が下がった、学習を除く検索語（比べなかったものを除く）")
            appendLine()
            val oldRanks = beforeRanks
            val worse =
                outcomes.filter { o ->
                    o.query.category != EvalSet.LEARNING_CATEGORY && !o.taught &&
                        (o.effectiveRank ?: Int.MAX_VALUE) > (oldRanks[o.query.query to o.query.category] ?: Int.MAX_VALUE)
                }
            if (worse.isEmpty()) appendLine("なし。")
            worse.forEach { o ->
                val old = oldRanks[o.query.query to o.query.category]
                appendLine(
                    "- ${o.query.query}（${o.query.category}）: ${old ?: "-"} → ${o.effectiveRank ?: "-"}（上位: ${o.top.joinToString("、")}）",
                )
            }
        }

    private fun StringBuilder.metricsRow(
        name: String,
        list: List<QueryOutcome>,
    ) {
        val m = EvalMetrics(list)
        appendLine(
            "| $name | ${m.count} | ${percent(
                m.top1Rate(),
            )} | ${percent(m.top3Rate())} | ${"%.3f".format(Locale.ROOT, m.mrr)} | ${m.weak} |",
        )
    }

    private fun StringBuilder.outcomeTable(list: List<QueryOutcome>) {
        if (list.isEmpty()) {
            appendLine("なし。")
            return
        }
        appendLine("| 検索語 | 分類 | 判定 | 正解（順位） | 上位 ${EvalRunner.TOP_SHOWN} 件 | 既知の弱点 |")
        appendLine("|---|---|---|---|---|---|")
        for (o in list.sortedBy { CATEGORY_ORDER.indexOf(it.query.category) }) {
            val answers =
                o.query.answers
                    .zip(o.ranks)
                    .joinToString("、") { (name, rank) -> "$name（${rank ?: "-"}）" }
            val top = o.top.joinToString("、").ifEmpty { "（なし）" }
            appendLine(
                "| ${cell(
                    o.query.query,
                )} | ${o.query.category} | ${o.query.judge.token} | ${cell(
                    answers,
                )} | ${cell(top)} | ${cell(o.query.knownWeakness ?: "")} |",
            )
        }
    }

    private fun taughtKeys(): Set<Pair<String, String>> = outcomes.filter { it.taught }.mapTo(HashSet()) { it.key() }

    private fun QueryOutcome.key(): Pair<String, String> = query.query to query.category

    private fun QueryOutcome.isRequired(): Boolean = query.query == T2_QUERY || query.category == REQUIRED_CATEGORY

    companion object {
        /** 合格の目安: 3 位以内の正解率（学習を除く。既知の弱点は外れに数える）。 */
        const val MIN_TOP3_RATE = 0.95

        /** 学習の目安: 学習の後の「学習」の分類の 3 位以内の正解率（M4-01 のタスクカード）。 */
        const val MIN_LEARNED_TOP3_RATE = 0.90

        /** MRR を比べるときの丸めの許し。 */
        private const val MRR_EPSILON = 1e-9

        /** 既知の弱点の上限（検索語の全体に対する割合）。 */
        const val MAX_WEAK_RATE = 0.05

        /** 要件 T2 の検索語。 */
        const val T2_QUERY = "やふー"

        /** 全問正解を求める分類。 */
        const val REQUIRED_CATEGORY = "送り仮名"

        /** 報告の分類の並び。queries.tsv の分類の説明の順に、短い語と学習を後ろに置く。 */
        val CATEGORY_ORDER =
            listOf("表記ゆれ", "送り仮名", "ローマ字", "英字のブランド", "略語", "ジャンル", "漢字の検索語", "途中まで", "短い語", "学習")

        private const val TSV_HEADER = "検索語\t分類\t順位\t3位以内\t上位"
        private const val TSV_RANK = 2
        private const val TSV_TOP3 = 3

        fun percent(rate: Double): String = "%.1f%%".format(Locale.ROOT, rate * 100)

        // Markdown の表の区切りと衝突する字を逃がす
        private fun cell(text: String): String = text.replace("|", "\\|")
    }
}
