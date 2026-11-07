// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.NoReadingAnalyzer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

// 「すべてのアプリ」の並び（ui.md）。英字 A〜Z → かな・漢字は読みの五十音順 → 数字・記号・ほかの文字
class AppNameOrderTest {
    @Test
    fun `英字・かな・漢字・数字・記号の混ざった名前を並べる`() {
        val labels =
            listOf(
                "乗換案内",
                "Yahoo!天気",
                "カメラ",
                "ガイド",
                "1Password",
                "+メッセージ",
                "LINE",
                "地図",
                "ｇｍａｉｌ",
                "Éclair",
                "★お気に入り",
                "アマゾン",
                "ラーメン",
                "らくらく",
                "Google マップ",
                "123",
                "카카오톡",
                "!!!",
                "ちずぶらり",
                "銀行",
            )

        val sorted = sort(labels)

        assertEquals(
            listOf(
                // 英字（全角・アクセント付きも英字として A〜Z に）
                "Éclair" to "E",
                "ｇｍａｉｌ" to "G",
                "Google マップ" to "G",
                "LINE" to "L",
                "Yahoo!天気" to "Y",
                // かな・漢字（読みの五十音順。頭の記号は飛ばす。濁点は除いて比べる: がいど → かいと < かめら）
                "アマゾン" to "あ",
                "★お気に入り" to "あ",
                "ガイド" to "か",
                "カメラ" to "か",
                "銀行" to "か",
                "地図" to "た",
                "ちずぶらり" to "た",
                "乗換案内" to "な",
                "+メッセージ" to "ま",
                // 長音は前の字の母音（らーめん → らあめん < らくらく）
                "ラーメン" to "ら",
                "らくらく" to "ら",
                // 数字・ほかの文字・記号だけの名前
                "!!!" to "#",
                "123" to "#",
                "1Password" to "#",
                "카카오톡" to "#",
            ),
            sorted,
        )
    }

    @Test
    fun `漢字の名前は読みで並べ、読みのわからない漢字は最後に回す`() {
        // NoReadingAnalyzer では漢字に読みが付かない（読みのキーは表記のまま）
        val noReading = SearchKeyGenerator(NoReadingAnalyzer, LatinWordReader(TestAnalyzers.brands))
        val apps = listOf("地図", "あいう", "Maps").map { IndexedApp(id(it), it, noReading.generate(entry(it))) }
        assertEquals(listOf("Maps" to "M", "あいう" to "あ", "地図" to "#"), AppNameOrder.sort(apps).map { it.app.label to it.section })
    }

    @Test
    fun `表示名が同じなら識別子の順で、毎回同じ順になる`() {
        val work = IndexedApp(AppId("p", "p.Main", 10), "メール", emptyList())
        val personal = IndexedApp(AppId("p", "p.Main", 0), "メール", emptyList())
        assertEquals(listOf(personal, work), AppNameOrder.sort(listOf(work, personal)).map { it.app })
        assertEquals(listOf(personal, work), AppNameOrder.sort(listOf(personal, work)).map { it.app })
    }

    @Test
    fun `行の見出しと五十音の比べ方`() {
        assertEquals(
            listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ", "わ"),
            sort(listOf("あ", "が", "ざ", "づ", "ぬ", "ぱ", "む", "ゃ", "ろ", "ゐ", "ん")).map { it.second },
        )
        assertEquals("かいと", AppNameOrder.gojuonKey("がいど"))
        assertEquals("らあめん", AppNameOrder.gojuonKey("らーめん"))
        assertEquals("うあいおりん", AppNameOrder.gojuonKey("ゔぁいおりん"))
        // 前の字がない・ん の後の長音はそのまま
        assertEquals("ーんー", AppNameOrder.gojuonKey("ーんー"))
    }

    @Test
    fun `プロファイルで絞った索引`() {
        val personal = IndexedApp(AppId("a", "a.Main", 0), "A", emptyList())
        val work = IndexedApp(AppId("b", "b.Main", 10), "B", emptyList())
        val index = SearchIndex(listOf(personal, work))

        assertEquals(listOf(work), index.restrictedTo(setOf(10L)).inNameOrder().map { it.app })
        assertEquals(listOf(personal), index.restrictedTo(setOf(10L), exclude = true).inNameOrder().map { it.app })
        // 絞っても変わらなければ同じものを返す
        assertSame(index, index.restrictedTo(emptySet(), exclude = true))
    }

    private fun sort(labels: List<String>): List<Pair<String, String>> =
        AppNameOrder
            .sort(labels.map { IndexedApp(id(it), it, TestAnalyzers.generator.generate(entry(it))) })
            .map { it.app.label to it.section }

    private fun id(label: String) = AppId("pkg.${label.hashCode()}", "Main", 0)

    private fun entry(label: String) = AppEntry(id(label), label)
}
