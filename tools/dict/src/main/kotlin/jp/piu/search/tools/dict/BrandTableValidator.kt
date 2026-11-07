// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.tools.dict

import jp.piu.search.core.text.CharKind
import jp.piu.search.core.text.TextNormalizer

/** 表の 1 行の問題。[line] は 1 から数えた行の番号。 */
public data class Problem(
    /** 行の番号（1 から）。 */
    val line: Int,
    /** 何が誤りか。 */
    val message: String,
) {
    override fun toString(): String = "$line 行目: $message"
}

/**
 * `brands.tsv` の書式の検査。
 *
 * 読み込みの側（`core:search` の BrandTable）は崩れた行で止まるだけなので、ここで全部の誤りを行の番号つきで並べる。
 * 規則:
 *
 * - 空の行と `#` で始まる行は飛ばす
 * - 列はタブ区切りで 3 つ（綴り・読み・メモ）
 * - 綴りは [TextNormalizer.strict] を通した形のままで、英字を含み、英字と数字だけ
 * - 読みは `,` 区切りで 1 つ以上。それぞれ空でなく、ひらがなと「ー」だけ。同じ行で重ならない
 * - 綴りが表の中で重ならない
 */
public object BrandTableValidator {
    private const val COLUMN_COUNT = 3
    private const val PROLONGED_SOUND_MARK = 'ー'

    /** 行の一覧 [lines] を検査し、見つけた問題を行の順に返す。問題がなければ空。 */
    public fun validate(lines: List<String>): List<Problem> {
        val problems = ArrayList<Problem>()
        val firstLineBySpelling = HashMap<String, Int>()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val columns = line.split('\t')
            if (columns.size != COLUMN_COUNT) {
                problems += Problem(lineNumber, "列が ${columns.size} 個（綴り・読み・メモの $COLUMN_COUNT 個のはず）")
                return@forEachIndexed
            }
            val (spelling, readings) = columns
            problems += checkSpelling(spelling).map { Problem(lineNumber, it) }
            problems += checkReadings(readings).map { Problem(lineNumber, it) }
            val first = firstLineBySpelling.putIfAbsent(spelling, lineNumber)
            if (first != null) problems += Problem(lineNumber, "綴り「$spelling」が $first 行目と重なる")
        }
        return problems
    }

    private fun checkSpelling(spelling: String): List<String> {
        if (spelling.isEmpty()) return listOf("綴りが空")
        val normalized = TextNormalizer.strict(spelling).value
        // 正規化されていなければ字の検査は正規化の後の形でやり直すことになるので、ここで止める
        if (normalized != spelling) return listOf("綴り「$spelling」が正規化されていない（正規化すると「$normalized」）")
        val problems = ArrayList<String>()
        val kinds = spelling.codePoints().toArray().map(CharKind::of)
        if (CharKind.LATIN !in kinds) problems += "綴り「$spelling」に英字がない"
        if (kinds.any { it != CharKind.LATIN && it != CharKind.DIGIT }) problems += "綴り「$spelling」に英字と数字のほかの字がある"
        return problems
    }

    private fun checkReadings(field: String): List<String> {
        val readings = field.split(',')
        val problems = ArrayList<String>()
        val seen = HashSet<String>()
        for (reading in readings) {
            when {
                reading.isEmpty() -> problems += "空の読みがある（「$field」）"
                !reading.all(::isReadingChar) -> problems += "読み「$reading」にひらがなと「ー」のほかの字がある"
                !seen.add(reading) -> problems += "読み「$reading」が同じ行で重なる"
            }
        }
        return problems
    }

    // 結合文字の濁点（U+3099）は KANA_MARK なので外れる。NFKC で合成済みの形だけを受ける
    private fun isReadingChar(ch: Char): Boolean = ch == PROLONGED_SOUND_MARK || CharKind.of(ch.code) == CharKind.HIRAGANA
}
