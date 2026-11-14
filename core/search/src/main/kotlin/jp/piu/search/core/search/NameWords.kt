// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.ReadingToken
import jp.piu.search.core.text.CharKind

/** 名前の中の語の種類。読みの付け方が変わる。 */
internal enum class WordKind {
    /** かな・漢字の語。読みは形態素解析器から。 */
    JAPANESE,

    /** 英字の語。読みはブランド表と略語の規則から。 */
    LATIN,

    /** 数字の語と、ほかの用字（ハングル・キリル文字など）の語。読みを付けない。 */
    OTHER,

    /**
     * 記号「+」。「Disney+」「少年ジャンプ+」のように、名前の「+」は「ぷらす」と読まれる。
     *
     * 正規化で記号として消えるので、名前のキーには何も足さず、読みとローマ字のキーにだけ「ぷらす」を足す。
     */
    PLUS,
}

/** 名前の中の語 1 つ。 */
internal data class NameWord(
    /** NFKC の後の表記。 */
    val surface: String,
    val kind: WordKind,
    /** 形態素解析器の読み（ひらがな）。[WordKind.JAPANESE] で辞書に読みがあるときだけ。 */
    val reading: String? = null,
)

/**
 * NFKC をかけた名前 [nfkc] を語に分ける。[tokens] は [nfkc] を形態素解析器にかけた結果。
 *
 * - かな・漢字の語は、形態素解析器の語の区切りと読みをそのまま使う
 * - 残りの部分（英字・数字・記号）は、形態素解析器の区切りを使わず、文字の種類で分ける。英字の語は、空白と記号
 *   （`-`・`!`・`.`・`&` など）で分かれる（`Wi-Fi` → `Wi`・`Fi`、`Uber Eats` → `Uber`・`Eats`、`F-Droid` → `F`・`Droid`）。
 *   ブランド表の綴りはこの単位で書く約束（`brands.tsv` の頭の説明）なので、表を引く単位とここの語をそろえる。
 *   ただし英字に挟まれたアポストロフィは語の中に含める（`McDonald's` → 1 語。表の綴りは `mcdonalds`）。
 *   Kuromoji は記号で英字を分けるのでほぼ同じになるが、アポストロフィと、読みのない語の扱いを形態素解析器に依らず決めるため自前で分ける
 * - 英字と数字の境、かなと英字の境でも分ける（`モバイルSuica` → モバイル・Suica）。
 *   ただし [latinReader] が読める英数字の連なり（`Qoo10`）は、ブランド表の綴りを失わないよう 1 語にする
 *
 * 形態素解析器が読みを付けなかった日本語の部分（辞書にない漢字、[jp.piu.search.core.reading.NoReadingAnalyzer] の結果）も、文字の種類で分ける。
 */
internal fun splitNameWords(
    nfkc: String,
    tokens: List<ReadingToken>,
    latinReader: LatinWordReader? = null,
): List<NameWord> {
    val words = ArrayList<NameWord>()
    var pos = 0
    for (token in tokens.sortedBy { it.start }) {
        val reading = token.reading
        if (token.start < pos || token.end > nfkc.length || reading == null || !token.surface.hasJapanese()) continue
        splitByCharClass(nfkc, pos, token.start, words, latinReader)
        words.add(NameWord(token.surface, WordKind.JAPANESE, reading))
        pos = token.end
    }
    splitByCharClass(nfkc, pos, nfkc.length, words, latinReader)
    return words
}

private fun String.hasJapanese(): Boolean = codePoints().anyMatch { classOf(it) == CharClass.JAPANESE }

private enum class CharClass { JAPANESE, LATIN, DIGIT, OTHER_LETTER, MARK, SEPARATOR }

private fun classOf(cp: Int): CharClass {
    val kind = CharKind.of(cp)
    return when {
        kind.isKana || kind == CharKind.KANJI -> CharClass.JAPANESE
        kind == CharKind.LATIN -> CharClass.LATIN
        kind == CharKind.DIGIT -> CharClass.DIGIT
        Character.isLetterOrDigit(cp) -> CharClass.OTHER_LETTER
        else -> classOfNonLetter(cp)
    }
}

private fun classOfNonLetter(cp: Int): CharClass =
    when (Character.getType(cp).toByte()) {
        // 結合文字（異体字セレクタを含む）は前の字の一部
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK -> CharClass.MARK

        // 正規化で残る数字の類（Ⅻ・① は NFKC で数字になるが、残るものもある）
        Character.LETTER_NUMBER, Character.OTHER_NUMBER -> CharClass.OTHER_LETTER

        else -> CharClass.SEPARATOR
    }

// 全角の＋は NFKC で + になる
private const val PLUS_SIGN = '+'.code

private fun isApostrophe(cp: Int): Boolean = cp == '\''.code || cp == '’'.code

/** [text] の [start] から [end] の前までを、文字の種類の連なりで語に分けて [out] に足す。 */
private fun splitByCharClass(
    text: String,
    start: Int,
    end: Int,
    out: MutableList<NameWord>,
    latinReader: LatinWordReader?,
) {
    var runStart = -1
    var runClass = CharClass.SEPARATOR

    fun close(at: Int) {
        if (runStart >= 0) out.add(NameWord(text.substring(runStart, at), wordKindOf(runClass)))
        runStart = -1
    }

    var i = start
    while (i < end) {
        val cp = text.codePointAt(i)
        val next = i + Character.charCount(cp)
        var cls = classOf(cp)
        if (runStart < 0 || (runClass != CharClass.LATIN && runClass != CharClass.DIGIT)) {
            val brandEnd = alphanumericBrandEnd(text, i, end, latinReader)
            if (brandEnd > i) {
                close(i)
                out.add(NameWord(text.substring(i, brandEnd), WordKind.LATIN))
                i = brandEnd
                continue
            }
        }
        if (cls == CharClass.MARK && runStart >= 0) cls = runClass
        if (runClass == CharClass.LATIN && runStart >= 0 && isApostrophe(cp) && next < end &&
            classOf(text.codePointAt(next)) == CharClass.LATIN
        ) {
            cls = CharClass.LATIN
        }
        if (cp == PLUS_SIGN) {
            close(i)
            out.add(NameWord(text.substring(i, next), WordKind.PLUS))
        } else if (cls == CharClass.SEPARATOR) {
            close(i)
        } else if (runStart < 0 || cls != runClass) {
            // 種類が変わったら新しい語にする（モバイルSuica、LINE2）
            close(i)
            runStart = i
            runClass = if (cls == CharClass.MARK) CharClass.OTHER_LETTER else cls
        }
        i = next
    }
    close(end)
}

/** 表にある英数字のブランドだけをまとめ、数字の読みのない LINE2 などは従来どおり区切る。 */
private fun alphanumericBrandEnd(
    text: String,
    start: Int,
    end: Int,
    latinReader: LatinWordReader?,
): Int {
    if (latinReader == null) return start
    var at = start
    var hasLatin = false
    var hasDigit = false
    while (at < end) {
        val cp = text.codePointAt(at)
        when (classOf(cp)) {
            CharClass.LATIN -> hasLatin = true
            CharClass.DIGIT -> hasDigit = true
            else -> break
        }
        at += Character.charCount(cp)
    }
    return if (hasLatin && hasDigit && latinReader.readings(text.substring(start, at)).isNotEmpty()) at else start
}

private fun wordKindOf(cls: CharClass): WordKind =
    when (cls) {
        CharClass.JAPANESE -> WordKind.JAPANESE
        CharClass.LATIN -> WordKind.LATIN
        else -> WordKind.OTHER
    }
