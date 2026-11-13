// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// 本物の端末で、テスト用のアプリ（test-apps/）を入れる・更新する・消すと、次の同期で索引に反映されることを確かめる。
// 更新には、:test-apps:installTestApps が端末に置いた transit の更新の試験用の版（versionCode 2、名前が「…2」）を使う。
// 試験の後は、もとの版（debug）に戻す
@RunWith(AndroidJUnit4::class)
class IndexSyncInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<LocalDatabase>()
    private var bootCount: Int? = null

    @Before
    fun saveOriginalTransit() {
        // もとの版の APK を取っておく（入れ直しと、試験の後の戻しに使う）
        val path = shell("pm path $TRANSIT").trim().removePrefix("package:")
        assertTrue("transit が入っている: $path", path.endsWith(".apk"))
        shell("cp $path $ORIGINAL_APK")
    }

    @After
    fun restoreTransit() {
        opened.forEach(LocalDatabase::close)
        context.deleteDatabase(FILE)
        if (installedVersion() != 1) {
            shell("pm uninstall $TRANSIT")
            shell("pm install $ORIGINAL_APK")
        }
        shell("rm -f $ORIGINAL_APK")
        assertEquals("もとの版に戻した", 1, installedVersion())
    }

    private fun newSync(watch: Boolean = false): IndexSync {
        val database = LocalDatabase.open(context, FILE).also(opened::add)
        val device = AndroidDeviceApps(context, bootCountOverride = { bootCount })
        return IndexSync(
            database.index,
            device,
            { SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.loadDefault())) },
            analyzer,
            if (watch) context.getSystemService(LauncherApps::class.java) else null,
        )
    }

    @Test
    fun installUpdateAndUninstallAreReflectedOnTheNextSync() {
        val sync = newSync()
        val first = sync.sync()
        Log.i(TAG, "最初の同期: $first")
        assertEquals(SyncMode.FULL_REBUILD, first.mode)
        assertEquals(listOf(TRANSIT_LABELS[0]), sync.index.labelsFor("のりかえ"))
        assertTrue("「ちず」でテスト地図（ジャンル）", sync.index.labelsFor("ちず").any { it in MAPS_LABELS })
        // 漢字の検索語は、読みを付けて引ける
        assertEquals(listOf(TRANSIT_LABELS[0]), sync.index.labelsFor("乗り換え"))

        shell("pm uninstall $TRANSIT")
        val removed = sync.sync()
        Log.i(TAG, "消した後: $removed")
        assertEquals(SyncMode.CHANGES, removed.mode)
        assertEquals(emptyList<String>(), sync.index.labelsFor("のりかえ"))

        shell("pm install $ORIGINAL_APK")
        val installed = sync.sync()
        Log.i(TAG, "入れた後: $installed")
        assertEquals(profilesWithTransit(), installed.rebuiltApps)
        assertEquals(listOf(TRANSIT_LABELS[0]), sync.index.labelsFor("のりかえ"))

        shell("pm install -r $UPDATE_APK")
        assertEquals(2, installedVersion())
        val updated = sync.sync()
        Log.i(TAG, "更新した後: $updated")
        assertEquals(SyncMode.CHANGES, updated.mode)
        assertEquals(profilesWithTransit(), updated.rebuiltApps)
        assertEquals(listOf(TRANSIT_LABELS[1]), sync.index.labelsFor("のりかえ"))

        // 次に開いたとき（保存した索引を読む）も同じ
        val reopened = newSync()
        assertEquals(0, reopened.sync().rebuiltApps)
        assertEquals(listOf(TRANSIT_LABELS[1]), reopened.index.labelsFor("のりかえ"))
    }

    @Test
    fun bootCountChangeReviewsAllPackages() {
        bootCount = 1
        newSync().sync()
        // 再起動の間に更新されたが、変更の番号には出ない状態を作る: 保存した transit を古い名前と時刻に書き換える
        val database = opened.single()
        val stored = database.index.load()
        val stale =
            stored.apps
                .filter { it.app.id.packageName == TRANSIT && it.app.id.profileSerial == mySerial }
                .map { StoredApp(IndexedApp(it.app.id, "古い名前", it.app.keys), packageUpdatedAt = 0) }
        database.index.update(stale, state = stored.state!!)

        // 同じ起動の間なら、番号に出ないので見直さない
        val sameBoot = newSync()
        assertEquals(SyncMode.CHANGES, sameBoot.sync().mode)
        assertEquals(listOf("古い名前"), sameBoot.index.labelsFor("のりかえ"))

        bootCount = 2
        val rebooted = newSync()
        val report = rebooted.sync()
        Log.i(TAG, "再起動の後: $report")
        assertEquals(SyncMode.FULL_REVIEW, report.mode)
        assertEquals(1, report.rebuiltApps)
        assertEquals(listOf(TRANSIT_LABELS[0]), rebooted.index.labelsFor("のりかえ"))
    }

    @Test
    fun changesWhileWatchingAreReflectedWithoutAskingForSync() {
        val sync = newSync(watch = true)
        sync.sync()
        val published = LinkedBlockingQueue<SearchIndex>()
        sync.addListener { published += it }
        sync.setSearchVisible(true)
        sync.startWatching()
        try {
            shell("pm uninstall $TRANSIT")
            assertTrue("消えたことが届く", awaitIndex(published) { it.labelsFor("のりかえ").isEmpty() })
            shell("pm install $ORIGINAL_APK")
            assertTrue("入ったことが届く", awaitIndex(published) { it.labelsFor("のりかえ") == listOf(TRANSIT_LABELS[0]) })
        } finally {
            sync.setSearchVisible(false)
            sync.stopWatching()
            // 遅れて届いた変更の同期が、データベースを閉じた後に走らないようにする
            Thread.sleep(SETTLE_MILLIS)
            sync.awaitIdle()
        }
    }

    @Test
    fun ownPackageIsNotIndexed() {
        val sync = newSync()
        sync.sync()
        val entries = InstalledAppReader(context).readAll().map { it.id }
        assertFalse(entries.any { it.packageName == context.packageName })
        assertTrue(sync.index.size > 0)
    }

    @Test
    fun kuromojiLoadTimeAndMemory() {
        // 辞書の読み込みの時間と、ヒープの増え方を測って記録する（報告用。合否は付けない）
        val runtime = Runtime.getRuntime()
        repeat(2) {
            val fresh = KuromojiReadingAnalyzer()
            System.gc()
            Thread.sleep(200)
            val before = runtime.totalMemory() - runtime.freeMemory()
            val started = System.nanoTime()
            fresh.warmUp()
            val millis = (System.nanoTime() - started) / 1_000_000
            System.gc()
            Thread.sleep(200)
            val after = runtime.totalMemory() - runtime.freeMemory()
            Log.i(TAG, "Kuromoji の読み込み ${it + 1} 回目: $millis ms、ヒープ ${(after - before) / 1024 / 1024} MB 増")
            assertEquals("のりかえあんない", fresh.analyze("乗換案内").joinToString("") { token -> token.reading.orEmpty() })
            fresh.release()
        }
    }

    private fun awaitIndex(
        queue: LinkedBlockingQueue<SearchIndex>,
        condition: (SearchIndex) -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val next = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return false
            if (condition(next)) return true
        }
        return false
    }

    /** transit の入口があるプロファイルの数。仕事用プロファイルにも入っていれば、入れ直しと更新でその分も作り直す。 */
    private fun profilesWithTransit(): Int {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        return launcherApps.profiles.count { launcherApps.getActivityList(TRANSIT, it).isNotEmpty() }
    }

    private fun installedVersion(): Int? {
        val line = shell("dumpsys package $TRANSIT").lines().firstOrNull { it.trim().startsWith("versionCode=") } ?: return null
        return line
            .trim()
            .removePrefix("versionCode=")
            .substringBefore(' ')
            .toIntOrNull()
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        // 出力を読み切ると、コマンドの終わりまで待てる
        return android.os.ParcelFileDescriptor
            .AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private companion object {
        const val TAG = "IndexSyncTest"
        const val FILE = "index-sync-instrumented.db"
        const val TRANSIT = "jp.piu.search.testapp.transit"
        const val ORIGINAL_APK = "/data/local/tmp/piu-testapp-transit-original.apk"
        const val UPDATE_APK = "/data/local/tmp/piu-testapp-transit-update.apk"
        const val TIMEOUT_MILLIS = 30_000L
        const val SETTLE_MILLIS = 1_000L

        // 辞書の読み込みをテストごとに繰り返さない
        val analyzer = KuromojiReadingAnalyzer()

        /** 端末の言語で決まる表示名。debug の版と更新の試験用の版。 */
        val TRANSIT_LABELS: List<String> =
            if (isJapanese()) listOf("乗換案内テスト", "乗換案内テスト2") else listOf("Transit Test", "Transit Test 2")
        val MAPS_LABELS = setOf("テスト地図", "Test Map")

        fun isJapanese(): Boolean =
            android.content.res.Resources
                .getSystem()
                .configuration.locales[0]
                .language == "ja"

        /**
         * このテストが動くプロファイル（主プロファイル）のシリアル番号。
         * 仕事用プロファイルにもテスト用のアプリが入っている端末では、同じ名前が 2 件出るので、主プロファイルに絞って比べる
         */
        val mySerial: Long by lazy {
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(UserManager::class.java)
                .getSerialNumberForUser(Process.myUserHandle())
        }

        /** 検索語 [raw] で出る、主プロファイルのテスト用のアプリの表示名（読みを付けた検索語で照らす）。 */
        fun SearchIndex.labelsFor(raw: String): List<String> =
            SearchEngine(this)
                .search(SearchQuery.of(raw).withReading(analyzer))
                .filter { it.app.packageName.startsWith("jp.piu.search.testapp.") && it.app.profileSerial == mySerial }
                .map { it.label }
    }
}
