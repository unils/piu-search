// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.reading

/**
 * 日本語の文字列を語に分け、語ごとに読み（ひらがな）を付ける部品。
 *
 * 照合の部分が形態素解析器の種類に依存しないよう、interface にしておく。
 */
public fun interface ReadingAnalyzer {
    /**
     * [text] を語に分けて返す。語は元の文字列の中の順に並ぶ。
     *
     * 空白だけの語は返さない（語の区切りは [ReadingToken.start] でわかるため）。
     * 空や空白だけの [text] には空の一覧を返す。
     *
     * [text] には NFKC だけをかけた文字列を渡す。半角カナのままだと読みが付かない。
     * 厳密形（ひらがなにし、記号を除いた形）を渡すと、辞書の表記と合わず語の切り方が崩れる。
     */
    public fun analyze(text: String): List<ReadingToken>
}

/**
 * 語ひとつの表記・読み・位置。
 *
 * 英字や記号の語の読みは、ブランド表と略語の規則（`core:search`）で別に付けるため、ここでは付けない。
 */
public data class ReadingToken(
    /** 元の文字列のとおりの表記。 */
    public val surface: String,
    /**
     * 読み（ひらがな。長音符「ー」はそのまま）。
     *
     * null は読みを付けていない語: 英字・数字・記号だけの語と、辞書にない漢字の語。
     */
    public val reading: String?,
    /** 元の文字列の中の開始位置（UTF-16 の単位）。 */
    public val start: Int,
) {
    /** 元の文字列の中の終了位置（この位置の文字を含まない）。 */
    public val end: Int get() = start + surface.length
}

/**
 * 読みを付けない [ReadingAnalyzer]。文字列全体を、読みのない 1 語として返す。
 *
 * 辞書を読み込む前や、メモリが足りず辞書を手放したときに、照合を止めずに済ませるため。
 */
public object NoReadingAnalyzer : ReadingAnalyzer {
    override fun analyze(text: String): List<ReadingToken> = if (text.isBlank()) emptyList() else listOf(ReadingToken(text, null, 0))
}
