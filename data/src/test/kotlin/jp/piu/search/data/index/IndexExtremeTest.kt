// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchQuery
import jp.piu.search.data.FakeDevice
import jp.piu.search.data.InstalledApp
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 異常な場合（M5-03）: 端末のアプリが 0 個（自分だけ）・数千個・表示名が空や極端に長い・同じ名前が多い。
// 索引の書き手・保存（index.db）・読み戻し・照合が落ちず、保存した中身が読み戻しで変わらないことを、偽の端末と本物のキーの生成で確かめる。
// 自分（このアプリ）は読み手が一覧から除くので、偽の端末に何も入れないことが「自分だけ」に当たる
@RunWith(AndroidJUnit4::class)
class IndexExtremeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<IndexDatabase>()
    private val device = FakeDevice()

    @After
    fun close() {
        opened.forEach(IndexDatabase::close)
        context.deleteDatabase(FILE)
    }

    @Test
    fun noAppsBesidesItself() =
        runTest {
            val actor = actor()
            actor.requestSync().await()
            assertEquals(0, actor.snapshot.value.packages.size)
            assertEquals(emptyList<Any>(), SearchEngine(actor.index()).search(SearchQuery.of("のりかえ")))
            assertEquals(emptyList<Any>(), actor.index().inNameOrder())

            // 空の索引も保存され、次に開いたときに何も作り直さない
            val reopened = reopen(actor)
            reopened.requestSync().await()
            assertEquals(
                0,
                reopened.status.value.lastSync
                    ?.keysBuilt,
            )
            assertEquals(0, reopened.snapshot.value.packages.size)
        }

    @Test
    fun thousandsOfAppsAreIndexedSavedAndReloaded() =
        runTest {
            val apps = (0 until MANY).map { i -> app("io.example.app$i", NAMES[i % NAMES.size] + " $i") }
            device.install(*apps.toTypedArray())

            val started = System.nanoTime()
            val actor = actor()
            actor.requestSync().await()
            val buildMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
            assertEquals(
                MANY,
                actor.status.value.lastSync
                    ?.keysBuilt,
            )

            // 次に開いたとき: 保存した索引を読み、作り直さない
            val loadStarted = System.nanoTime()
            val reopened = reopen(actor)
            reopened.requestSync().await()
            val loadMillis = (System.nanoTime() - loadStarted) / NANOS_PER_MILLI
            assertEquals(
                0,
                reopened.status.value.lastSync
                    ?.keysBuilt,
            )
            assertEquals(MANY, reopened.index().size)
            assertEquals(MANY, reopened.index().inNameOrder().size)
            val hits = SearchEngine(reopened.index()).search(SearchQuery.of("のりかえ"))
            assertEquals(MANY / NAMES.size, hits.count { it.label.startsWith("乗換案内") })
            // Robolectric の上の値で、端末の値ではない。記録として出す
            println("アプリ $MANY 個: 全件の作り直しと保存 $buildMillis ms、読み戻しと同期 $loadMillis ms（Robolectric）")
        }

    @Test
    fun emptyAndVeryLongLabelsSurviveSaving() =
        runTest {
            val empty = app("io.example.nolabel", "")
            val blank = app("io.example.blank", "   ")
            val symbols = app("io.example.symbols", "!!!")
            val long = app("io.example.long", LONG_LABEL)
            device.install(empty, blank, symbols, long)
            val actor = actor()
            actor.requestSync().await()

            val reopened = reopen(actor)
            reopened.requestSync().await()
            val index = reopened.index()
            assertEquals(4, index.size)
            // 表示名は保存の前後で変わらない（切り詰めない）
            assertEquals("", index.labelOf(empty.entry.id))
            assertEquals("   ", index.labelOf(blank.entry.id))
            assertEquals(LONG_LABEL, index.labelOf(long.entry.id))
            // 名前で引けなくても、パッケージ名の語で引ける
            val engine = SearchEngine(index)
            assertEquals(listOf(empty.entry.id), engine.search(SearchQuery.of("nolabel")).map { it.app })
            assertTrue(engine.search(SearchQuery.of("のりかえ")).any { it.app == long.entry.id })
            assertEquals(4, index.inNameOrder().size)
        }

    @Test
    fun manyAppsWithTheSameNameStayDistinct() =
        runTest {
            val same = (0 until SAME).map { i -> app("io.example.same$i", "テスト") }
            // 同じパッケージの 2 つの入口と、主と仕事用プロファイルの同じアプリ
            val twoEntries =
                listOf(
                    InstalledApp(AppEntry(AppId("io.example.two", "io.example.two.A", OWN), "テスト"), 1_000),
                    InstalledApp(AppEntry(AppId("io.example.two", "io.example.two.B", OWN), "テスト"), 1_000),
                    InstalledApp(AppEntry(AppId("io.example.two", "io.example.two.A", WORK), "テスト"), 1_000),
                )
            device.install(*(same + twoEntries).toTypedArray())
            val actor = actor()
            actor.requestSync().await()

            val reopened = reopen(actor)
            reopened.requestSync().await()
            val expected = (same + twoEntries).map { it.entry.id }.toSet()
            val engine = SearchEngine(reopened.index())
            val first = engine.search(SearchQuery.of("てすと")).map { it.app }
            assertEquals(expected, first.toSet())
            assertEquals("同じ名前でも 1 つずつ出る", expected.size, first.size)
            // 同じ点の並びは、呼ぶたびに変わらない
            assertEquals(first, engine.search(SearchQuery.of("てすと")).map { it.app })
            assertEquals(expected.size, reopened.index().inNameOrder().size)
        }

    private fun TestScope.actor(): IndexActor =
        IndexActor(
            backgroundScope,
            IndexDatabase.open(context, FILE, allowMainThread = true).also(opened::add),
            DeviceAppsReader(device, { setOf(OWN, WORK) }),
            GeneratorKeyFactory({}, { SearchKeyGenerator(analyzer, LatinWordReader(brands)) }),
            1,
            StandardTestDispatcher(testScheduler),
        )

    // 前の書き手を止めて DB を閉じ、同じファイルで開き直す（次にアプリを開いたとき）
    private suspend fun TestScope.reopen(previous: IndexActor): IndexActor {
        previous.job.cancelAndJoin()
        opened.forEach(IndexDatabase::close)
        opened.clear()
        return actor()
    }

    private fun IndexActor.index(): SearchIndex = snapshot.value.toSearchIndex()

    private companion object {
        const val FILE = "index-extreme-test.db"
        const val MANY = 3_000
        const val SAME = 200
        const val NANOS_PER_MILLI = 1_000_000L

        // 読みを付ける実物（Kuromoji とブランド表）。辞書の読み込みは 0.2 秒ほどなので、テストの間で共有する
        val analyzer by lazy { KuromojiReadingAnalyzer() }
        val brands by lazy { BrandTable.loadDefault() }

        val NAMES = listOf("乗換案内", "Yahoo!天気", "PayPay", "テスト地図", "メール", "将棋ウォーズ", "Google マップ", "楽天市場")

        // 端末の表示名は長さの上限がない（LauncherActivityInfo.getLabel）。漢字・かな・英字を混ぜて 5,000 字
        val LONG_LABEL = ("乗換案内とYahoo!天気のアプリ" + "あいうえおABCDE漢字").repeat(5_000 / 28 + 1).take(5_000)

        fun app(
            packageName: String,
            label: String,
        ) = InstalledApp(AppEntry(AppId(packageName, "$packageName.Main", OWN), label), packageUpdatedAt = 1_000)
    }
}
