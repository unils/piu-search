// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

/**
 * 評価セットの基準（コミットした結果。`eval/baseline.tsv`）と、今の結果との比べ。
 *
 * 評価は決定的（同じ索引と検索語なら同じ順位）なので、率ではなく検索語ごとの順位を比べる。
 * 分類ごとの率で比べると、ある語が上がって別の語が下がったときに見逃すため。
 * 順位は学習のない状態と学習の後の両方を持つ。
 *
 * @property rows 検索語と分類の組 → 行
 */
internal class EvalBaseline(
    val rows: Map<Pair<String, String>, Row>,
) {
    /**
     * 検索語 1 件の基準。
     *
     * @property rank 学習のない状態の、指標に使う順位（[QueryOutcome.effectiveRank]）。当たらなければ null
     * @property top3 学習のない状態で 3 位以内か
     * @property learnedRank 学習の後の順位
     * @property learnedTop3 学習の後に 3 位以内か
     */
    data class Row(
        val query: String,
        val category: String,
        val rank: Int?,
        val top3: Boolean,
        val learnedRank: Int?,
        val learnedTop3: Boolean,
    ) {
        val key: Pair<String, String> get() = query to category
    }

    /**
     * 今の結果との比べ。
     *
     * @property regressions 基準より下がった検索語と、基準と評価セットの食い違い。空でなければ check を落とす
     * @property improvements 基準より上がった検索語。落とさないが、基準を書き直すと固定できる
     */
    class Comparison(
        val regressions: List<String>,
        val improvements: List<String>,
    )

    /** 学習のない状態の [plain] と学習の後の [learned]（同じ検索語の並び）を、この基準と比べる。 */
    fun compare(
        plain: EvalReport,
        learned: EvalReport,
    ): Comparison {
        val current = of(plain, learned).rows
        val regressions = mutableListOf<String>()
        val improvements = mutableListOf<String>()
        for ((key, now) in current) {
            val old = rows[key]
            if (old == null) {
                regressions += "${label(key)}: 基準にない（評価セットに足したなら、基準を書き直す）"
                continue
            }
            compareRank("学習なし", old.rank, old.top3, now.rank, now.top3)?.let { (worse, text) ->
                (if (worse) regressions else improvements) += "${label(key)}: $text"
            }
            compareRank("学習の後", old.learnedRank, old.learnedTop3, now.learnedRank, now.learnedTop3)?.let { (worse, text) ->
                (if (worse) regressions else improvements) += "${label(key)}: $text"
            }
        }
        (rows.keys - current.keys).forEach { regressions += "${label(it)}: 基準にあるが評価セットにない（消したなら、基準を書き直す）" }
        return Comparison(regressions, improvements)
    }

    /** Markdown の「基準との差」の節。 */
    fun section(comparison: Comparison): String =
        buildString {
            appendLine("## 基準との差")
            appendLine()
            appendLine("基準は `core/search/src/test/resources/eval/$FILE`。順位は指標に使う順位（`-` は当たらない）。")
            appendLine()
            appendLine("- 下がった・食い違う: ${comparison.regressions.size} 件")
            comparison.regressions.forEach { appendLine("  - $it") }
            appendLine("- 上がった: ${comparison.improvements.size} 件")
            comparison.improvements.forEach { appendLine("  - $it") }
        }

    companion object {
        /** 基準のファイルの名前（`eval/` の下）。 */
        const val FILE = "baseline.tsv"

        private val COLUMNS = listOf("検索語", "分類", "順位", "3位以内", "学習の後の順位", "学習の後の3位以内")

        /** 学習のない状態の [plain] と学習の後の [learned] から作る。 */
        fun of(
            plain: EvalReport,
            learned: EvalReport,
        ): EvalBaseline {
            require(plain.outcomes.map { it.query } == learned.outcomes.map { it.query }) { "学習の前後で検索語の並びが違う" }
            val rows =
                plain.outcomes.zip(learned.outcomes) { p, l ->
                    Row(p.query.query, p.query.category, p.effectiveRank, p.isTop3, l.effectiveRank, l.isTop3)
                }
            return EvalBaseline(rows.associateBy { it.key })
        }

        /** [format] で書いた TSV を読む。 */
        fun parse(text: String): EvalBaseline {
            val table = EvalSet.parse(text)
            require(table.header == COLUMNS) { "$FILE の列の名前が違う: ${table.header}（期待: $COLUMNS）" }
            val rows =
                table.rows.map {
                    Row(it["検索語"], it["分類"], rank(it["順位"]), it["3位以内"] == "1", rank(it["学習の後の順位"]), it["学習の後の3位以内"] == "1")
                }
            val result = rows.associateBy { it.key }
            require(result.size == rows.size) { "$FILE に同じ検索語と分類の行がある" }
            return EvalBaseline(result)
        }

        /**
         * 基準の TSV。行は評価セットと同じ順に並べ、上位のアプリの名前は持たない。
         * 書き直したときの差分を、順位の変わった行だけにするため。頭の注記の指標は読む人のためで、比べには使わない。
         */
        fun format(
            plain: EvalReport,
            learned: EvalReport,
        ): String =
            buildString {
                appendLine("# 評価セットの基準（EvalRunTest が比べる。書き直しは ./gradlew :core:search:updateEvalBaseline）")
                appendLine("# 検索語ごとの順位が 1 件でもこれより下がると check が落ちる。順位は指標に使う順位で、- は当たらない")
                appendLine(
                    "# 3 位以内（学習を除く）: ${EvalReport.percent(plain.scoredTop3Rate)}。" +
                        "「学習」の分類の 3 位以内: ${EvalReport.percent(
                            plain.learningTop3Rate,
                        )} → 学習の後 ${EvalReport.percent(learned.learningTop3Rate)}",
                )
                appendLine(COLUMNS.joinToString("\t"))
                for (row in of(plain, learned).rows.values) {
                    appendLine(
                        listOf(
                            row.query,
                            row.category,
                            row.rank.cell(),
                            row.top3.cell(),
                            row.learnedRank.cell(),
                            row.learnedTop3.cell(),
                        ).joinToString("\t"),
                    )
                }
            }

        // 下がったか上がったかと、その説明。変わらなければ null
        private fun compareRank(
            stateName: String,
            oldRank: Int?,
            oldTop3: Boolean,
            newRank: Int?,
            newTop3: Boolean,
        ): Pair<Boolean, String>? {
            val old = oldRank ?: Int.MAX_VALUE
            val new = newRank ?: Int.MAX_VALUE
            if (old == new && oldTop3 == newTop3) return null
            // all の 3 位以内は順位と正解の数で決まるので、順位が同じなら 3 位以内も同じ。念のため 3 位以内の後退も下がったとみなす
            val worse = new > old || (oldTop3 && !newTop3)
            val top3Change =
                when {
                    oldTop3 && !newTop3 -> "、3 位以内から外れた"
                    !oldTop3 && newTop3 -> "、3 位以内に入った"
                    else -> ""
                }
            return worse to "${stateName}の順位 ${oldRank.cell()} → ${newRank.cell()}$top3Change"
        }

        private fun label(key: Pair<String, String>): String = "「${key.first}」（${key.second}）"

        private fun rank(cell: String): Int? = if (cell == "-") null else requireNotNull(cell.toIntOrNull()) { "$FILE の順位が不正: $cell" }

        private fun Int?.cell(): String = this?.toString() ?: "-"

        private fun Boolean.cell(): String = if (this) "1" else "0"
    }
}
