// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 照合の時間（matching.md の性能の目標: アプリ 500 個で、入力 1 回あたり 16 ms を十分に下回る。M5-03 で 3,000 個も）。
 *
 * JVM の上の値で、端末の値ではない（端末での計測は M6）。CI の機械はほかの仕事と同居し、GC や割り込みで 1 回だけ遅くなることがある。
 * 時々落ちないよう、温めて（JIT のコンパイルを済ませて）から検索語ごとに [ROUNDS] 回測り、その中央値で判定する。
 * 中央値は、半分より少ない回の遅れには動かない。判定は、中央値の最も遅い検索語が 16 ms を下回ること。
 * 実測の値は標準出力に書く（`build/test-results/` の XML に残る）。
 */
class SearchEnginePerformanceTest {
    @ParameterizedTest(name = "アプリ {0} 個")
    @ValueSource(ints = [500, 3_000])
    fun `入力 1 回の照合が 16 ms を下回る`(appCount: Int) {
        val entries = manyApps(appCount)
        // 辞書の読み込みを、索引の大きさの見積もりに入れない
        TestAnalyzers.generator.generate(entries.first())
        val usedBefore = usedHeap()
        val index = SearchIndex.build(entries, TestAnalyzers.generator)
        // GC の後の使用量の差。JVM の上の目安で、端末の値ではない
        val indexMb = (usedHeap() - usedBefore) / BYTES_PER_MB
        assertEquals(appCount, index.size)
        val engine = SearchEngine(index)
        // 入力 1 回ごとの処理（検索語の正規化と照合）を測る。漢字の検索語の読みの付与は裏の別の呼び出しなので含めない
        val queries = EvalSet.queries(EvalSet.parse(EvalSet.resource("queries.tsv"))).map { it.query }

        repeat(WARMUP_ROUNDS) { queries.forEach { engine.search(SearchQuery.of(it)) } }
        val samples = Array(queries.size) { LongArray(ROUNDS) }
        repeat(ROUNDS) { round ->
            queries.forEachIndexed { i, raw ->
                val start = System.nanoTime()
                engine.search(SearchQuery.of(raw))
                samples[i][round] = System.nanoTime() - start
            }
        }
        val medians = samples.map { it.sorted()[ROUNDS / 2] / NANOS_PER_MS }
        val slowest = medians.indices.maxBy { medians[it] }
        val all = samples.flatMap { it.asIterable() }.sorted()
        val meanMs = all.average() / NANOS_PER_MS
        val p99Ms = all[(all.size * P99).toInt()] / NANOS_PER_MS
        val keyCount = index.textKeys.size + index.romajiKeys.size
        println(
            (
                "照合の時間: アプリ ${index.size} 個・キー $keyCount 個（索引のヒープ約 %.1f MB）・検索語 ${queries.size} 個 × $ROUNDS 回。" +
                    "平均 %.3f ms、99 パーセンタイル %.3f ms、検索語ごとの中央値の最大 %.3f ms（「%s」）"
            ).format(
                indexMb,
                meanMs,
                p99Ms,
                medians[slowest],
                queries[slowest],
            ),
        )
        assertTrue(medians[slowest] < LIMIT_MS, "「${queries[slowest]}」の中央値 ${medians[slowest]} ms")
    }

    private companion object {
        const val WARMUP_ROUNDS = 3
        const val ROUNDS = 7
        const val LIMIT_MS = 16.0
        const val P99 = 0.99
        const val NANOS_PER_MS = 1_000_000.0
        const val BYTES_PER_MB = 1024.0 * 1024.0

        fun usedHeap(): Long {
            val runtime = Runtime.getRuntime()
            repeat(GC_ROUNDS) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }

        const val GC_ROUNDS = 3

        /** 評価セットのアプリに番号を付けて [count] 個にする。名前とパッケージ名が違うので、キーも別に作られる。 */
        fun manyApps(count: Int): List<AppEntry> {
            val evalApps = EvalSet.apps(EvalSet.parse(EvalSet.resource("apps.tsv")))
            return (0 until count).map { i ->
                val app = evalApps[i % evalApps.size]
                val copy = i / evalApps.size
                val label = if (copy == 0) app.name else "${app.name} ${copy + 1}"
                AppEntry(
                    AppId("${app.packageName}.c$copy", "Main", 0),
                    label,
                    listOfNotNull(app.englishName.ifEmpty { null }),
                    app.genres.map(::GenreId),
                )
            }
        }
    }
}
