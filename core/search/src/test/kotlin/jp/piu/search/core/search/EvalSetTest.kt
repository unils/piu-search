// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EvalSetTest {
    private val appsTable = EvalSet.parse(EvalSet.resource("apps.tsv"))
    private val queriesTable = EvalSet.parse(EvalSet.resource("queries.tsv"))
    private val appNames = EvalSet.apps(appsTable).map { it.name }.toSet()

    @Test
    fun `apps の表に誤りがない`() {
        assertEquals(emptyList<String>(), EvalSet.checkApps(appsTable))
    }

    @Test
    fun `queries の表に誤りがなく、正解がすべて apps にある`() {
        assertEquals(emptyList<String>(), EvalSet.checkQueries(queriesTable, appNames))
    }

    @Test
    fun `件数がタスクカードの目安を満たす`() {
        assertTrue(appsTable.rows.size >= 150) { "アプリ ${appsTable.rows.size} 件" }
        assertTrue(queriesTable.rows.size >= 300) { "検索語 ${queriesTable.rows.size} 件" }
    }

    @Test
    fun `すべての分類に検索語がある`() {
        val used = EvalSet.queries(queriesTable).map { it.category }.toSet()
        assertEquals(EvalSet.CATEGORIES, used)
    }

    // matching.md・learning.md・testing.md と M1 のタスクカードに出てくる例
    @ParameterizedTest
    @ValueSource(
        strings = [
            "やふー", "やふ", "のりかえ", "乗り換え", "乗換", "norikae", "norikaeanai", "のりか", "のりk", "あんない",
            "hot", "hott", "ほt", "hotpepper", "hotpe", "pepper", "nhk", "んhk", "ちず", "地図", "まっぷ", "gmp",
            "shinkansen", "sinkansen", "tokyo", "toukyou", "ramen", "raamen", "fuji", "huzi",
            "おかね", "paypay", "てんき", "ぎんこう", "じゃる", "ごー", "えーゆーぺい", "わいふぁい", "えふどろいど", "ubereats",
            "go", "pay", "with", "でぃー", "ぷらす", "おん",
        ],
    )
    fun `設計の文書の例が入っている`(query: String) {
        assertTrue(queriesTable.rows.any { it["検索語"] == query }) { "検索語がない: $query" }
    }

    @Test
    fun `T2 の やふー は Yahoo の名を持つアプリすべてを要る`() {
        val yahoo = EvalSet.queries(queriesTable).single { it.query == "やふー" }
        assertEquals(Judge.ALL, yahoo.judge)
        assertEquals(appNames.filter { it.startsWith("Yahoo!") }.toSet(), yahoo.answers.toSet())
    }

    // 以下は検査そのものが誤りを見逃さないことを確かめる
    @Test
    fun `検査は apps の誤りを見つける`() {
        val broken =
            table(
                listOf("表示名", "英語名", "パッケージ名", "ジャンル", "確かめ方", "メモ"),
                listOf("地図", "", "not a package", "map", "play"),
                listOf("地図", "", "com.example.a", "", "どこか"),
                listOf("時計", "", "com.example.b", "", "要確認"),
            )
        val errors = EvalSet.checkApps(broken)
        assertTrue(errors.any { "パッケージ名の形" in it }, errors.toString())
        assertTrue(errors.any { "知らないジャンル: map" in it }, errors.toString())
        assertTrue(errors.any { "確かめ方が不正" in it }, errors.toString())
        assertTrue(errors.any { "要確認の理由" in it }, errors.toString())
        assertTrue(errors.any { "表示名 が重なっている" in it }, errors.toString())
    }

    @Test
    fun `検査は queries の誤りを見つける`() {
        val broken =
            table(
                listOf("検索語", "正解", "判定", "分類", "メモ"),
                listOf("Yahoo", "ない名前", "some", "謎"),
                listOf("ちず", "地図|地図", "any", "ジャンル"),
                listOf("ちず", "地図", "any", "ジャンル"),
            )
        val errors = EvalSet.checkQueries(broken, setOf("地図"))
        assertTrue(errors.any { "大文字" in it }, errors.toString())
        assertTrue(errors.any { "apps.tsv にない" in it }, errors.toString())
        assertTrue(errors.any { "判定が不正" in it }, errors.toString())
        assertTrue(errors.any { "分類が不正" in it }, errors.toString())
        assertTrue(errors.any { "正解が重なっている" in it }, errors.toString())
        assertTrue(errors.any { "同じ検索語と分類" in it }, errors.toString())
    }

    @Test
    fun `既知の弱点の印を読み、理由のない印と学習の分類の印を見つける`() {
        val marked =
            table(
                listOf("検索語", "正解", "判定", "分類", "メモ"),
                listOf("ちず", "地図", "any", "ジャンル", "既知の弱点: 表示名の順で負ける。メモ"),
                listOf("ちず", "地図", "any", "学習", "既知の弱点: 理由"),
                listOf("地図", "地図", "any", "ジャンル", "既知の弱点:"),
            )
        val queries = EvalSet.queries(marked)
        assertEquals("表示名の順で負ける。メモ", queries[0].knownWeakness)
        val errors = EvalSet.checkQueries(marked, setOf("地図"))
        assertTrue(errors.any { "学習の分類には" in it }, errors.toString())
        assertTrue(errors.any { "理由がない" in it }, errors.toString())
    }

    @Test
    fun `列が多い行と、列の名前の違いを見つける`() {
        val extra =
            table(
                listOf("検索語", "正解", "判定", "分類", "メモ"),
                listOf("ちず", "地図", "any", "ジャンル", "メモ", "余分"),
            )
        assertTrue(EvalSet.checkQueries(extra, setOf("地図")).any { "列が多い" in it })
        assertEquals(1, EvalSet.checkQueries(table(listOf("検索語", "正解")), emptySet()).size)
    }

    @Test
    fun `行末の空の列は省いてよく、注釈と空行は読み飛ばす`() {
        val parsed = EvalSet.parse("# 注釈\n\n表示名\t英語名\t確かめ方\n地図\n")
        assertEquals("", parsed.rows.single()["英語名"])
        assertEquals(4, parsed.rows.single().line)
    }

    private fun table(vararg rows: List<String>): TsvTable = EvalSet.parse(rows.joinToString("\n") { it.joinToString("\t") })
}
