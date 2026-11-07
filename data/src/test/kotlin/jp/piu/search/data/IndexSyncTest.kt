// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 同期の判断（全件の作り直し・全件の見直し・変更の番号による差分・一覧との突き合わせ）を、偽の端末で確かめる。
// 保存は Robolectric の SQLite（ファイル）。「次に画面を開いたとき」を、同じファイルで新しい IndexSync を作ることで再現する
@RunWith(AndroidJUnit4::class)
class IndexSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<LocalDatabase>()
    private val device = FakeDevice()
    private val analyzer = CountingAnalyzer()

    @After
    fun closeDatabases() {
        opened.forEach(LocalDatabase::close)
        context.deleteDatabase(FILE)
    }

    private fun newSync(indexVersion: Int = 4): IndexSync {
        val database = LocalDatabase.open(context, FILE, indexVersion, allowMainThread = true).also(opened::add)
        val generator = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList())))
        return IndexSync(database.index, device, { generator }, analyzer, launcherApps = null)
    }

    private fun IndexSync.labels(): List<String> = index.labels()

    private fun SearchIndex.labels(): List<String> =
        SearchEngine(this)
            .search(SearchQuery.of("テスト"))
            .map { it.label }
            .sorted()

    @Test
    fun firstSyncBuildsEverythingAndSavesIt() {
        device.install(TRANSIT, MAPS)
        val sync = newSync()
        val published = ArrayList<SearchIndex>()
        sync.addListener { published += it }

        val report = sync.sync()

        assertEquals(SyncMode.FULL_REBUILD, report.mode)
        assertEquals(2, report.rebuiltApps)
        assertEquals(listOf("乗換テスト", "地図テスト"), sync.labels())
        // 保存した索引を読んだとき（空）と、作り直したとき
        assertEquals(2, published.size)
        assertSame(sync.index, published.last())

        // 次に開いたとき: 保存した索引を読み、変わったものがなければ作り直さない（形態素解析器を使わない）
        analyzer.calls = 0
        val reopened = newSync()
        val again = reopened.sync()
        assertEquals(SyncMode.CHANGES, again.mode)
        assertEquals(0, again.rebuiltApps)
        assertEquals(0, analyzer.calls)
        assertEquals(listOf("乗換テスト", "地図テスト"), reopened.labels())
    }

    @Test
    fun changedPackageIsRebuiltAlone() {
        device.install(TRANSIT, MAPS)
        val sync = newSync()
        sync.sync()

        device.install(TRANSIT.copy(label = "乗換テスト2"))
        val report = sync.sync()

        assertEquals(SyncMode.CHANGES, report.mode)
        assertEquals(1, report.rebuiltApps)
        assertEquals(listOf("乗換テスト2", "地図テスト"), sync.labels())
        assertEquals(listOf("乗換テスト2", "地図テスト"), newSync().also { it.sync() }.labels())
    }

    @Test
    fun installedAndRemovedPackagesAreReflected() {
        device.install(TRANSIT, MAPS)
        val sync = newSync()
        sync.sync()

        device.uninstall(MAPS.entry.id.packageName)
        device.install(MAIL)
        val report = sync.sync()

        assertEquals(1, report.rebuiltApps)
        assertEquals(1, report.removedPackages)
        assertEquals(listOf("メールテスト", "乗換テスト"), sync.labels())
    }

    @Test
    fun listedChangesAreFoundEvenIfTheSequenceMissesThem() {
        // 仕事用プロファイルの変更と、可視性の制限で番号に出ない削除
        device.install(TRANSIT, MAPS, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()

        device.uninstall(MAPS.entry.id.packageName, silently = true)
        device.uninstall(WORK_TRANSIT.entry.id.packageName, profile = WORK, silently = true)
        device.install(WORK_MAIL, silently = true)
        val report = sync.sync()

        assertEquals(SyncMode.CHANGES, report.mode)
        assertEquals(listOf("メールテスト", "乗換テスト"), sync.labels())
        assertEquals(setOf(PERSONAL, WORK), sync.index.profiles())
    }

    @Test
    fun bootCountChangeReviewsUpdateTimes() {
        device.install(TRANSIT, MAPS)
        val sync = newSync()
        sync.sync()

        // 再起動の間に更新された（番号は戻るので、番号では分からない）
        device.install(TRANSIT.copy(label = "乗換テスト2", updatedAt = 2_000), silently = true)
        device.reboot()
        val report = sync.sync()

        assertEquals(SyncMode.FULL_REVIEW, report.mode)
        assertEquals(1, report.rebuiltApps)
        assertEquals(listOf("乗換テスト2", "地図テスト"), sync.labels())
        val next = sync.sync()
        assertEquals(SyncMode.CHANGES, next.mode)
        assertEquals(0, next.rebuiltApps)
    }

    @Test
    fun withoutBootCountEverySyncIsAReview() {
        device.bootCount = null
        device.install(TRANSIT)
        val sync = newSync()
        sync.sync()

        val report = sync.sync()

        assertEquals(SyncMode.FULL_REVIEW, report.mode)
        assertEquals(0, report.rebuiltApps)
    }

    @Test
    fun localeChangeRebuildsEverything() {
        device.install(TRANSIT, MAPS)
        val sync = newSync()
        sync.sync()

        device.locales = "en-US"
        val report = sync.sync()

        assertEquals(SyncMode.FULL_REBUILD, report.mode)
        assertEquals(2, report.rebuiltApps)
    }

    @Test
    fun indexVersionChangeRebuildsEverything() {
        device.install(TRANSIT, MAPS)
        newSync(indexVersion = 4).sync()
        opened.forEach(LocalDatabase::close)
        opened.clear()

        val report = newSync(indexVersion = 5).sync()

        assertEquals(SyncMode.FULL_REBUILD, report.mode)
        assertEquals(2, report.rebuiltApps)
    }

    @Test
    fun removedProfileIsDropped() {
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()

        device.removeProfile(WORK)
        val report = sync.sync()

        assertEquals(1, report.removedProfiles)
        assertEquals(setOf(PERSONAL), sync.index.profiles())
    }

    @Test
    fun requestedPackagesAreRebuiltWithoutASequenceChange() {
        // LauncherApps.Callback で知った仕事用プロファイルの更新（番号に出ない）
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()

        device.install(WORK_TRANSIT.copy(label = "乗換テスト2"), silently = true)
        assertEquals(0, sync.sync().rebuiltApps)
        val report = sync.sync(setOf(TRANSIT.entry.id.packageName))

        assertEquals(2, report.rebuiltApps)
        assertEquals(listOf("乗換テスト", "乗換テスト2"), sync.labels())
    }

    @Test
    fun onlySequenceProgressIsSavedWithoutRebuilding() {
        device.install(TRANSIT)
        val sync = newSync()
        sync.sync()
        val published = ArrayList<SearchIndex>()
        sync.addListener { published += it }

        // 索引に入らないパッケージ（入口なし）の変更
        device.touch("io.example.service")
        val report = sync.sync()

        assertEquals(0, report.rebuiltApps)
        assertTrue("索引は変わらないので知らせない", published.isEmpty())
        // 進んだ番号を保存し、次に開いたときに同じ変更を見直さない
        assertEquals(
            2,
            opened
                .last()
                .index
                .load()
                .state
                ?.changeSequence,
        )
    }

    private fun SearchIndex.profiles(): Set<Long> =
        SearchEngine(this)
            .search(SearchQuery.of("テスト"))
            .mapTo(HashSet()) { it.app.profileSerial }

    private companion object {
        const val FILE = "index-sync-test.db"

        fun app(
            packageName: String,
            label: String,
            profile: Long = PERSONAL,
        ) = InstalledApp(AppEntry(AppId(packageName, "$packageName.Main", profile), label), packageUpdatedAt = 1_000)

        fun InstalledApp.copy(
            label: String = entry.label,
            updatedAt: Long = packageUpdatedAt,
        ) = InstalledApp(entry.copy(label = label), updatedAt)

        val TRANSIT = app("io.example.transit", "乗換テスト")
        val MAPS = app("io.example.maps", "地図テスト")
        val MAIL = app("io.example.mail", "メールテスト")
        val WORK_TRANSIT = app("io.example.transit", "乗換テスト", WORK)
        val WORK_MAIL = app("io.example.mail", "メールテスト", WORK)
    }
}
