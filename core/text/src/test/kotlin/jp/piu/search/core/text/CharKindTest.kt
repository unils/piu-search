// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CharKindTest {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
# 字 | 種類
あ | HIRAGANA
ゃ | HIRAGANA
ゔ | HIRAGANA
ア | KATAKANA
ｱ | KATAKANA
ヶ | KATAKANA
ー | KANA_MARK
ｰ | KANA_MARK
゛ | KANA_MARK
漢 | KANJI
々 | KANJI
𠮷 | KANJI
a | LATIN
Ｚ | LATIN
é | LATIN
1 | DIGIT
１ | DIGIT
! | OTHER
・ | OTHER
😀 | OTHER
한 | OTHER
""",
    )
    fun `字の種類`(
        ch: String,
        expected: CharKind,
    ) {
        assertEquals(expected, CharKind.of(ch.codePointAt(0)))
    }

    @Test
    fun `結合文字の濁点はかなの記号`() {
        assertEquals(CharKind.KANA_MARK, CharKind.of(0x3099))
    }

    @Test
    fun `漢字を含むか`() {
        assertTrue("乗り換え".containsKanji())
        assertTrue("𠮷野家".containsKanji())
        assertFalse("のりかえ".containsKanji())
        assertFalse("".containsKanji())
    }

    @Test
    fun `かなを含むか`() {
        assertTrue("nhkぷらす".containsKana())
        assertTrue("ー".containsKana())
        assertFalse("nhk".containsKana())
    }

    @Test
    fun `ラテン文字を含むか`() {
        assertTrue("のりk".containsLatin())
        assertFalse("のり1".containsLatin())
    }

    @Test
    fun `含まれる種類の集合`() {
        assertEquals(setOf(CharKind.LATIN, CharKind.KANJI), "yahoo天気".charKinds())
        assertEquals(emptySet<CharKind>(), "".charKinds())
    }

    @Test
    fun `かなかどうか`() {
        assertTrue(CharKind.HIRAGANA.isKana)
        assertTrue(CharKind.KANA_MARK.isKana)
        assertFalse(CharKind.KANJI.isKana)
    }
}
