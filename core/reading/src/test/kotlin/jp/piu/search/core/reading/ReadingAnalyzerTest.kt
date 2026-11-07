// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.reading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadingAnalyzerTest {
    @Test
    fun `NoReadingAnalyzer は全体を読みのない 1 語として返す`() {
        assertEquals(listOf(ReadingToken("乗換案内", null, 0)), NoReadingAnalyzer.analyze("乗換案内"))
    }

    @Test
    fun `NoReadingAnalyzer は空や空白だけの文字列に空の一覧を返す`() {
        assertEquals(emptyList<ReadingToken>(), NoReadingAnalyzer.analyze(""))
        assertEquals(emptyList<ReadingToken>(), NoReadingAnalyzer.analyze(" 　"))
    }

    @Test
    fun `関数で実装を差し替えられる`() {
        val analyzer = ReadingAnalyzer { listOf(ReadingToken(it, "ちず", 0)) }
        assertEquals("ちず", analyzer.analyze("地図").single().reading)
    }

    @Test
    fun `end は start に表記の長さを足した位置`() {
        assertEquals(5, ReadingToken("天気", "てんき", 3).end)
    }
}
