// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 異常な場合（M5-03）: アプリが 0 個・数千個・表示名が空や記号だけや極端に長い・同じ名前が多い。
 * キーの生成・照合・名前の順の並べ替えが落ちず、引けるものは引けること。
 */
class ExtremeCasesTest {
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "a", "のりかえ", "乗り換え", "norik", "🙂", "!!!", "ｶﾞｷﾞ"])
    fun `アプリが 0 個でも照合と並べ替えが落ちない`(raw: String) {
        val index = SearchIndex.build(emptyList(), TestAnalyzers.generator)
        assertEquals(0, index.size)
        assertEquals(emptyList<SearchResult>(), SearchEngine(index).search(SearchQuery.of(raw).withReading(TestAnalyzers.kuromoji)))
        assertEquals(emptyList<NameOrderedApp>(), index.inNameOrder())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "　", "!!!", "🙂", "​", "・・・"])
    fun `名前で引けない表示名でも、パッケージ名の語で引け、名前の順に並ぶ`(label: String) {
        val odd = AppEntry(AppId("io.example.nolabel", "Main", 0), label)
        val transit = AppEntry(AppId("io.example.transit", "Main", 0), "乗換案内")
        val index = SearchIndex.build(listOf(odd, transit), TestAnalyzers.generator)
        val engine = SearchEngine(index)

        assertEquals(listOf(odd.id), engine.search(SearchQuery.of("nolabel")).map { it.app })
        assertEquals(listOf(transit.id), engine.search(SearchQuery.of("のりかえ")).map { it.app })
        assertEquals(label, index.labelOf(odd.id))
        assertEquals(setOf(odd.id, transit.id), index.inNameOrder().map { it.app.id }.toSet())
    }

    @Test
    fun `極端に長い表示名でもキーを作り、照合が遅くならない`() {
        val long = AppEntry(AppId("io.example.long", "Main", 0), LONG_LABEL)
        val others = (0 until OTHERS).map { AppEntry(AppId("io.example.app$it", "Main", 0), "テスト $it") }

        val buildStarted = System.nanoTime()
        val index = SearchIndex.build(others + long, TestAnalyzers.generator)
        val buildMillis = (System.nanoTime() - buildStarted) / NANOS_PER_MS
        val engine = SearchEngine(index)
        // 長い名前の頭（名前の前方一致）と途中（読みの語の頭）で引ける
        assertTrue(engine.search(SearchQuery.of("のりかえ")).any { it.app == long.id })
        assertTrue(engine.search(SearchQuery.of("てんき")).any { it.app == long.id })

        // 長いキーと照らしても、入力 1 回の照合は目標（16 ms）を大きく下回る。温めてから中央値で見る
        val queries = listOf("の", "のりかえ", "abcde", "zzzz", "てすと")
        repeat(WARMUP_ROUNDS) { queries.forEach { engine.search(SearchQuery.of(it)) } }
        val times =
            (0 until ROUNDS)
                .map {
                    val start = System.nanoTime()
                    queries.forEach { engine.search(SearchQuery.of(it)) }
                    (System.nanoTime() - start) / NANOS_PER_MS / queries.size
                }.sorted()
        val medianMs = times[ROUNDS / 2]
        println("表示名 ${LONG_LABEL.length} 字: キーの生成 %.1f ms、照合の中央値 %.3f ms".format(buildMillis, medianMs))
        assertTrue(medianMs < LIMIT_MS, "照合の中央値 $medianMs ms")
    }

    @Test
    fun `同じ名前のアプリが多くても、1 つずつ出て、並びが変わらない`() {
        val same = (0 until SAME).map { AppEntry(AppId("io.example.same$it", "Main", 0), "テスト") }
        // 同じパッケージの 2 つの入口と、主と仕事用プロファイルの同じアプリ
        val sameIds =
            listOf(
                AppId("io.example.two", "A", 0),
                AppId("io.example.two", "B", 0),
                AppId("io.example.two", "A", 11),
            )
        val entries = same + sameIds.map { AppEntry(it, "テスト") }
        val index = SearchIndex.build(entries, TestAnalyzers.generator)
        val engine = SearchEngine(index)

        val first = engine.search(SearchQuery.of("てすと")).map { it.app }
        assertEquals(entries.map { it.id }.toSet(), first.toSet())
        assertEquals(entries.size, first.size)
        assertEquals(first, engine.search(SearchQuery.of("てすと")).map { it.app })
        assertEquals(entries.size, index.inNameOrder().size)
    }

    private companion object {
        const val OTHERS = 500
        const val SAME = 200
        const val WARMUP_ROUNDS = 3
        const val ROUNDS = 7
        const val LIMIT_MS = 16.0
        const val NANOS_PER_MS = 1_000_000.0

        // 端末の表示名は長さの上限がない（LauncherActivityInfo.getLabel）。漢字・かな・英字を混ぜて 5,000 字
        val LONG_LABEL = ("乗換案内とYahoo!天気のアプリ" + "あいうえおABCDE漢字").repeat(5_000 / 28 + 1).take(5_000)
    }
}
