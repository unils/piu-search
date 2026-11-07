// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.TextNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BrandTableTest {
    private val table = BrandTable.loadDefault()

    private fun readingsOf(spelling: String): List<String> = table.readings(TextNormalizer.strict(spelling)).map { it.value }

    @Test
    fun `同梱の表は 200 語以上`() {
        assertTrue(table.size >= 200, "語数 ${table.size}")
    }

    @Test
    fun `同梱の表から設計の例が引ける`() {
        assertEquals(listOf("やふー"), readingsOf("Yahoo"))
        assertEquals(listOf("らいん"), readingsOf("LINE"))
        assertEquals(listOf("えっくす", "ついったー"), readingsOf("X"))
        assertEquals(listOf("じゃる"), readingsOf("JAL"))
    }

    @Test
    fun `表にない綴りは空`() {
        assertEquals(emptyList<NormalizedText>(), table.readings(NormalizedText("nhk")))
        assertEquals(emptyList<NormalizedText>(), table.readings(NormalizedText("")))
    }

    @Test
    fun `コメントと空の行を飛ばし、複数の読みを順に持つ`() {
        val parsed =
            BrandTable.parse(
                listOf(
                    "# 見出し",
                    "",
                    "x\tえっくす,ついったー\t旧 Twitter",
                    "yahoo\tやふー\t",
                ),
            )
        assertEquals(2, parsed.size)
        assertEquals(listOf(NormalizedText("えっくす"), NormalizedText("ついったー")), parsed.readings(NormalizedText("x")))
    }

    @Test
    fun `綴りは正規化してから鍵にする`() {
        val parsed = BrandTable.parse(listOf("Ｙａｈｏｏ!\tやふー\t"))
        assertEquals(listOf(NormalizedText("やふー")), parsed.readings(NormalizedText("yahoo")))
    }

    @Test
    fun `同じ綴りが 2 回出たら読みを重ねずに足す`() {
        val parsed = BrandTable.parse(listOf("jr\tじぇいあーる\t", "jr\tじぇいあーる,じぇーあーる\t"))
        assertEquals(1, parsed.size)
        assertEquals(listOf("じぇいあーる", "じぇーあーる"), parsed.readings(NormalizedText("jr")).map { it.value })
    }

    @Test
    fun `書式の崩れた行は行の番号つきで拒む`() {
        val columns = assertThrows<IllegalArgumentException> { BrandTable.parse(listOf("# 見出し", "yahoo\tやふー")) }
        assertTrue(columns.message!!.startsWith("2 行目"), columns.message)
        assertThrows<IllegalArgumentException> { BrandTable.parse(listOf("!!\tやふー\t")) }
        assertThrows<IllegalArgumentException> { BrandTable.parse(listOf("yahoo\t,\t")) }
    }
}
