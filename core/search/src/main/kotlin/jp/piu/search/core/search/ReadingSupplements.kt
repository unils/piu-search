// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText

/**
 * 形態素解析器の読みの補い: 語の表記 → 足す読み（M1-06b）。
 *
 * Kuromoji（IPADIC）は 1 語に読みを 1 つしか付けず、アプリ名でよく使う読みと違うことがある（通帳 → かよいちょう、市場 → しじょう）。
 * 辞書の読みは消さずに、ここの読みを候補に足す（語の読みの組み合わせになる）。
 * 表記は形態素解析器の語の表記（NFKC の後）と完全に一致したときだけ引く。
 * 評価セットで外れた語だけを置く。数を増やすより、残りは学習で埋める（matching.md の方針）。
 *
 * 中身を変えたら [SearchIndexVersion.CURRENT] を上げる（`SearchIndexVersionTest` が中身と版の組を固定している）。
 */
internal object ReadingSupplements {
    /** 表記 → 足す読み（ひらがな）。 */
    val ENTRIES: Map<String, List<String>> =
        mapOf(
            // 「かよいちょう」と読む。銀行の通帳は「つうちょう」
            "通帳" to listOf("つうちょう"),
            // 「しじょう」と読む。楽天市場・築地市場など、店の名前では「いちば」
            "市場" to listOf("いちば"),
            // 「ひがしにっぽん」と読む。JR東日本は「ひがしにほん」
            "東日本" to listOf("ひがしにほん"),
            // 「すし」と読む。名前の後ろに付くと連濁して「ずし」（くら寿司・回転寿司）
            "寿司" to listOf("ずし"),
            // 「はらい」と読む。前に語が付くと連濁して「ばらい」（d払い・あと払い）
            "払い" to listOf("ばらい"),
        )

    private val readings: Map<String, List<NormalizedText>> = ENTRIES.mapValues { (_, list) -> list.map(::NormalizedText) }

    /** 表記 [surface] に足す読み。なければ空。 */
    fun of(surface: String): List<NormalizedText> = readings[surface].orEmpty()
}
