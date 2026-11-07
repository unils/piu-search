// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.NoReadingAnalyzer
import jp.piu.search.core.reading.ReadingToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.text.Normalizer

class NameWordsTest {
    /**
     * 期待値は語を空白でつないだもの。語の種類を頭に付ける: J かな・漢字、L 英字、O 数字・ほかの用字。
     * かな・漢字の語の読みは `/` の後に書く（形態素解析器の読みがない語は読みなし）。
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '\t',
        textBlock = """
Yahoo!天気	LYahoo J天気/てんき
Wi-Fi	LWi LFi
F-Droid	LF LDroid
Uber Eats	LUber LEats
McDonald's	LMcDonald's
McDonald’s	LMcDonald’s
Rock 'n' Roll	LRock Ln LRoll
au PAY	Lau LPAY
U-NEXT	LU LNEXT
モバイルSuica	Jモバイル/もばいる LSuica
d払い	Ld J払い/はらい
NHKプラス	LNHK Jプラス/ぷらす
Google マップ	LGoogle Jマップ/まっぷ
ジョルダン乗換案内	Jジョルダン/じょるだん J乗換/のりかえ J案内/あんない
ＰａｙＰａｙ銀行	LPayPay J銀行/ぎんこう
LINE2	LLINE O2
123円	O123 J円/えん
𠮷野家	J𠮷 J野家/のや
카카오톡 KakaoTalk	O카카오톡 LKakaoTalk
Ⅻ時計	LXII J時計/とけい
""",
    )
    fun `名前を語に分ける`(
        name: String,
        expected: String,
    ) {
        assertEquals(expected, describe(name, analyzer.analyze(nfkc(name))))
    }

    @Test
    fun `読みのない解析器でも文字の種類で語に分ける`() {
        val name = "Yahoo!天気 Wi-Fi"
        assertEquals("LYahoo J天気 LWi LFi", describe(name, NoReadingAnalyzer.analyze(name)))
    }

    @Test
    fun `英字の語に読みを付ける解析器でも、英字の語は自前で分ける`() {
        // ReadingAnalyzer の約束に反する実装でも、英字の語の読みはブランド表に任せる
        val tokens = listOf(ReadingToken("Wi-Fi", "わいふぁい", 0))
        assertEquals("LWi LFi", describe("Wi-Fi", tokens))
    }

    @Test
    fun `「+」は 1 字で 1 語にする`() {
        // 全角の＋も NFKC で + になる
        assertEquals("LDisney P+", describe("Disney+", emptyList()))
        assertEquals("LApple LTV P+", describe("Apple TV＋", emptyList()))
    }

    @Test
    fun `結合文字は前の字と同じ語に入れる`() {
        // 異体字セレクタ（U+E0100）付きの漢字。読みのない解析器で、記号の扱いにしないことを確かめる
        val name = "葛󠄀城"
        assertEquals("J$name", describe(name, emptyList()))
    }

    private fun describe(
        name: String,
        tokens: List<ReadingToken>,
    ): String =
        splitNameWords(nfkc(name), tokens).joinToString(" ") { word ->
            val prefix =
                when (word.kind) {
                    WordKind.JAPANESE -> "J"
                    WordKind.LATIN -> "L"
                    WordKind.OTHER -> "O"
                    WordKind.PLUS -> "P"
                }
            prefix + word.surface + (word.reading?.let { "/$it" } ?: "")
        }

    private fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    private companion object {
        val analyzer = TestAnalyzers.kuromoji
    }
}
