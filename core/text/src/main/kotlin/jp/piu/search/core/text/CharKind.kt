// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

/**
 * 文字の種類。検索語の扱い（漢字を含むなら読みを付ける、など）を分けるのに使う。
 *
 * 判定は `Character.UnicodeScript` と一般カテゴリで行い、コードポイントの範囲を決め打ちしない。
 * 新しい版の Unicode で足された文字（CJK 統合漢字の拡張など）も、JDK の表に従って分類される。
 */
public enum class CharKind {
    /** ひらがな。 */
    HIRAGANA,

    /** カタカナ（半角を含む）。 */
    KATAKANA,

    /**
     * かなに付く記号: 長音「ー」と濁点・半濁点（結合文字と単独の字）。
     *
     * Unicode では、ひらがなとカタカナで共用するため用字が `COMMON`・`INHERITED` になっている。
     * かなの一部として扱えるように独立させる。
     */
    KANA_MARK,

    /** 漢字（「々」「〇」を含む）。 */
    KANJI,

    /** ラテン文字（ローマ字・英字。アクセント付きを含む）。 */
    LATIN,

    /** 数字（全角・アラビア数字以外の 10 進の数字を含む）。 */
    DIGIT,

    /** 上のいずれでもない文字（記号・空白・ほかの用字）。 */
    OTHER,

    ;

    /** かな（ひらがな・カタカナ・かなの記号）か。 */
    public val isKana: Boolean get() = this == HIRAGANA || this == KATAKANA || this == KANA_MARK

    /** 種類の判定。 */
    public companion object {
        /** コードポイント [codePoint] の種類を返す。 */
        public fun of(codePoint: Int): CharKind {
            if (codePoint in KANA_MARKS) return KANA_MARK
            if (Character.isDigit(codePoint)) return DIGIT
            return when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HIRAGANA -> HIRAGANA
                Character.UnicodeScript.KATAKANA -> KATAKANA
                Character.UnicodeScript.HAN -> KANJI
                Character.UnicodeScript.LATIN -> if (Character.isLetter(codePoint)) LATIN else OTHER
                else -> OTHER
            }
        }

        // 長音（全角 U+30FC・半角 U+FF70）、濁点・半濁点（結合文字 U+3099・U+309A、単独 U+309B・U+309C、半角 U+FF9E・U+FF9F）。
        // 用字で決まらない例外は 8 字だけなので、範囲ではなく字で挙げる
        private val KANA_MARKS = intArrayOf(0x30FC, 0xFF70, 0x3099, 0x309A, 0x309B, 0x309C, 0xFF9E, 0xFF9F)
    }
}

/** 漢字を 1 字でも含むか。含むなら、IME で変換を確定した検索語として読みを付ける。 */
public fun CharSequence.containsKanji(): Boolean = anyCodePoint { CharKind.of(it) == CharKind.KANJI }

/** かな（ひらがな・カタカナ・長音など）を 1 字でも含むか。 */
public fun CharSequence.containsKana(): Boolean = anyCodePoint { CharKind.of(it).isKana }

/** ラテン文字を 1 字でも含むか。 */
public fun CharSequence.containsLatin(): Boolean = anyCodePoint { CharKind.of(it) == CharKind.LATIN }

/** 含まれる文字の種類の集合。空の文字列なら空の集合。 */
public fun CharSequence.charKinds(): Set<CharKind> {
    val kinds = java.util.EnumSet.noneOf(CharKind::class.java)
    forEachCodePoint { kinds.add(CharKind.of(it)) }
    return kinds
}

private inline fun CharSequence.anyCodePoint(predicate: (Int) -> Boolean): Boolean {
    var i = 0
    while (i < length) {
        val cp = Character.codePointAt(this, i)
        if (predicate(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

private inline fun CharSequence.forEachCodePoint(action: (Int) -> Unit) {
    var i = 0
    while (i < length) {
        val cp = Character.codePointAt(this, i)
        action(cp)
        i += Character.charCount(cp)
    }
}
