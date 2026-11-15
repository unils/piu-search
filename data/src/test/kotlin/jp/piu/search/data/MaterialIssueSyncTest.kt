// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.res.Resources
import android.os.DeadObjectException
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchIndexVersion
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchQuery
import jp.piu.search.data.db.PiuRoomDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.cert.CertificateException

// 読み取りの失敗と材料の欠け（testing.md の「読み取りの失敗と材料の欠け（0.2.0）」）を、偽の端末と Robolectric の SQLite で確かめる。
// 同じ入力で 2 回続けるだけでは鍵の保存し忘れを捕まえられないので、鍵か入口の集合を変えて読ませ、失敗させ、同じ一覧で 3 回目以降を回す。
// どの場面も、作り直した数だけでなく、詳細を読みに行った組（FakeDevice.reads）まで確かめる
@RunWith(AndroidJUnit4::class)
class MaterialIssueSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<LocalDatabase>()
    private var device = FakeDevice()
    private val analyzer = CountingAnalyzer()
    private var now = 1_763_164_800_000L // 2025-11-15T00:00:00Z

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PiuRoomDatabase::class.java)

    @After
    fun closeDatabases() {
        opened.forEach(LocalDatabase::close)
        context.deleteDatabase(FILE)
    }

    private fun newSync(appVersionCode: Int = 1): IndexSync {
        val database = LocalDatabase.open(context, FILE, SearchIndexVersion.CURRENT, allowMainThread = true).also(opened::add)
        val generator = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList())))
        return IndexSync(
            database.index,
            device,
            { generator },
            analyzer,
            launcherApps = null,
            profiles = ProfileReconciler({ database.profiles }, { device.existingProfiles() }),
            profileSerialOf = { PERSONAL },
            appVersionCode = appVersionCode,
            clock = { now },
        )
    }

    private fun issues(): List<MaterialIssue> =
        opened
            .last()
            .index
            .load()
            .issues

    private fun IndexSync.found(query: String): List<String> = SearchEngine(index).search(SearchQuery.of(query)).map { it.label }

    // 端末の組み合わせごとに、失敗を混ぜて同期を続け、最後の 2 回で何も読まず、形態素解析器も呼ばないこと
    @Test
    fun failuresConvergeOnEveryDeviceCombination() {
        for (work in listOf(false, true)) {
            for (boot in listOf("stable", "unknown", "changing")) {
                closeDatabases()
                opened.clear()
                device = FakeDevice()
                converge(work, boot)
            }
        }
    }

    private fun converge(
        work: Boolean,
        boot: String,
    ) {
        val profile = if (work) WORK else PERSONAL
        if (boot == "unknown") device.bootCount = null
        val existing = (1..4).map { app("io.example.e$it", "既存$it", profile) }
        val zero = app("io.example.zero", "零テスト", profile, time = 0)
        // 鍵のない組は個人用に置く（鍵も時刻もない仕事用は、再起動のたびに 1 回読む規則で、別に確かめる）
        val keyless = app("io.example.keyless", "鍵なしテスト", PERSONAL, time = null)
        device.install(*existing.toTypedArray(), zero, keyless)
        val sync = newSync()
        sync.sync()

        // 既存の組の各段の失敗。鍵を変えて読ませる
        val changed = existing.map { it.copy(updatedAt = 2_000) }
        device.install(*changed.toTypedArray())
        device.fail(changed[0], MaterialStage.ENTRY, SecurityException())
        device.fail(changed[1], MaterialStage.LABEL, Resources.NotFoundException())
        device.fail(changed[2], MaterialStage.SIGNER, CertificateException())
        device.fail(changed[3], MaterialStage.LABEL, IOException())
        // 新しい組の ENTRY と KEYS の失敗
        val newEntry = app("io.example.newentry", "新入口テスト", profile)
        val newKeys = app("io.example.newkeys", "爆新キーテスト", profile)
        device.install(newEntry, newKeys)
        device.fail(newEntry, MaterialStage.ENTRY, RuntimeException(DeadObjectException()))
        analyzer.failOn = "爆"

        repeat(4) {
            if (boot == "changing") device.reboot()
            sync.sync()
        }
        repeat(2) {
            if (boot == "changing") device.reboot()
            device.reads.clear()
            analyzer.calls = 0
            val report = sync.sync()
            assertEquals("$work $boot: $report", emptyList<PackageKey>(), device.reads)
            assertEquals("$work $boot", 0, analyzer.calls)
        }
        assertEquals(
            "$work $boot",
            setOf(MaterialStage.ENTRY to changed[0], MaterialStage.LABEL to changed[1], MaterialStage.SIGNER to changed[2])
                .map {
                    it.first to
                        it.second.key
                }.toSet() +
                setOf(MaterialStage.LABEL to changed[3].key, MaterialStage.ENTRY to newEntry.key, MaterialStage.KEYS to newKeys.key),
            issues().map { it.stage to it.key }.toSet(),
        )
        // 新しい組も一覧の表示名で検索に出る
        assertTrue(sync.index.labelOf(newEntry.entry.id) == newEntry.entry.label)
        assertTrue(sync.index.labelOf(newKeys.entry.id) == newKeys.entry.label)
        analyzer.failOn = null
    }

    @Test
    fun keylessWorkPackageIsReadOncePerBoot() {
        val keyless = app("io.example.keyless", "鍵なしテスト", WORK, time = null)
        device.install(keyless)
        val sync = newSync()
        sync.sync()
        device.reads.clear()
        sync.sync()
        assertEquals(emptyList<PackageKey>(), device.reads)
        device.reboot()
        sync.sync()
        assertEquals(listOf(keyless.key), device.reads)
        device.reads.clear()
        sync.sync()
        assertEquals(emptyList<PackageKey>(), device.reads)
    }

    @Test
    fun newPackageWithEntryOrKeysFailureIsFoundByItsListedLabel() {
        val sync = newSync()
        sync.sync()
        val entry = app("io.example.entry", "入口失敗テスト")
        val keys = app("io.example.keys", "爆キー失敗テスト")
        device.install(entry, keys)
        device.fail(entry, MaterialStage.ENTRY, SecurityException())
        analyzer.failOn = "爆"
        val report = sync.sync()
        // 正規化だけのキー（厳密形と緩い形）で引ける
        assertEquals(listOf("入口失敗テスト"), sync.found("入口失敗"))
        assertEquals(listOf("爆キー失敗テスト"), sync.found("爆キー"))
        val keysIssue = issues().single { it.stage == MaterialStage.KEYS }
        assertEquals(IssueCategory.SUSPECTED_BUG, keysIssue.category)
        assertEquals("label:len8:kanji,kana", keysIssue.inputSummary)
        assertNotNull(keysIssue.frames)
        assertEquals(1, keysIssue.versionCode)
        assertFalse(keysIssue.retryOnce)
        assertEquals("2025-11-15", keysIssue.firstFailedOn)
        assertEquals(IssueCounts(deterministic = 1, suspectedBug = 1), report.issueCounts)
        assertEquals(SyncOutcome.ISSUES, report.outcome)
    }

    @Test
    fun keysFailureOfAnExistingEntryKeepsItsOldRow() {
        val transit = app("io.example.transit", "乗換テスト")
        device.install(transit)
        val sync = newSync()
        sync.sync()
        device.install(transit.copy(label = "爆乗換テスト", updatedAt = 2_000))
        analyzer.failOn = "爆"
        sync.sync()
        assertEquals("乗換テスト", sync.index.labelOf(transit.entry.id))
        assertEquals(listOf(MaterialStage.KEYS), issues().map { it.stage })
        // 読んだ組は成否によらず今回の鍵を保存するので、3 回目は読まない
        device.reads.clear()
        sync.sync()
        assertEquals(emptyList<PackageKey>(), device.reads)
    }

    @Test
    fun uninstallingAPackageRemovesItsIssuesButAPausedWorkProfileKeepsThem() {
        val personal = app("io.example.mail", "メールテスト")
        val work = app("io.example.mail", "メールテスト", WORK)
        device.install(personal, work)
        device.fail(personal, MaterialStage.SIGNER, CertificateException())
        device.fail(work, MaterialStage.SIGNER, CertificateException())
        val sync = newSync()
        sync.sync()
        assertEquals(2, issues().size)
        device.unavailable += WORK
        device.uninstall(personal.entry.id.packageName)
        sync.sync()
        assertEquals(listOf(work.key), issues().map { it.key })
        assertEquals(listOf(work.key), sync.materialIssues.map { it.key })
    }

    @Test
    fun entryFailureKeepsThePreviousLabelAndSignerRows() {
        val maps = app("io.example.maps", "地図テスト")
        device.install(maps)
        device.fail(maps, MaterialStage.LABEL, Resources.NotFoundException())
        val sync = newSync()
        sync.sync()
        val label = issues().single()
        device.failures.clear()
        device.install(maps.copy(updatedAt = 2_000))
        device.fail(maps, MaterialStage.ENTRY, IOException())
        sync.sync()
        assertEquals(label, issues().single { it.stage == MaterialStage.LABEL })
        assertTrue(issues().single { it.stage == MaterialStage.ENTRY }.retryOnce)
    }

    @Test
    fun notificationsAreConsumedOnceTheirResultIsSaved() {
        val maps = app("io.example.maps", "地図テスト")
        val work = app("io.example.mail", "メールテスト", WORK)
        device.install(maps, work)
        val sync = newSync()
        sync.sync()
        // 索引にあった組のアンインストールと、もともとなかった組の知らせ。COMPLETE の一覧で不在を確かめたら消費する
        device.uninstall(maps.entry.id.packageName, silently = true)
        sync.packagesChanged(setOf(maps.entry.id.packageName, "io.example.never"), android.os.Process.myUserHandle())
        sync.sync()
        assertEquals(0, sync.pendingNotificationCount())
        // 一時停止中の仕事用の組の知らせは残る
        device.unavailable += WORK
        sync.packagesChanged(setOf(work.entry.id.packageName))
        sync.sync()
        assertEquals(1, sync.pendingNotificationCount())
        device.unavailable.clear()
        device.reads.clear()
        sync.sync()
        assertEquals(listOf(work.key), device.reads)
        assertEquals(0, sync.pendingNotificationCount())
    }

    // 仕事用が一時停止中にこのアプリの版が変わる。個人用だけを読んで版を保存した後、再開後に仕事用の欠けの組を読み直す
    @Test
    fun versionChangeWhileWorkIsPausedReachesWorkAfterResuming() {
        val personal = app("io.example.maps", "地図テスト")
        val work = app("io.example.mail", "メールテスト", WORK)
        val clean = app("io.example.clean", "問題なしテスト", WORK)
        device.install(personal, work, clean)
        device.fail(personal, MaterialStage.SIGNER, CertificateException())
        device.fail(work, MaterialStage.SIGNER, CertificateException())
        newSync(appVersionCode = 1).sync()
        device.unavailable += WORK
        device.failures.clear()
        closeAndReopen()

        // 保存の前に止まった同期は、版も印も書かない
        val upgraded = newSync(appVersionCode = 2)
        device.beforeRead = { error("保存の前に止める") }
        runCatching { upgraded.sync() }
        device.beforeRead = {}
        val untouched = opened.last().index.load()
        assertEquals(1, untouched.state?.appVersionCode)
        assertTrue(untouched.apps.none { it.rebuildPending })

        device.reads.clear()
        upgraded.sync()
        assertEquals(listOf(personal.key), device.reads)
        val saved = opened.last().index.load()
        assertEquals(2, saved.state?.appVersionCode)
        assertEquals(
            setOf(work.key),
            saved.apps
                .filter { it.rebuildPending }
                .map { it.key }
                .toSet(),
        )

        device.unavailable.clear()
        device.reads.clear()
        upgraded.sync()
        assertEquals(listOf(work.key), device.reads)
        assertTrue(issues().none { it.key == work.key })
        device.reads.clear()
        upgraded.sync()
        assertEquals(emptyList<PackageKey>(), device.reads)
    }

    @Test
    fun pausingAndResumingWorkDoesNotRebuildUnlessTheLocaleChanged() {
        val work = app("io.example.mail", "メールテスト", WORK)
        device.install(app("io.example.maps", "地図テスト"), work)
        val sync = newSync()
        sync.sync()
        device.unavailable += WORK
        sync.sync()
        device.unavailable.clear()
        device.reads.clear()
        val resumed = sync.sync()
        assertEquals(0, resumed.rebuiltApps)
        assertEquals(emptyList<PackageKey>(), device.reads)

        device.unavailable += WORK
        device.locales = "en-US"
        sync.sync()
        device.unavailable.clear()
        device.reads.clear()
        val afterLocale = sync.sync()
        assertEquals(listOf(work.key), device.reads)
        assertEquals(1, afterLocale.rebuiltApps)
    }

    @Test
    fun archivedPackagesSkipAuxiliaryMaterialAndAreCounted() {
        val maps = app("io.example.maps", "地図テスト")
        val work = app("io.example.mail", "メールテスト", WORK)
        device.install(maps, work)
        device.fail(maps, MaterialStage.LABEL, Resources.NotFoundException())
        device.fail(maps, MaterialStage.SIGNER, CertificateException())
        val sync = newSync()
        sync.sync()
        assertEquals(2, issues().size)

        // アーカイブすると APK がなくなり、鍵もなくなる。LABEL・SIGNER の行は消え、件数に入る
        device.install(
            maps.copy(archived = true, packageUpdatedAt = null),
            work.copy(archived = true, packageUpdatedAt = null),
            silently = true,
        )
        val archived = sync.sync()
        assertEquals(emptyList<MaterialIssue>(), issues())
        assertEquals(2, archived.archivedPackages)
        assertEquals(SyncOutcome.OK, archived.outcome)
        assertEquals(listOf("地図テスト"), sync.found("地図テスト"))

        // 一時停止中の仕事用も、保存した archived から数える
        device.unavailable += WORK
        assertEquals(2, sync.sync().archivedPackages)
        device.unavailable.clear()

        // 個人用を変更番号に出さずに復元しても、archived の比較で読み直す
        device.failures.clear()
        device.install(maps, silently = true)
        device.reads.clear()
        val restored = sync.sync()
        assertEquals(listOf(maps.key), device.reads)
        assertEquals(1, restored.archivedPackages)
    }

    // 0.1.x（Room 5）の DB から開いた最初の同期は、印の組（指紋 NULL）と、指紋の形が変わった仕事用の組だけを読む
    @Test
    fun firstSyncAfterMigrationReadsOnlyMarkedAndWorkPackages() {
        val marked = app("io.example.marked", "印テスト")
        val plain = app("io.example.plain", "通常テスト")
        val work = app("io.example.work", "仕事テスト", WORK)
        device.install(marked, plain, work, silently = true)
        // インストールの印が合わないと、開いたときに索引を捨てる（端末間転送の扱い）。印のファイルを先に作り、同じ値を DB に入れる
        LocalDatabase.open(context, "marker.db", allowMainThread = true).use { it.index.load() }
        context.deleteDatabase("marker.db")
        val token = java.io.File(context.noBackupFilesDir, "installation-id").readText()
        helper.createDatabase(FILE, 5).use { db ->
            db.execSQL("INSERT INTO installation VALUES (0, '$token')")
            db.execSQL(
                "INSERT INTO meta (id, index_version, change_sequence, boot_count, locales, build_fingerprint) " +
                    "VALUES (0, ${SearchIndexVersion.CURRENT}, 0, 1, 'ja-JP', 'test-build')",
            )
            for ((i, row) in listOf(marked to null, plain to "'old'", work to "'1:/x:1'").withIndex()) {
                val (installed, fingerprint) = row
                val id = installed.entry.id
                db.execSQL(
                    "INSERT INTO app (id, package_name, activity_name, profile_serial, label, package_updated_at, apk_fingerprint) " +
                        "VALUES (${i + 1}, '${id.packageName}', '${id.activityName}', ${id.profileSerial}, '${installed.entry.label}', 1000, $fingerprint)",
                )
            }
        }
        val sync = newSync()
        device.reads.clear()
        val first = sync.sync()
        assertEquals(SyncMode.CHANGES, first.mode)
        assertEquals(setOf(marked.key, work.key), device.reads.toSet())
        device.reads.clear()
        sync.sync()
        assertEquals(emptyList<PackageKey>(), device.reads)
    }

    @Test
    fun outcomeIsTheFirstMatchingOfFailedPartialIssuesAndOk() {
        val maps = app("io.example.maps", "地図テスト")
        device.install(maps, app("io.example.mail", "メールテスト", WORK))
        val sync = newSync()
        assertEquals(SyncOutcome.OK, sync.sync().outcome)

        // 一時停止は結果を下げない
        device.unavailable += WORK
        assertEquals(SyncOutcome.OK, sync.sync().outcome)
        device.unavailable.clear()

        device.genreFailure = IllegalStateException()
        val genre = sync.sync()
        assertEquals(SyncOutcome.PARTIAL, genre.outcome)
        assertEquals("java.lang.IllegalStateException", genre.genreFailure)
        assertNull(sync.status.failure)
        device.genreFailure = null

        device.failedProfiles += WORK
        val profile = sync.sync()
        assertEquals(SyncOutcome.PARTIAL, profile.outcome)
        assertEquals(mapOf(PERSONAL to ProfileReadStatus.COMPLETE, WORK to ProfileReadStatus.FAILED), profile.profileResults)
        assertEquals(SyncFailure.PROFILE, sync.status.failure)
        device.failedProfiles.clear()

        device.install(maps.copy(updatedAt = 2_000))
        device.fail(maps, MaterialStage.SIGNER, CertificateException())
        assertEquals(SyncOutcome.ISSUES, sync.sync().outcome)
        assertEquals(SyncOutcome.ISSUES, sync.status.outcome)

        device.beforeRead = { error("同期全体の失敗") }
        device.touch(maps.entry.id.packageName)
        runCatching { sync.sync() }
        assertEquals(SyncOutcome.FAILED, sync.status.outcome)
        device.beforeRead = {}
        assertNotNull(sync.lastSavedAt)
    }

    private fun closeAndReopen() {
        opened.forEach(LocalDatabase::close)
        opened.clear()
    }

    private companion object {
        const val FILE = "material-issue-sync-test.db"

        fun app(
            packageName: String,
            label: String,
            profile: Long = PERSONAL,
            time: Long? = 1_000,
        ) = InstalledApp(AppEntry(AppId(packageName, "$packageName.Main", profile), label), packageUpdatedAt = time)

        fun InstalledApp.copy(
            label: String = entry.label,
            updatedAt: Long? = packageUpdatedAt,
        ) = InstalledApp(entry.copy(label = label), updatedAt, apkFingerprint, archived)
    }
}
