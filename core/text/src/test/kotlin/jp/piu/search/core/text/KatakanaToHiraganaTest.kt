// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class KatakanaToHiraganaTest {
    // core:reading の自前の変換（M1-02）のテストを引き継いだ
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '\t',
        textBlock = """
ノリカエアンナイ	のりかえあんない
ヴァイオリン	ゔぁいおりん
ヵヶ	ゕゖ
ヽヾ	ゝゞ
レーダー	れーだー
ヷヸヹヺ	ヷヸヹヺ
ヿ	ヿ
ｶﾒﾗ	ｶﾒﾗ
Suica・NHK	Suica・NHK
ひらがな	ひらがな
""",
    )
    fun `カタカナをひらがなに変え、対応のない字は残す`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, katakanaToHiragana(text))
    }

    @Test
    fun `サロゲートペアを壊さない`() {
        assertEquals("𠮷のや😀", katakanaToHiragana("𠮷ノヤ😀"))
    }

    @Test
    fun `正規化の厳密形と同じ変換になる`() {
        // 厳密形は NFKC の後にこの変換をかける。読みと名前で変換が食い違わないことを確かめる
        val text = "ジョルダンノリカエアンナイヴ"
        assertEquals(TextNormalizer.strict(text).value, katakanaToHiragana(text))
    }
}
