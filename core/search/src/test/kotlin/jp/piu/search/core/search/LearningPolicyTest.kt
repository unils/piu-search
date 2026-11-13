// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** learning.md の「例」の表と、R1・R2 の条件。時刻は秒で書き、[s] でミリ秒にする。 */
class LearningPolicyTest {
    private val policy = LearningPolicy()

    @Test
    fun `64 コードポイントを超える語は選択にも救済にも判定しない`() {
        for (word in listOf("あ", "𠮷")) {
            val longQuery = word.repeat(65)
            val decision = policy.decide(listOf(typed(0, longQuery), opened(1, PAYPAY, LaunchRoute.SEARCH_RESULT, longQuery)))
            assertEquals(LearningDecision.NONE, decision)
        }
    }

    // learning.md の「例」の表を 1 行ずつ

    @Test
    fun `例1 ちず → Google マップを開くと選択だけ`() {
        val decision =
            policy.decide(
                listOf(
                    typed(0, "ち", OTHER),
                    typed(1, "ちず", MAPS, OTHER),
                    opened(2, MAPS, LaunchRoute.SEARCH_RESULT, "ちず"),
                ),
            )
        assertEquals(LearningDecision(pair("ちず", MAPS), emptyList()), decision)
    }

    @Test
    fun `例2 おかね → 何も出ない → 消して paypay → PayPay は救済と選択`() {
        val decision =
            policy.decide(
                listOf(
                    typed(0, "お", OTHER),
                    typed(1, "おか", OTHER),
                    typed(2, "おかね"),
                    typed(4, "おか", OTHER),
                    typed(5, "お", OTHER),
                    typed(6, ""),
                    typed(7, "p", OTHER),
                    typed(8, "pay", PAYPAY),
                    typed(9, "paypay", PAYPAY),
                    opened(10, PAYPAY, LaunchRoute.SEARCH_RESULT, "paypay"),
                ),
            )
        assertEquals(LearningDecision(pair("paypay", PAYPAY), listOf(pair("おかね", PAYPAY))), decision)
    }

    @Test
    fun `例3 やふ → やふー → Yahoo!天気は同じ試行なので選択だけ`() {
        val decision =
            policy.decide(
                listOf(
                    // 「やふ」の時点では見えていなくても、代表の「やふー」で見えていたので救済にしない
                    typed(0, "やふ", OTHER),
                    typed(1, "やふー", WEATHER, OTHER),
                    opened(2, WEATHER, LaunchRoute.SEARCH_RESULT, "やふー"),
                ),
            )
        assertEquals(LearningDecision(pair("やふー", WEATHER), emptyList()), decision)
    }

    @Test
    fun `例4 てんき → 8 番目をスクロールして開くと救済と選択`() {
        val decision =
            policy.decide(
                listOf(
                    typed(0, "てんき", *FIVE_OTHERS),
                    SessionEvent.Scrolled(s(3)),
                    opened(5, WEATHER, LaunchRoute.SEARCH_RESULT, "てんき"),
                ),
            )
        assertEquals(LearningDecision(pair("てんき", WEATHER), listOf(pair("てんき", WEATHER))), decision)
    }

    @Test
    fun `例5 ぎんこう → すべてのアプリ → ゆうちょは救済だけ`() {
        val decision = policy.decide(ginkoSession())
        assertEquals(LearningDecision(null, listOf(pair("ぎんこう", YUCHO))), decision)
    }

    @Test
    fun `例6 ぎんこう → 救済の知らせで取り消すと何も記録しない`() {
        val decision = policy.decide(ginkoSession()).withoutRescue(pair("ぎんこう", YUCHO))
        assertTrue(decision.isEmpty)
        assertEquals(LearnedMemory.EMPTY.records, LearnedMemory.EMPTY.recorded(decision, s(10)).records)
    }

    @Test
    fun `例7 ぐ → 画面を閉じると何もない`() {
        val decision = policy.decide(listOf(typed(0, "ぐ", OTHER), SessionEvent.Closed(s(2))))
        assertEquals(LearningDecision.NONE, decision)
    }

    // R2 の条件

    @Test
    fun `試行の終わりから 60 秒を過ぎたら救済にしない`() {
        fun session(openAt: Int) = listOf(typed(0, "おかね"), typed(5, ""), opened(openAt, PAYPAY, LaunchRoute.SUGGESTION, ""))
        assertEquals(listOf(pair("おかね", PAYPAY)), policy.decide(session(60)).rescues)
        assertEquals(emptyList<LearnedPair>(), policy.decide(session(61)).rescues)
    }

    @Test
    fun `試行の終わりは最後の入力で、一覧を眺めていた時間を数える`() {
        val decision =
            policy.decide(
                listOf(typed(0, "ぎんこう"), SessionEvent.AllAppsShown(s(1)), opened(61, YUCHO, LaunchRoute.ALL_APPS, "ぎんこう")),
            )
        assertEquals(LearningDecision.NONE, decision)
    }

    @Test
    fun `1 字の検索語は救済にしない`() {
        assertEquals(LearningDecision.NONE, policy.decide(listOf(typed(0, "ぎ"), opened(2, YUCHO, LaunchRoute.ALL_APPS, "ぎ"))))
        assertEquals(LearningDecision.NONE, policy.decide(listOf(typed(0, "g"), opened(2, YUCHO, LaunchRoute.ALL_APPS, "g"))))
        assertEquals(listOf(pair("go", YUCHO)), policy.decide(listOf(typed(0, "go"), opened(2, YUCHO, LaunchRoute.ALL_APPS, "go"))).rescues)
    }

    @Test
    fun `前の試行が開いたときの検索語の前の部分なら救済にしない`() {
        // 「やふー」を消してから「やふーてんき」と打ち直した: 打ち直しの途中にすぎない
        val decision =
            policy.decide(
                listOf(
                    typed(0, "やふー"),
                    typed(1, ""),
                    typed(2, "やふーてんき", WEATHER),
                    opened(3, WEATHER, LaunchRoute.SEARCH_RESULT, "やふーてんき"),
                ),
            )
        assertEquals(LearningDecision(pair("やふーてんき", WEATHER), emptyList()), decision)
    }

    @Test
    fun `前の試行の代表に戻る途中は同じ試行で、続きでなくなったら次の試行`() {
        // 「やふー」→「やふ」→「y」: 「やふ」は同じ試行、「y」から次の試行（learning.md の「言葉の定義」）
        val decision =
            policy.decide(
                listOf(
                    typed(0, "や"),
                    typed(1, "やふ"),
                    typed(2, "やふー"),
                    typed(3, "やふ"),
                    typed(4, "y"),
                    typed(5, "ya", WEATHER),
                    opened(6, WEATHER, LaunchRoute.SEARCH_RESULT, "ya"),
                ),
            )
        assertEquals(LearningDecision(pair("ya", WEATHER), listOf(pair("やふー", WEATHER))), decision)
    }

    @Test
    fun `空の検索語の一覧から開いたら、前の試行が救済になり選択はない`() {
        val decision = policy.decide(listOf(typed(0, "おかね"), typed(2, ""), opened(4, PAYPAY, LaunchRoute.SUGGESTION, "")))
        assertEquals(LearningDecision(null, listOf(pair("おかね", PAYPAY))), decision)
    }

    @Test
    fun `同じ検索語で結果が出し直されたら後の結果で見えていたかを決める`() {
        // 漢字の検索語は、表記のままの結果の後に、読みを付けた結果が出る
        val decision =
            policy.decide(
                listOf(
                    typed(0, "銀行"),
                    typed(1, "銀行", YUCHO),
                    opened(2, YUCHO, LaunchRoute.SEARCH_RESULT, "銀行"),
                ),
            )
        assertEquals(LearningDecision(pair("銀行", YUCHO), emptyList()), decision)
    }

    @Test
    fun `同じ代表の試行が 2 回あっても救済は 1 つ`() {
        val decision =
            policy.decide(
                listOf(
                    typed(0, "おかね"),
                    typed(1, ""),
                    typed(2, "おかね"),
                    opened(3, PAYPAY, LaunchRoute.ALL_APPS, "おかね"),
                ),
            )
        assertEquals(listOf(pair("おかね", PAYPAY)), decision.rescues)
    }

    @Test
    fun `検索語は正規化した厳密形で記録する`() {
        val decision = policy.decide(listOf(typed(0, "ＰａｙＰａｙ", PAYPAY), opened(1, PAYPAY, LaunchRoute.SEARCH_RESULT, "ＰａｙＰａｙ")))
        assertEquals(pair("paypay", PAYPAY), decision.selection)
    }

    @Test
    fun `閉じた後とアプリを開いた後の出来事は見ない`() {
        assertEquals(
            LearningDecision.NONE,
            policy.decide(listOf(typed(0, "おかね"), SessionEvent.Closed(s(1)), opened(2, PAYPAY, LaunchRoute.ALL_APPS, "おかね"))),
        )
        val first =
            policy.decide(
                listOf(
                    typed(0, "ちず", MAPS),
                    opened(1, MAPS, LaunchRoute.SEARCH_RESULT, "ちず"),
                    opened(2, OTHER, LaunchRoute.SEARCH_RESULT, "x"),
                ),
            )
        assertEquals(pair("ちず", MAPS), first.selection)
        assertEquals(LearningDecision.NONE, policy.decide(listOf(typed(0, "ちず"))))
    }

    @Test
    fun `見えていたとみなす件数は画面に収まる件数と下限の大きいほう`() {
        assertEquals(5, LearningConfig.DEFAULT.visibleCount(3))
        assertEquals(8, LearningConfig.DEFAULT.visibleCount(8))
    }

    private fun ginkoSession(): List<SessionEvent> =
        listOf(
            typed(0, "ぎ", OTHER),
            typed(1, "ぎんこう", *FIVE_OTHERS),
            SessionEvent.AllAppsShown(s(3)),
            SessionEvent.Scrolled(s(5)),
            opened(9, YUCHO, LaunchRoute.ALL_APPS, "ぎんこう"),
        )

    private companion object {
        val MAPS = AppId("io.example.maps", "Main", 0)
        val PAYPAY = AppId("io.example.paypay", "Main", 0)
        val WEATHER = AppId("io.example.weather", "Main", 0)
        val YUCHO = AppId("io.example.yucho", "Main", 0)
        val OTHER = AppId("io.example.other", "Main", 0)
        val FIVE_OTHERS = Array(5) { AppId("io.example.other$it", "Main", 0) }

        fun s(seconds: Int): Long = 1_000_000_000L + seconds * 1000L

        fun typed(
            second: Int,
            query: String,
            vararg visible: AppId,
        ) = SessionEvent.QueryChanged(s(second), query, visible.toList())

        fun opened(
            second: Int,
            app: AppId,
            route: LaunchRoute,
            query: String,
        ) = SessionEvent.AppOpened(s(second), app, route, query)

        fun pair(
            query: String,
            app: AppId,
        ) = LearnedPair(NormalizedText(query), app)
    }
}
