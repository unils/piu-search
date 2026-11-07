// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.CharKind
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.TextNormalizer
import java.text.Normalizer

/**
 * アプリ名の英字の語に読み（ひらがな）を付ける（matching.md の「英字のブランド名の読み」の 1 と 2）。
 *
 * 1. ブランド表 [brands] にある綴りなら、表の読み
 * 2. 表になく、アプリ名の側で大文字だけの語（略語）なら、1 字ずつの読み（`NHK` → えぬえいちけー）
 * 3. どちらでもなければ読みなし（学習で埋める）
 *
 * 表を略語より優先する（`JAL` → じゃる、`PAY` → ぺい）。大文字かどうかは書かれた形で見るので、
 * 正規化（小文字にする）の前の語を渡す。
 */
public class LatinWordReader(
    private val brands: BrandTable,
) {
    /**
     * アプリ名に書かれた英字の語 [word]（大文字と小文字を区別した形）の読みの一覧。読みがなければ空。
     */
    public fun readings(word: String): List<NormalizedText> {
        val spelling = TextNormalizer.strict(word)
        if (spelling.isEmpty) return emptyList()
        val fromTable = brands.readings(spelling)
        if (fromTable.isNotEmpty()) return fromTable
        return listOfNotNull(AcronymReader.read(word))
    }
}

/**
 * 大文字の略語の 1 字ずつの読み（A → えー、B → びー …）。
 *
 * 略語をかなで打つ人（フリック入力で「えぬえいち」など）のための規則。効果が小さければ評価セットで判断して外す。
 */
public object AcronymReader {
    /**
     * [word] が大文字の略語なら 1 字ずつの読みをつないだもの、そうでなければ null。
     *
     * 略語とみなすのは、NFKC の後の文字が `A`〜`Z` と記号（`.`・`&`・`-` など。読まない）だけで、`A`〜`Z` を 1 字以上含む語。
     * 小文字・数字・ほかの文字を含む語は略語とみなさない（数字は読みを決めにくく、落とすと別の語になる）。
     * 1 字の大文字（`X`）も略語として読む。表にある語は表が先に答える。
     */
    public fun read(word: String): NormalizedText? {
        val folded = Normalizer.normalize(word, Normalizer.Form.NFKC)
        val out = StringBuilder(folded.length * READING_LENGTH_HINT)
        for (ch in folded) {
            when {
                ch in 'A'..'Z' -> out.append(LETTER_READINGS[ch - 'A'])
                isSkippable(ch) -> Unit
                else -> return null
            }
        }
        return if (out.isEmpty()) null else NormalizedText(out.toString())
    }

    /** 文字・数字でない字（記号・空白）は読まずに飛ばす。 */
    private fun isSkippable(ch: Char): Boolean = !Character.isLetterOrDigit(ch) && CharKind.of(ch.code) == CharKind.OTHER

    /** 1 字の読みのおおよその長さ。StringBuilder の初めの大きさにだけ使う。 */
    private const val READING_LENGTH_HINT = 3

    // 読みは 1 字に 1 つ。えいち/えっち、ぜっと/ぜっど のような揺れを足すと、語の長さに対して組み合わせが指数で増えるため。
    // 揺れは学習で埋める
    private val LETTER_READINGS: List<String> =
        listOf(
            "えー", // A
            "びー", // B
            "しー", // C
            "でぃー", // D
            "いー", // E
            "えふ", // F
            "じー", // G
            "えいち", // H
            "あい", // I
            "じぇー", // J
            "けー", // K
            "える", // L
            "えむ", // M
            "えぬ", // N
            "おー", // O
            "ぴー", // P
            "きゅー", // Q
            "あーる", // R
            "えす", // S
            "てぃー", // T
            "ゆー", // U
            "ぶい", // V
            "だぶりゅー", // W
            "えっくす", // X
            "わい", // Y
            "ぜっと", // Z
        )
}
