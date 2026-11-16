// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.NoReadingAnalyzer
import jp.piu.search.core.reading.ReadingAnalyzer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// キーの生成の例外が、どの入力で起きたかを持つこと（architecture.md の「失敗の分け方」の input_summary のもと）
class KeyGenerationFailureTest {
    private val boom = IllegalStateException()

    // 「爆」を含む文字列で落ちる形態素解析器。表示名・別の言語の名前・DN の組織名は、どれもこれを通る
    private val analyzer =
        ReadingAnalyzer { text ->
            if ("爆" in text) throw boom
            NoReadingAnalyzer.analyze(text)
        }
    private val generator = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList())))
    private val id = AppId("io.example.app", "io.example.app.Main", 0)

    @Test
    fun `表示名の例外は LABEL`() {
        val e = assertThrows<KeyGenerationException> { generator.generate(AppEntry(id, "爆テスト")) }
        assertEquals(KeyInput.LABEL, e.input)
        assertEquals(0, e.index)
        assertSame(boom, e.cause)
    }

    @Test
    fun `別の言語の名前の例外は位置を持つ`() {
        val e =
            assertThrows<KeyGenerationException> {
                generator.generate(AppEntry(id, "テスト", localizedLabels = listOf("Test", "爆テスト")))
            }
        assertEquals(KeyInput.LOCALIZED_LABEL, e.input)
        assertEquals(1, e.index)
    }

    @Test
    fun `DN の例外は署名者の位置を持つ`() {
        val e =
            assertThrows<KeyGenerationException> {
                generator.generate(AppEntry(id, "テスト", signerNames = listOf("O=Example Inc.", "O=爆株式会社")))
            }
        assertEquals(KeyInput.SIGNER, e.input)
        assertEquals(1, e.index)
    }

    @Test
    fun `例外がなければキーは包む前と同じ`() {
        val entry = AppEntry(id, "テスト", localizedLabels = listOf("Test"), signerNames = listOf("O=Example Inc."))
        val plain = SearchKeyGenerator(NoReadingAnalyzer, LatinWordReader(BrandTable.parse(emptyList())))
        assertEquals(plain.generate(entry), generator.generate(entry))
    }
}
