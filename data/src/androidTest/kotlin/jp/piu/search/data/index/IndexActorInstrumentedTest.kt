// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchQuery
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.ListingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// 本物の端末で、テスト用のアプリ（test-apps/）を入れる・更新する・消すと、索引の書き手の次の同期で写しに反映されることを確かめる。
// 更新には、:test-apps:installTestApps が端末に置いた transit の更新の試験用の版（versionCode 2、名前が「…2」）を使う。
// 試験の後は、もとの版（debug）に戻す
@RunWith(AndroidJUnit4::class)
class IndexActorInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val writers = ArrayList<IndexActor>()
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
        runBlocking { writers.forEach { it.job.cancelAndJoin() } }
        context.deleteDatabase(FILE)
        if (installedVersion() != 1) {
            shell("pm uninstall $TRANSIT")
            shell("pm install $ORIGINAL_APK")
        }
        shell("rm -f $ORIGINAL_APK")
        assertEquals("もとの版に戻した", 1, installedVersion())
    }

    // 前の書き手を止めてから、同じ DB で開く（次にアプリを開いたとき）
    private fun newWriter(): IndexActor {
        runBlocking { writers.forEach { it.job.cancelAndJoin() } }
        writers.clear()
        return IndexActor
            .open(
                context,
                FILE,
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                APP_VERSION,
                DeviceAppsReader(context) { bootCount },
                GeneratorKeyFactory(analyzer),
                Dispatchers.IO,
            ) { IndexDatabase.open(context, FILE) }
            .also(writers::add)
    }

    private fun IndexActor.sync(): SyncSummary {
        runBlocking { requestSync().await() }
        val summary = checkNotNull(status.value.lastSync)
        Log.i(TAG, "同期: $summary ${status.value}")
        return summary
    }

    @Test
    fun installUpdateAndUninstallAreReflectedOnTheNextSync() {
        val installedIn = profilesWithTransit()
        val writer = newWriter()
        val first = writer.sync()
        assertTrue(first.keysBuilt > 0)
        assertEquals(listOf(TRANSIT_LABELS[0]), writer.snapshot.value.labelsFor("のりかえ"))
        assertTrue(
            "「ちず」でテスト地図（ジャンル）",
            writer.snapshot.value
                .labelsFor("ちず")
                .any { it in MAPS_LABELS },
        )
        // 漢字の検索語は、読みを付けて引ける
        assertEquals(listOf(TRANSIT_LABELS[0]), writer.snapshot.value.labelsFor("乗り換え"))

        shell("pm uninstall $TRANSIT")
        val removed = writer.sync()
        assertEquals(installedIn, removed.removed)
        assertEquals(emptyList<String>(), writer.snapshot.value.labelsFor("のりかえ"))

        shell("pm install $ORIGINAL_APK")
        assertEquals(profilesWithTransit(), writer.sync().materialsRead)
        assertEquals(listOf(TRANSIT_LABELS[0]), writer.snapshot.value.labelsFor("のりかえ"))

        // 変更の番号は使わない。毎回の一覧の鍵（APK の属性と更新の時刻）の違いで拾う
        shell("pm install -r $UPDATE_APK")
        assertEquals(2, installedVersion())
        assertEquals(profilesWithTransit(), writer.sync().materialsRead)
        assertEquals(listOf(TRANSIT_LABELS[1]), writer.snapshot.value.labelsFor("のりかえ"))

        // 次に開いたとき（保存した索引を読み戻す）も同じで、何も作り直さない
        val reopened = newWriter()
        val again = reopened.sync()
        assertEquals(0, again.materialsRead)
        assertEquals(0, again.keysBuilt)
        assertEquals(listOf(TRANSIT_LABELS[1]), reopened.snapshot.value.labelsFor("のりかえ"))
    }

    // 端末のすべての入口（テスト用のアプリに限らない）で、同じ状態のまま同期を続けると何も読み直さない。
    // 読み直しや失敗が残ると、利用者には開くたびの辞書の読み込みと「索引を更新できなかった」の表示になる
    @Test
    fun repeatedSyncsOnTheSameDeviceReadNothing() {
        bootCount = 1
        val first = newWriter().sync()
        Log.i(TAG, "最初の同期: $first")

        assertConverged(newWriter())
        // 再起動の後: 鍵のない組（APK の属性も更新の時刻もない組）だけを読み直す。一覧の鍵がある組は読み直さない
        bootCount = 2
        val rebooted = newWriter()
        runBlocking { rebooted.status.first { it.loaded } }
        val keyless =
            rebooted.snapshot.value.packages.values
                .count { it.observed.keyless }
        assertEquals("再起動の後に読むのは鍵のない組だけ", keyless, rebooted.sync().materialsRead)
        assertConverged(rebooted)
    }

    // 0.1.0 型の不具合（system image のアプリで毎回失敗する）を見逃さない。収束しても、欠けが増えれば検索の材料が失われるので、
    // test-apps 以外の入口の欠けを段と理由ごとに数え、system image ごとの既知の件数と比べる（増減の相殺も見逃さない）。
    // test-apps の分類ごとのアプリの欠けは MaterialIssueInstrumentedTest が厳密に確かめる
    private fun assertConverged(writer: IndexActor) {
        val report = writer.sync()
        val status = writer.status.value
        val packages = writer.snapshot.value.packages.values
        val issues = packages.mapNotNull { state -> state.attempt.issue?.let { state.key to it } }
        val breakdown =
            issues
                .filterNot { (key, _) -> key.packageName.startsWith(TEST_APP_PREFIX) }
                .groupingBy { (_, issue) -> "${issue.stage}:${issue.failure.cause}" }
                .eachCount()
                .toSortedMap()
        Log.i(TAG, "test-apps 以外の欠け（$IMAGE）: $breakdown")
        val retrying = packages.filter { it.attempt.retry }.map { it.key }
        val bugs = issues.filter { (_, issue) -> issue.failure.category == IssueCategory.SUSPECTED_BUG }
        if (report.materialsRead != 0 || report.keysBuilt != 0 || report.failed || status.genreFailure != null ||
            retrying.isNotEmpty() || bugs.isNotEmpty() ||
            writer.snapshot.value.listings.values
                .any { it != ListingStatus.COMPLETE }
        ) {
            throw AssertionError("同じ状態の同期で読み直しか失敗が残った: $report $status\n再試行: $retrying\n欠け: $issues")
        }
        val known = KNOWN_ISSUES[IMAGE]
        if (known == null) {
            // 実機など、件数を定めていない image では比べない（入っているアプリで変わるため）
            Log.w(TAG, "既知の件数のない image（$IMAGE）なので比べない")
            return
        }
        assertEquals("test-apps 以外の欠け（$IMAGE。image を上げたら testing.md のとおり測り直す）", known, breakdown)
    }

    // 知らせ（LauncherApps.Callback）を書き手に渡すと、前面の間は同期を頼まなくても写しに届く（app の PackageWatcher と同じ渡し方）
    @Test
    fun noticesWhileInTheForegroundAreReflectedWithoutAskingForSync() {
        val writer = newWriter()
        writer.sync()
        writer.setForeground(true)
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val callback = NoticeForwarder(writer)
        launcherApps.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        try {
            shell("pm uninstall $TRANSIT")
            assertTrue("消えたことが届く", awaitSnapshot(writer) { it.labelsFor("のりかえ").isEmpty() })
            shell("pm install $ORIGINAL_APK")
            assertTrue("入ったことが届く", awaitSnapshot(writer) { it.labelsFor("のりかえ") == listOf(TRANSIT_LABELS[0]) })
        } finally {
            launcherApps.unregisterCallback(callback)
            writer.setForeground(false)
        }
    }

    @Test
    fun ownPackageIsNotIndexed() {
        val writer = newWriter()
        writer.sync()
        val packages = writer.snapshot.value.packages.keys
        assertTrue(packages.none { it.packageName == context.packageName })
        assertTrue(packages.isNotEmpty())
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

    private class NoticeForwarder(
        private val writer: IndexActor,
    ) : LauncherApps.Callback() {
        override fun onPackageRemoved(
            packageName: String,
            user: UserHandle,
        ) = writer.packagesChanged(listOf(packageName), user)

        override fun onPackageAdded(
            packageName: String,
            user: UserHandle,
        ) = writer.packagesChanged(listOf(packageName), user)

        override fun onPackageChanged(
            packageName: String,
            user: UserHandle,
        ) = writer.packagesChanged(listOf(packageName), user)

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = writer.packagesChanged(packageNames.toList(), user)

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = writer.packagesChanged(packageNames.toList(), user)
    }

    private fun awaitSnapshot(
        writer: IndexActor,
        condition: (IndexSnapshot) -> Boolean,
    ): Boolean = runBlocking { withTimeoutOrNull(TIMEOUT_MILLIS) { writer.snapshot.first(condition) } } != null

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
        const val TAG = "IndexActorTest"
        const val FILE = "index-actor-instrumented.db"
        const val APP_VERSION = 1
        const val TRANSIT = "jp.piu.search.testapp.transit"
        const val TEST_APP_PREFIX = "jp.piu.search.testapp."

        /** この端末の system image。CI と手元の AVD の image（AOSP の既定の x86_64 の像。PRODUCT は API 37 が `sdk_phone64_x86_64`、API 28 が `sdk_phone_x86_64`）を見分ける。 */
        val IMAGE = "${android.os.Build.PRODUCT}/${android.os.Build.VERSION.SDK_INT}"

        /**
         * system image ごとの、test-apps 以外の入口の欠けの件数（`段:理由` ごと）。同期を 2 回続けた後の値。
         * image を上げたら測り直して直す（testing.md の「読み取りの失敗と材料の欠け」）。
         */
        val KNOWN_ISSUES: Map<String, Map<String, Int>> =
            mapOf(
                "sdk_phone64_x86_64/37" to emptyMap(),
                "sdk_phone_x86_64/28" to emptyMap(),
            )
        const val ORIGINAL_APK = "/data/local/tmp/piu-testapp-transit-original.apk"
        const val UPDATE_APK = "/data/local/tmp/piu-testapp-transit-update.apk"
        const val TIMEOUT_MILLIS = 30_000L

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
        fun IndexSnapshot.labelsFor(raw: String): List<String> =
            SearchEngine(toSearchIndex())
                .search(SearchQuery.of(raw).withReading(analyzer))
                .filter { it.app.packageName.startsWith(TEST_APP_PREFIX) && it.app.profileSerial == mySerial }
                .map { it.label }
    }
}
