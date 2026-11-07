// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.RomajiConverter
import jp.piu.search.core.text.RomajiMode
import jp.piu.search.core.text.TextNormalizer
import jp.piu.search.core.text.containsKanji
import jp.piu.search.core.text.containsLatin
import java.text.Normalizer

/**
 * 照合の前に整えた検索語（matching.md の「検索語の扱い」）。
 *
 * 正規化とローマ字への変換を、照合（[SearchEngine.search]）の外で 1 回だけ行うために分けた。
 * 照合は索引のキーごとに比べるので、検索語の側の変換を中で繰り返さない。
 *
 * 漢字を含む検索語（IME で変換を確定した場合）は、まず [of] で作って名前のキーと比べ、すぐ結果を出す。
 * 読みを付けた結果は、裏のスレッドで [withReading] を呼んで作った検索語で、もう一度 [SearchEngine.search] を呼んで得る
 * （形態素解析器の準備を待たずに最初の結果を出すため）。
 */
public class SearchQuery private constructor(
    /** 打たれたままの検索語。 */
    public val raw: String,
    /** 検索語の厳密形。 */
    public val strict: NormalizedText,
    /** 検索語そのものから作った形。 */
    internal val primary: QueryForms,
    /** 形態素解析器で付けた読み。[withReading] の前と、漢字を含まない検索語では null。 */
    public val reading: NormalizedText?,
    private val readingForms: QueryForms?,
) {
    /** 照合に使う形の組。検索語そのものの形が先、読みの形が後。 */
    internal val formSets: List<QueryForms> = listOfNotNull(primary, readingForms)

    /** 空の検索語か。空なら照合せず、最近と頻繁に使ったアプリを出す（ui.md）。 */
    public val isEmpty: Boolean get() = strict.isEmpty

    /** 漢字を含むか。含むなら、読みを付けた検索語（[withReading]）でも照らす。 */
    public val containsKanji: Boolean = strict.value.containsKanji()

    /** 読みを付けた検索語を作る必要があるか。漢字を含み、まだ読みを付けていなければ true。 */
    public val needsReading: Boolean get() = containsKanji && reading == null

    /** 英字（と数字）だけの検索語か。頭文字・飛ばし一致（段階 6）と、ジャンルの字数の規則に使う。 */
    internal val isLatin: Boolean = strict.value.isRomajiOnly()

    /** 厳密形の字数（コードポイント）。 */
    internal val length: Int = strict.value.codePointCount(0, strict.value.length)

    /** ジャンルの語と比べてよい長さか（matching.md の段階 4: かな 2 字以上、英字 3 字以上）。 */
    internal val isLongEnoughForGenre: Boolean =
        length >= if (isLatin) MatchingConfig.GENRE_MIN_LATIN_LENGTH else MatchingConfig.GENRE_MIN_KANA_LENGTH

    /**
     * 形態素解析器 [analyzer] で読みを付けた検索語。漢字を含まない検索語にはこの検索語自身を返す。
     *
     * 形態素解析をするので、入力のスレッドではなく裏のスレッドで呼ぶ。
     * 語の読みをつなぎ、読みのない語（英字・辞書にない漢字）は表記のまま残す（「乗り換え」→ のりかえ、「yahoo天気」→ yahooてんき）。
     */
    public fun withReading(analyzer: ReadingAnalyzer): SearchQuery {
        if (!containsKanji || reading != null) return this
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val joined = StringBuilder(nfkc.length * 2)
        analyzer.analyze(nfkc).forEach { joined.append(it.reading ?: it.surface) }
        val readingText = TextNormalizer.strict(joined)
        // 読みが付かなかった（辞書にない漢字だけ）なら、同じ形で照らし直しても結果は変わらない
        if (readingText.isEmpty || readingText == strict) return SearchQuery(raw, strict, primary, strict, null)
        return SearchQuery(raw, strict, primary, readingText, QueryForms.of(readingText, fromReading = true))
    }

    override fun toString(): String = "SearchQuery($raw)"

    /** 検索語の作り方。 */
    public companion object {
        /** 打たれた文字列 [raw] から検索語を作る。正規化とローマ字への変換を行う（形態素解析はしない）。 */
        public fun of(raw: String): SearchQuery {
            val strict = TextNormalizer.strict(raw)
            return SearchQuery(raw, strict, QueryForms.of(strict, fromReading = false), null, null)
        }
    }
}

/**
 * 検索語の 1 つの形の組: 厳密形・緩い形・標準形のローマ字（完結と途中）・ローマ字の緩い形。
 *
 * ローマ字の形は、変換の結果が英字と数字だけのときにだけ持つ。漢字やほかの用字が残る形（`乗換`）はローマ字のキーと比べても意味がない。
 * 英字の入力の途中でローマ字が空になったとき（`ch` で終わるなど）も持たない（M1-01 の約束: 空のローマ字の経路を使わない）。
 */
internal class QueryForms(
    val strict: String,
    val loose: String,
    val romajiComplete: String?,
    val romajiPrefix: String?,
    val romajiLoose: String?,
    val romajiLooseComplete: String?,
    /**
     * ローマ字の一致を厳密形の一致とみなすか。検索語に英字があるときだけ true。
     *
     * かなだけの検索語は、かなのキーと厳密に比べられる。ローマ字にすると長音が消え、じ・ぢが重なるので（めーる → `meru` が めるかり に当たる）、
     * かなだけの検索語のローマ字の一致は緩い形の一致として並べる。
     */
    val romajiIsStrict: Boolean,
    /** 形態素解析器の読みから作った形か。出た理由に使う。 */
    val fromReading: Boolean,
) {
    companion object {
        fun of(
            strict: NormalizedText,
            fromReading: Boolean,
        ): QueryForms {
            val complete = RomajiConverter.toStandard(strict, RomajiMode.COMPLETE).value.takeIf { it.isRomajiOnly() }
            val prefix = RomajiConverter.toStandard(strict, RomajiMode.PREFIX).value.takeIf { it.isRomajiOnly() }
            return QueryForms(
                strict = strict.value,
                loose = TextNormalizer.loose(strict).value,
                romajiComplete = complete,
                romajiPrefix = prefix,
                romajiLoose = prefix?.let { RomajiConverter.loose(NormalizedText(it)).value },
                romajiLooseComplete = complete?.let { RomajiConverter.loose(NormalizedText(it)).value },
                romajiIsStrict = strict.value.containsLatin(),
                fromReading = fromReading,
            )
        }
    }
}
