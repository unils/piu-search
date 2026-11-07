// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaunchHistoryTest {
    @Test
    fun `起動を記録すると回数が増え、最後の時刻が進む`() {
        val history = LaunchHistory.EMPTY.recorded(MAPS, at = 100).recorded(MAPS, at = 300)
        assertEquals(LaunchRecord(count = 2, lastLaunchedAt = 300), history.records[MAPS])
        // 時計が戻っても、最後の時刻は戻さない
        assertEquals(LaunchRecord(count = 3, lastLaunchedAt = 300), history.recorded(MAPS, at = 200).records[MAPS])
        // 元のものは変わらない
        assertEquals(LaunchRecord(count = 1, lastLaunchedAt = 100), LaunchHistory.EMPTY.recorded(MAPS, at = 100).records[MAPS])
    }

    @Test
    fun `加点は回数と新しさで決まり、回数の分には上限がある`() {
        val now = 30 * DAY
        val history =
            LaunchHistory(
                mapOf(
                    MAPS to LaunchRecord(1, now - HOUR),
                    TRANSIT to LaunchRecord(2, now - 3 * DAY),
                    MAIL to LaunchRecord(100, now - 20 * DAY),
                ),
            )
        val perLaunch = LaunchHistory.USAGE_POINTS_PER_LAUNCH
        assertEquals(perLaunch + LaunchHistory.RECENT_DAY_POINTS, history.usageBoost(MAPS, now))
        assertEquals(2 * perLaunch + LaunchHistory.RECENT_WEEK_POINTS, history.usageBoost(TRANSIT, now))
        assertEquals(LaunchHistory.USAGE_MAX_COUNTED_LAUNCHES * perLaunch, history.usageBoost(MAIL, now))
        assertEquals(0, history.usageBoost(GAME, now))
        assertEquals(0, history.asRankingSignals(now).learnedBoost(SearchQuery.of("x"), MAPS))
    }

    @Test
    fun `起動の回数で同じ段階の中の順位が変わる`() {
        // 「のりかえ」で、2 つとも読みの前方一致（段階 2）。記録がなければ表示名の順
        val index = indexOf("のりかえ案内" to TRANSIT, "のりかえマップ" to MAPS)
        val query = SearchQuery.of("のりかえ")
        assertEquals(listOf("のりかえマップ", "のりかえ案内"), SearchEngine(index).search(query).map { it.label })

        val history = LaunchHistory.EMPTY.recorded(TRANSIT, at = NOW)
        val results = SearchEngine(index, signals = history.asRankingSignals(NOW)).search(query)
        assertEquals(listOf("のりかえ案内", "のりかえマップ"), results.map { it.label })
        assertEquals(results.map { it.reason.stage }.distinct(), listOf(MatchStage.PREFIX))
    }

    @Test
    fun `起動の回数は段階を越えない`() {
        // 「ちず」: 「ちず」（段階 1）は、何度も開いた「ちずのほん」（段階 2）より上のまま
        val index = indexOf("ちず" to MAPS, "ちずのほん" to TRANSIT)
        var history = LaunchHistory.EMPTY
        repeat(50) { history = history.recorded(TRANSIT, at = NOW) }
        val results = SearchEngine(index, signals = history.asRankingSignals(NOW)).search(SearchQuery.of("ちず"))
        assertEquals(listOf("ちず", "ちずのほん"), results.map { it.label })
    }

    @Test
    fun `空の検索語の一覧は最近とよく使うアプリを分けて、索引にあるものだけを出す`() {
        val index = indexOf("地図" to MAPS, "乗換" to TRANSIT, "メール" to MAIL, "将棋" to GAME)
        val history =
            LaunchHistory(
                mapOf(
                    MAPS to LaunchRecord(1, 500),
                    TRANSIT to LaunchRecord(9, 100),
                    MAIL to LaunchRecord(3, 400),
                    GAME to LaunchRecord(3, 300),
                    // 消えたアプリの記録は残っていても出さない
                    AppId("gone", "a", 0) to LaunchRecord(50, 900),
                ),
            )
        val suggestions = history.suggestions(index, recentLimit = 2, frequentLimit = 5)
        assertEquals(listOf("地図", "メール"), suggestions.recent.map { it.label })
        // 回数の多い順。同じ回数なら新しいほう
        assertEquals(listOf("乗換", "将棋"), suggestions.frequent.map { it.label })
        assertFalse(suggestions.isEmpty)
        assertTrue(LaunchHistory.EMPTY.suggestions(index).isEmpty)
    }

    private fun indexOf(vararg apps: Pair<String, AppId>): SearchIndex =
        SearchIndex.build(apps.map { (label, id) -> AppEntry(id, label) }, TestAnalyzers.generator)

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
        const val NOW = 1_000L * DAY
        val MAPS = AppId("io.example.maps", "Main", 0)
        val TRANSIT = AppId("io.example.transit", "Main", 0)
        val MAIL = AppId("io.example.mail", "Main", 0)
        val GAME = AppId("io.example.game", "Main", 0)
    }
}
