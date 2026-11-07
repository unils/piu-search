// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchIndexVersion
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 索引の表の保存と読み込みを、Robolectric の SQLite（本物の SQLite を JVM で動かすもの）で確かめる。
// 「次に開いたとき」を、ファイルのデータベースを閉じて開き直すことで再現する
@RunWith(AndroidJUnit4::class)
class IndexStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<LocalDatabase>()

    @After
    fun closeDatabases() {
        opened.forEach(LocalDatabase::close)
        context.deleteDatabase(FILE)
    }

    private fun open(indexVersion: Int = SearchIndexVersion.CURRENT): LocalDatabase =
        LocalDatabase.open(context, FILE, indexVersion, allowMainThread = true).also(opened::add)

    /** 開き直す前に閉じる。同じファイルを 2 つの接続で開いたままにしない。 */
    private fun reopen(
        database: LocalDatabase,
        indexVersion: Int = SearchIndexVersion.CURRENT,
    ): LocalDatabase {
        database.close()
        opened.remove(database)
        return open(indexVersion)
    }

    @Test
    fun savedIndexGivesTheSameSearchResultsAfterReopening() {
        val original = APPS.map { StoredApp(IndexedApp(it.id, it.label, generator.generate(it)), packageUpdatedAt = 1_000) }
        val database = open()
        database.index.replaceAll(original, STATE)

        val loaded = reopen(database).index.load()

        // キーの文字列・種類・形・語の頭の位置と、キーの順まで同じ
        assertEquals(original, loaded.apps)
        assertEquals(STATE, loaded.state)
        val before = SearchEngine(SearchIndex(original.map { it.app }))
        val after = SearchEngine(loaded.toSearchIndex())
        for (raw in QUERIES) {
            val query = SearchQuery.of(raw).withReading(analyzer)
            val expected = before.search(query)
            assertTrue("「$raw」で何か出る（比べる意味のある検索語）", expected.isNotEmpty())
            assertEquals("「$raw」", expected, after.search(query))
        }
    }

    @Test
    fun indexVersionChangeClearsOnlyTheIndexTables() {
        val database = open(indexVersion = 4)
        database.index.replaceAll(listOf(stored(APPS[0])), STATE)

        val newer = reopen(database, indexVersion = 5)
        val loaded = newer.index.load()

        assertEquals(emptyList<StoredApp>(), loaded.apps)
        assertNull("作り直しが要ることを同期に伝える", loaded.state)
        assertSame(SearchIndex.EMPTY, loaded.toSearchIndex())
        assertEquals(0, newer.count("app"))
        assertEquals(0, newer.count("search_key"))
        assertEquals(0, newer.count("meta"))

        // 古い版に戻しても、消した索引は戻らない（隠したのではなく消した）
        assertEquals(emptyList<StoredApp>(), reopen(newer, indexVersion = 4).index.load().apps)
    }

    @Test
    fun indexIsRebuiltWithTheNewVersionAfterClearing() {
        val database = open(indexVersion = 4)
        database.index.replaceAll(listOf(stored(APPS[0])), STATE)
        val newer = reopen(database, indexVersion = 5)
        newer.index.load()

        newer.index.replaceAll(listOf(stored(APPS[1])), STATE)

        assertEquals(listOf(stored(APPS[1])), reopen(newer, indexVersion = 5).index.load().apps)
    }

    @Test
    fun emptyDatabaseHasNoState() {
        val loaded = open().index.load()
        assertEquals(emptyList<StoredApp>(), loaded.apps)
        assertNull(loaded.state)
    }

    @Test
    fun stateWithUnknownValuesRoundTrips() {
        val database = open()
        val state = SyncState(changeSequence = null, bootCount = null, locales = "en-US")
        database.index.replaceAll(emptyList(), state)

        val loaded = reopen(database).index.load()

        assertEquals(state, loaded.state)
        assertEquals(emptyList<StoredApp>(), loaded.apps)
    }

    @Test
    fun replaceAllDropsAppsNotInTheNewList() {
        val database = open()
        database.index.replaceAll(APPS.map(::stored), STATE)
        database.index.replaceAll(listOf(stored(APPS[2])), STATE.copy(changeSequence = 9))

        val loaded = database.index.load()
        assertEquals(listOf(stored(APPS[2])), loaded.apps)
        assertEquals(9, loaded.state?.changeSequence)
        assertEquals(stored(APPS[2]).app.keys.size, database.count("search_key"))
    }

    @Test
    fun updateReplacesAndRemovesPackagesInOneProfile() {
        val database = open()
        database.index.replaceAll(APPS.map(::stored), STATE)
        val transit = APPS.first { it.id.packageName == TRANSIT }
        val renamed = transit.copy(label = "乗換案内 新版")
        val newState = STATE.copy(changeSequence = 12)

        database.index.update(
            apps = listOf(stored(renamed, updatedAt = 2_000)),
            removedPackages = listOf(PackageKey(PERSONAL, PAYPAY)),
            state = newState,
        )

        val loaded = database.index.load()
        val ids = loaded.apps.map { it.app.id }
        assertEquals(
            renamed.label,
            loaded.apps
                .single { it.app.id == transit.id }
                .app.label,
        )
        assertEquals(2_000L, loaded.apps.single { it.app.id == transit.id }.packageUpdatedAt)
        assertTrue("消したパッケージ", ids.none { it.packageName == PAYPAY && it.profileSerial == PERSONAL })
        assertTrue("別のプロファイルの同じパッケージは残る", ids.any { it.packageName == PAYPAY && it.profileSerial == WORK })
        assertEquals(newState, loaded.state)
        // 検索キーは消した入口と一緒に消え、置き換えた入口のキーだけが入る
        assertEquals(loaded.apps.sumOf { it.app.keys.size }, database.count("search_key"))
    }

    @Test
    fun updateRemovesEveryActivityOfAReplacedPackage() {
        val database = open()
        val second = AppEntry(AppId(TRANSIT, "$TRANSIT.Second", PERSONAL), "乗換案内 時刻表")
        database.index.replaceAll((APPS + second).map(::stored), STATE)

        // 入口が 1 つに減った版に更新した
        val transit = APPS.first { it.id.packageName == TRANSIT && it.id.profileSerial == PERSONAL }
        database.index.update(apps = listOf(stored(transit)), state = STATE)

        val activities =
            database.index
                .load()
                .apps
                .filter { it.app.id.packageName == TRANSIT && it.app.id.profileSerial == PERSONAL }
        assertEquals(listOf(transit.id), activities.map { it.app.id })
    }

    @Test
    fun updateRemovesProfiles() {
        val database = open()
        database.index.replaceAll(APPS.map(::stored), STATE)

        database.index.update(apps = emptyList(), removedProfiles = listOf(WORK), state = STATE)

        assertTrue(
            database.index
                .load()
                .apps
                .all { it.app.id.profileSerial == PERSONAL },
        )
    }

    @Test
    fun updateNeedsAnIndexOfTheCurrentVersion() {
        val database = open(indexVersion = 4)
        database.index.replaceAll(listOf(stored(APPS[0])), STATE)
        val newer = reopen(database, indexVersion = 5)

        assertThrows(IllegalStateException::class.java) {
            newer.index.update(apps = listOf(stored(APPS[1])), state = STATE)
        }
        assertThrows(IllegalStateException::class.java) {
            open(indexVersion = 5).let { it.index.update(apps = emptyList(), state = STATE) }
        }
    }

    private fun stored(
        entry: AppEntry,
        updatedAt: Long = 1_000,
    ): StoredApp = StoredApp(IndexedApp(entry.id, entry.label, generator.generate(entry)), updatedAt)

    private fun LocalDatabase.count(table: String): Int =
        room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    companion object {
        private const val FILE = "index-store-test.db"
        private const val PERSONAL = 0L
        private const val WORK = 11L
        private const val TRANSIT = "jp.co.jorudan.nrkj"
        private const val PAYPAY = "jp.ne.paypay.android.app"

        // 辞書の読み込み（約 170 ms）をテストごとに繰り返さない
        private val analyzer = KuromojiReadingAnalyzer()
        private val generator = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.loadDefault()))

        private val APPS =
            listOf(
                AppEntry(AppId(TRANSIT, "jp.co.jorudan.nrkj.Launcher", PERSONAL), "乗換案内"),
                AppEntry(
                    AppId("com.google.android.apps.maps", "com.google.android.maps.MapsActivity", PERSONAL),
                    "マップ",
                    localizedLabels = listOf("Maps"),
                    genres = listOf(Genres.MAPS),
                ),
                AppEntry(
                    AppId("jp.co.yahoo.android.weather.type1", "jp.co.yahoo.android.weather.Main", PERSONAL),
                    "Yahoo!天気",
                    genres = listOf(Genres.WEATHER),
                ),
                AppEntry(AppId(PAYPAY, "jp.ne.paypay.android.app.Main", PERSONAL), "PayPay"),
                AppEntry(AppId(PAYPAY, "jp.ne.paypay.android.app.Main", WORK), "PayPay"),
                AppEntry(AppId("jp.or.nhk.nhkplus", "jp.or.nhk.nhkplus.Main", PERSONAL), "NHKプラス"),
                // 提供元のキー（証明書の組織名。M6-01）
                AppEntry(
                    AppId("jp.co.mobileit.ispeed_fx", "jp.co.mobileit.ispeed_fx.Main", PERSONAL),
                    "iSPEED FX",
                    signerNames =
                        listOf(
                            "C=JP,ST=Tokyo,L=Shinagawa-ku,O=Rakuten Securities\\, Inc.,OU=Information System,CN=www.rakuten-sec.co.jp",
                        ),
                ),
            )

        // 段階の違う一致（名前・読み・ローマ字・語の頭・別の言語の名前・パッケージ名・ジャンル・提供元）を含める
        private val QUERIES =
            listOf(
                "のりかえ",
                "あんない",
                "norikae",
                "ちず",
                "地図",
                "maps",
                "やふー",
                "てんき",
                "天気",
                "paypay",
                "ぺい",
                "nhk",
                "ぷらす",
                "jorudan",
                "楽天",
                "rakuten",
            )

        private val STATE = SyncState(changeSequence = 3, bootCount = 7, locales = "ja-JP,en-US")
    }
}
