// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.tools.dict

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class BrandTableValidatorTest {
    private fun problems(vararg lines: String): List<Problem> = BrandTableValidator.validate(lines.toList())

    @Test
    fun `正しい表には問題がない`() {
        assertEquals(
            emptyList<Problem>(),
            problems("# 見出し", "", "yahoo\tやふー\t", "x\tえっくす,ついったー\t旧 Twitter", "qoo10\tきゅーてん\t", "pokémon\tぽけもん\t"),
        )
    }

    // 区切りは | で書き、タブに置き換える（CSV の中にタブを書けないため）
    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "yahoo|やふー; 列が 2 個",
            "yahoo|やふー|メモ|余り; 列が 4 個",
            "Yahoo|やふー|; 正規化されていない",
            "yahoo!|やふー|; 正規化されていない",
            "ｙａｈｏｏ|やふー|; 正規化されていない",
            "|やふー|; 綴りが空",
            "123|いちにさん|; 英字がない",
            "yahoo天気|やふーてんき|; 英字と数字のほかの字",
            "yahoo||; 空の読み",
            "yahoo|やふー,|; 空の読み",
            "yahoo|ヤフー|; ひらがなと「ー」のほか",
            "yahoo|やふ〜|; ひらがなと「ー」のほか",
            "yahoo|やふー |; ひらがなと「ー」のほか",
            "yahoo|yahoo|; ひらがなと「ー」のほか",
            "yahoo|やふー,やふー|; 同じ行で重なる",
        ],
    )
    fun `壊れた行を見つける`(
        line: String,
        expected: String,
    ) {
        val found = problems(line.replace('|', '\t'))
        assertEquals(1, found.size, found.toString())
        assertEquals(1, found.single().line)
        assertTrue(expected in found.single().message, found.single().message)
    }

    @Test
    fun `結合文字の濁点を拒む`() {
        // か + U+3099 は NFKC で が になる形。表には合成済みの字だけを書く
        assertTrue(problems("google\tぐーぐる\t").isNotEmpty())
    }

    @Test
    fun `綴りの重複を後の行で報告する`() {
        val found = problems("# 見出し", "line\tらいん\t", "yahoo\tやふー\t", "line\tらいん\t")
        assertEquals(listOf(Problem(4, "綴り「line」が 2 行目と重なる")), found)
        assertEquals("4 行目: 綴り「line」が 2 行目と重なる", found.single().toString())
    }

    @Test
    fun `同梱の表には問題がない`() {
        val table = File(checkNotNull(System.getProperty("brandTable")) { "build.gradle.kts が brandTable を渡す" })
        assertEquals(emptyList<Problem>(), BrandTableValidator.validate(table.readLines()))
    }

    @Test
    fun `入口は問題がなければ報告を書いて 0 を返す`(
        @TempDir dir: File,
    ) {
        val table = File(dir, "brands.tsv").apply { writeText("# 見出し\nyahoo\tやふー\t\n") }
        val report = File(dir, "out/report.txt")
        val err = ByteArrayOutputStream()
        assertEquals(0, run(arrayOf(table.path, report.path), PrintStream(err)))
        assertEquals("${table.path}: 1 語、問題なし\n", report.readText())
        assertEquals("", err.toString())
    }

    @Test
    fun `入口は問題を並べて 1 を返し、報告を書かない`(
        @TempDir dir: File,
    ) {
        val table = File(dir, "brands.tsv").apply { writeText("Yahoo\tやふー\t\nline\tらいん\n") }
        val report = File(dir, "report.txt")
        val err = ByteArrayOutputStream()
        assertEquals(1, run(arrayOf(table.path, report.path), PrintStream(err, true, Charsets.UTF_8)))
        val message = err.toString(Charsets.UTF_8)
        assertTrue("2 個の問題" in message, message)
        assertTrue("1 行目" in message && "2 行目" in message, message)
        assertTrue(!report.exists())
    }

    @Test
    fun `入口は引数の数が違えば 2 を返す`() {
        assertEquals(2, run(arrayOf("only-one"), PrintStream(ByteArrayOutputStream())))
    }
}
