// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps

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

    private fun newSync(
        indexVersion: Int = 4,
        existingProfiles: () -> Set<Long>? = { device.existingProfiles() },
        source: DeviceApps = device,
        watch: Boolean = false,
    ): IndexSync {
        val database = LocalDatabase.open(context, FILE, indexVersion, allowMainThread = true).also(opened::add)
        val generator = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList())))
        return IndexSync(
            database.index,
            source,
            { generator },
            analyzer,
            launcherApps = if (watch) context.getSystemService(LauncherApps::class.java) else null,
            profiles = ProfileReconciler({ database.profiles }, existingProfiles),
            profileSerialOf = context.getSystemService(UserManager::class.java)::getSerialNumberForUser,
        )
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
    @Config(sdk = [28, 37])
    fun bootCountChangeReviewsACompleteStoredPackageWithAStaleTime() {
        device.install(TRANSIT, MAPS)
        newSync().sync()
        val database = opened.single()
        val stored = database.index.load()
        // 指紋は完全な読み取りの印でもあるため、再起動による時刻の見直しの試験では引き継ぐ
        val stale =
            stored.apps.filter { it.app.id == TRANSIT.entry.id }.map {
                it.copy(app = it.app.copy(label = "古い名前"), packageUpdatedAt = 0)
            }
        assertEquals(1, stale.size)
        assertNotNull(stale.single().apkFingerprint)
        database.index.update(stale, state = checkNotNull(stored.state))
        assertEquals(
            stale.single(),
            database.index
                .load()
                .apps
                .single { it.app.id == TRANSIT.entry.id },
        )

        val sameBoot = newSync()
        val unchanged = sameBoot.sync()
        assertEquals(SyncMode.CHANGES, unchanged.mode)
        assertEquals(0, unchanged.rebuiltApps)
        assertEquals("古い名前", sameBoot.index.labelOf(TRANSIT.entry.id))

        device.reboot()
        val rebooted = newSync()
        val reviewed = rebooted.sync()
        assertEquals(SyncMode.FULL_REVIEW, reviewed.mode)
        assertEquals(1, reviewed.rebuiltApps)
        assertEquals(TRANSIT.entry.label, rebooted.index.labelOf(TRANSIT.entry.id))
    }

    @Test
    @Config(sdk = [28, 37])
    fun storedPackageWithoutAFingerprintIsRetriedInTheSameBoot() {
        device.install(TRANSIT, MAPS)
        newSync().sync()
        val database = opened.single()
        val stored = database.index.load()
        assertNotNull(stored.apps.single { it.app.id == TRANSIT.entry.id }.apkFingerprint)
        val stale =
            stored.apps.filter { it.app.id == TRANSIT.entry.id }.map {
                it.copy(app = it.app.copy(label = "古い名前"), apkFingerprint = null)
            }
        database.index.update(stale, state = checkNotNull(stored.state))

        val reopened = newSync()
        val retried = reopened.sync()
        assertEquals(SyncMode.CHANGES, retried.mode)
        assertEquals(1, retried.rebuiltApps)
        assertEquals(TRANSIT.entry.label, reopened.index.labelOf(TRANSIT.entry.id))
        assertNotNull(
            database.index
                .load()
                .apps
                .single { it.app.id == TRANSIT.entry.id }
                .apkFingerprint,
        )
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
    @Config(sdk = [28, 37])
    fun indexVersionChangeKeepsPausedWorkIndexAndRebuildsItAfterReopeningAndResuming() {
        device.install(TRANSIT, WORK_TRANSIT)
        newSync(indexVersion = 4).sync()
        opened.forEach(LocalDatabase::close)
        opened.clear()
        device.unavailable += WORK
        val upgraded = newSync(indexVersion = 5)
        assertEquals(1, upgraded.sync().rebuiltApps)
        assertEquals(WORK_TRANSIT.entry.label, upgraded.index.labelOf(WORK_TRANSIT.entry.id))
        assertEquals(0, upgraded.sync().rebuiltApps)
        opened.forEach(LocalDatabase::close)
        opened.clear()

        val reopened = newSync(indexVersion = 5)
        assertEquals(0, reopened.sync().rebuiltApps)
        assertEquals(WORK_TRANSIT.entry.label, reopened.index.labelOf(WORK_TRANSIT.entry.id))
        device.unavailable.clear()
        // APK の指紋も更新時刻も同じでも、旧版で作った入口は再開後に作り直す
        assertEquals(1, reopened.sync().rebuiltApps)
        assertEquals(setOf(PERSONAL, WORK), reopened.index.profiles())
        assertEquals(0, reopened.sync().rebuiltApps)
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
    fun pausedOrUnlistedProfileKeepsItsIndexIncludingDuringFullRebuild() {
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()
        device.unavailable += WORK
        assertEquals(0, sync.sync().removedProfiles)
        assertEquals(setOf(PERSONAL, WORK), sync.index.profiles())
        device.locales = "en-US"
        device.install(WORK_TRANSIT.copy(label = "仕事用テスト2"), silently = true)
        // 言語の変更で作り直すのは読める個人用だけ。再開を待つ間の同期と再オープンでは繰り返さない
        assertEquals(1, sync.sync().rebuiltApps)
        assertEquals(setOf(PERSONAL, WORK), sync.index.profiles())
        assertEquals(0, sync.sync().rebuiltApps)
        opened.forEach(LocalDatabase::close)
        opened.clear()
        val reopened = newSync()
        assertEquals(0, reopened.sync().rebuiltApps)
        device.unavailable.clear()
        // 指紋も更新時刻も変わらない静かな変更でも、再開後に仕事用だけを読み直す
        assertEquals(1, reopened.sync().rebuiltApps)
        assertEquals(setOf(PERSONAL, WORK), reopened.index.profiles())
        assertEquals("仕事用テスト2", reopened.index.labelOf(WORK_TRANSIT.entry.id))
        assertEquals(0, reopened.sync().rebuiltApps)
    }

    @Test
    fun buildChangeWhilePausedRebuildsPersonalOnceAndWorkAfterResuming() {
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()
        device.unavailable += WORK
        device.fingerprint = "test-build-2"
        val first = sync.sync()
        assertEquals(SyncMode.FULL_REBUILD, first.mode)
        assertEquals(1, first.rebuiltApps)
        val second = sync.sync()
        assertEquals(SyncMode.CHANGES, second.mode)
        assertEquals(0, second.rebuiltApps)
        analyzer.calls = 0
        device.unavailable.clear()
        // 仕事用の入口は旧ビルドで作ったまま。APK が同じでも再開後に新しいビルドで作り直す
        assertEquals(1, sync.sync().rebuiltApps)
        assertTrue(analyzer.calls > 0)
        assertEquals(0, sync.sync().rebuiltApps)
    }

    @Test
    fun failedUserQueryDoesNotTreatAnUnlistedProfileAsRemoved() {
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()
        device.unavailable += WORK
        device.failUserQuery = true
        sync.sync()
        assertEquals(setOf(PERSONAL, WORK), sync.index.profiles())
    }

    @Test
    fun staleDeviceEntriesCannotRestoreAConfirmedRemovedProfile() {
        var existing = setOf(PERSONAL, WORK)
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync(existingProfiles = { existing })
        sync.sync()
        // LauncherApps の一覧が古くても、UserManager で確定した削除が優先する
        existing = setOf(PERSONAL)
        assertEquals(1, sync.sync().removedProfiles)
        assertEquals(setOf(PERSONAL), sync.index.profiles())
        device.locales = "en-US"
        sync.sync()
        assertEquals(setOf(PERSONAL), sync.index.profiles())
        assertEquals(setOf(PERSONAL), opened.last().profiles.serials())
    }

    @Test
    fun profileBecomingUnavailableDuringEnumerationOrReadKeepsTheIndex() {
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()
        device.beforeStamps = { device.unavailable += WORK }
        sync.sync()
        assertEquals(setOf(PERSONAL, WORK), sync.index.profiles())
        device.beforeStamps = {}
        device.unavailable.clear()
        device.beforeRead = { device.unavailable += WORK }
        sync.sync(setOf(WORK_TRANSIT.entry.id.packageName))
        assertEquals(setOf(PERSONAL, WORK), sync.index.profiles())
        device.beforeRead = {}
        device.unavailable.clear()
        device.locales = "en-US"
        device.beforeRead = { device.unavailable += WORK }
        sync.sync()
        assertEquals(setOf(PERSONAL, WORK), sync.index.profiles())
    }

    @Test
    fun requestedPackagesAreRebuiltEvenWithTheSameFingerprint() {
        // 資源の重ね合わせなど、APK の指紋が同じ変更を Callback から知った場合を再現する
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
    fun workUpdateWhileTheScreenIsClosedIsFoundByTheNextSync() {
        device.install(TRANSIT, WORK_TRANSIT)
        newSync().sync()
        device.install(WORK_TRANSIT.copy(label = "仕事用更新テスト", updatedAt = 2_000), silently = true)

        val reopened = newSync()
        assertEquals(1, reopened.sync().rebuiltApps)
        assertEquals("仕事用更新テスト", reopened.index.labelOf(WORK_TRANSIT.entry.id))
    }

    @Test
    fun requestedPackageSurvivesAFailedSync() {
        device.install(WORK_TRANSIT)
        val sync = newSync()
        sync.sync()
        device.install(WORK_TRANSIT.copy(label = "更新テスト"), silently = true)
        device.beforeRead = { error("読み取りの失敗") }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            sync.sync(setOf(WORK_TRANSIT.entry.id.packageName))
        }
        assertEquals("乗換テスト", sync.index.labelOf(WORK_TRANSIT.entry.id))
        device.beforeRead = {}

        assertEquals(1, sync.sync().rebuiltApps)
        assertEquals("更新テスト", sync.index.labelOf(WORK_TRANSIT.entry.id))
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

    @Test
    fun osBuildChangeRebuildsEverything() {
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()
        device.fingerprint = "next-build"
        assertEquals(SyncMode.FULL_REBUILD, sync.sync().mode)
    }

    @Test
    fun incompleteMaterialHasNoFingerprintAndIsRetriedWithoutASequenceChange() {
        device.install(TRANSIT, MAPS)
        val sync = newSync()
        sync.sync()
        val incomplete = TRANSIT.copy(label = "更新テスト").copy(complete = false)
        device.install(incomplete, MAPS.copy(label = "地図更新テスト"))
        val partial = sync.sync()
        assertEquals("地図更新テスト", sync.index.labelOf(MAPS.entry.id))
        assertEquals(1, partial.failedPackages)
        assertEquals(jp.piu.search.data.SyncFailure.READ, sync.status.failure)
        org.junit.Assert.assertNull(
            opened
                .last()
                .index
                .load()
                .apps
                .single { it.app.id == TRANSIT.entry.id }
                .apkFingerprint,
        )
        device.install(incomplete.copy(complete = true), silently = true)
        assertEquals(1, sync.sync().rebuiltApps)
        org.junit.Assert.assertNotNull(
            opened
                .last()
                .index
                .load()
                .apps
                .single { it.app.id == TRANSIT.entry.id }
                .apkFingerprint,
        )
        org.junit.Assert.assertNull(sync.status.failure)
    }

    @Test
    fun failedProfileKeepsItsIndexAndRetriesAfterTheSequenceAdvanced() {
        device.install(TRANSIT, WORK_TRANSIT)
        val sync = newSync()
        sync.sync()
        device.failedProfiles += PERSONAL
        device.install(TRANSIT.copy(label = "個人用更新テスト"))
        device.install(WORK_TRANSIT.copy(label = "仕事用更新テスト", updatedAt = 2_000), silently = true)
        val partial = sync.sync()
        assertEquals(1, partial.failedProfiles)
        assertEquals("乗換テスト", sync.index.labelOf(TRANSIT.entry.id))
        assertEquals("仕事用更新テスト", sync.index.labelOf(WORK_TRANSIT.entry.id))
        device.failedProfiles.clear()
        sync.sync()
        assertEquals("個人用更新テスト", sync.index.labelOf(TRANSIT.entry.id))
    }

    @Test
    fun genresAreRefreshedWithoutRebuildingNameKeys() {
        device.install(TRANSIT)
        val sync = newSync()
        sync.sync()
        analyzer.calls = 0
        device.install(TRANSIT.copy(entry = TRANSIT.entry.copy(genres = listOf(Genres.BROWSER))), silently = true)
        assertEquals(0, sync.sync().rebuiltApps)
        assertEquals(0, analyzer.calls)
        assertTrue(
            sync.index
                .inNameOrder()
                .single()
                .app.keys
                .any { it.kind == jp.piu.search.core.search.SearchKeyKind.GENRE },
        )
        assertTrue(
            opened
                .last()
                .index
                .load()
                .apps
                .single()
                .app.keys
                .any { it.kind == jp.piu.search.core.search.SearchKeyKind.GENRE },
        )
    }

    @Test
    fun backgroundNotificationIsRetainedUntilTheNextExplicitSync() {
        device.install(TRANSIT)
        val sync = newSync()
        sync.sync()
        device.install(TRANSIT.copy(label = "背景の更新テスト"), silently = true)
        sync.packagesChanged(setOf(TRANSIT.entry.id.packageName))
        sync.awaitIdle()
        assertEquals("乗換テスト", sync.index.labelOf(TRANSIT.entry.id))
        assertEquals(1, sync.sync().rebuiltApps)
        assertEquals("背景の更新テスト", sync.index.labelOf(TRANSIT.entry.id))
    }

    @Suppress("DEPRECATION")
    @Test
    fun intentHandlerChangeUpdatesOnlyGenreRowsWithTheSameApk() {
        device.install(TRANSIT, MAPS)
        val manager = context.packageManager
        val resolver = GenreResolver(manager)
        val source =
            object : DeviceApps by device {
                override fun genres(stamps: List<ActivityStamp>) = resolver.resolveStamps(stamps)
            }
        val intent = GENRE_RULES.first { it.genre == Genres.BROWSER }.intent.toIntent()

        fun handler(app: InstalledApp) =
            android.content.pm.ResolveInfo().apply {
                activityInfo =
                    android.content.pm.ActivityInfo().apply {
                        packageName = app.entry.id.packageName
                        name = app.entry.id.activityName
                    }
            }
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setResolveInfosForIntent(intent, listOf(handler(TRANSIT)))
        val sync = newSync(source = source)
        sync.sync()
        val dao = opened.last().room.indexDao()
        val beforeApps = dao.apps()
        val beforeKeys = dao.keys().filter { it.kind != jp.piu.search.core.search.SearchKeyKind.GENRE }
        analyzer.calls = 0
        shadow.setResolveInfosForIntent(intent, listOf(handler(MAPS)))
        val report = sync.sync()
        assertEquals(0, report.rebuiltApps)
        assertEquals(0, analyzer.calls)
        assertEquals(beforeApps, dao.apps())
        assertEquals(beforeKeys, dao.keys().filter { it.kind != jp.piu.search.core.search.SearchKeyKind.GENRE })
        val browserApps =
            sync.index
                .inNameOrder()
                .filter { row ->
                    row.app.keys.any { it.text.value == Genres.BROWSER.value }
                }.map { it.app.id }
        assertEquals(listOf(MAPS.entry.id), browserApps)
    }

    @Test
    fun successfulNotificationIsClearedWhileAnIncompletePackageIsRetained() {
        device.install(TRANSIT, MAPS)
        val sync = newSync()
        sync.sync()
        device.install(TRANSIT.copy(complete = false), silently = true)
        sync.sync(setOf(TRANSIT.entry.id.packageName, MAPS.entry.id.packageName))
        device.install(TRANSIT, MAPS.copy(label = "番号にない変更テスト"), silently = true)
        assertEquals(1, sync.sync().rebuiltApps)
        assertEquals(MAPS.entry.label, sync.index.labelOf(MAPS.entry.id))
    }

    @Test
    fun notificationArrivingDuringReadIsNotClearedByTheOlderSync() {
        device.install(TRANSIT)
        val sync = newSync()
        sync.sync()
        device.beforeRead = {
            device.beforeRead = {}
            sync.packagesChanged(setOf(TRANSIT.entry.id.packageName))
        }
        sync.sync(setOf(TRANSIT.entry.id.packageName))
        assertEquals(1, sync.sync().rebuiltApps)
        assertEquals(0, sync.sync().rebuiltApps)
    }

    @Test
    @Config(sdk = [28, 37], shadows = [CallbackLauncherApps::class])
    fun personalNotificationIsConsumedWhileWorkProfileStaysUnavailable() {
        device.install(TRANSIT, WORK_TRANSIT)
        withWatchingSync { sync, callback, _ ->
            device.unavailable += WORK
            device.install(TRANSIT.copy(label = "個人用更新テスト"), silently = true)
            callback.notifyChanged(TRANSIT.entry.id.packageName, Process.myUserHandle())

            assertEquals(1, sync.sync().rebuiltApps)
            assertEquals("個人用更新テスト", sync.index.labelOf(TRANSIT.entry.id))
            assertEquals(WORK_TRANSIT.entry.label, sync.index.labelOf(WORK_TRANSIT.entry.id))
            analyzer.calls = 0
            assertEquals(0, sync.sync().rebuiltApps)
            assertEquals(0, analyzer.calls)
        }
    }

    @Test
    @Config(sdk = [28, 37], shadows = [CallbackLauncherApps::class])
    fun callbackRebuildsOnlyItsProfileEvenForTheSamePackage() {
        device.install(TRANSIT, WORK_TRANSIT)
        withWatchingSync { sync, callback, workUser ->
            device.install(TRANSIT.copy(label = "個人用の未通知テスト"), WORK_TRANSIT.copy(label = "仕事用更新テスト"), silently = true)
            callback.notifyChanged(WORK_TRANSIT.entry.id.packageName, workUser)

            assertEquals(1, sync.sync().rebuiltApps)
            assertEquals(TRANSIT.entry.label, sync.index.labelOf(TRANSIT.entry.id))
            assertEquals("仕事用更新テスト", sync.index.labelOf(WORK_TRANSIT.entry.id))
            assertEquals(0, sync.sync().rebuiltApps)
        }
    }

    @Test
    @Config(sdk = [28, 37], shadows = [CallbackLauncherApps::class])
    fun successfulPersonalNotificationIsConsumedWhenTheSameWorkPackageIsIncomplete() {
        device.install(TRANSIT, WORK_TRANSIT)
        withWatchingSync { sync, callback, workUser ->
            device.install(
                TRANSIT.copy(label = "個人用更新テスト"),
                WORK_TRANSIT.copy(label = "仕事用更新テスト").copy(complete = false),
                silently = true,
            )
            callback.notifyChanged(TRANSIT.entry.id.packageName, Process.myUserHandle())
            callback.notifyChanged(WORK_TRANSIT.entry.id.packageName, workUser)
            val partial = sync.sync()
            assertEquals(2, partial.rebuiltApps)
            assertEquals(1, partial.failedPackages)

            device.install(WORK_TRANSIT.copy(label = "仕事用回復テスト"), silently = true)
            assertEquals(1, sync.sync().rebuiltApps)
            assertEquals("個人用更新テスト", sync.index.labelOf(TRANSIT.entry.id))
            assertEquals("仕事用回復テスト", sync.index.labelOf(WORK_TRANSIT.entry.id))
            assertEquals(0, sync.sync().rebuiltApps)
        }
    }

    @Test
    @Config(sdk = [28, 37], shadows = [CallbackLauncherApps::class])
    fun callbackWithAnUnknownProfileSerialIsRetainedUntilItCanBeResolved() {
        device.install(TRANSIT, WORK_TRANSIT)
        withWatchingSync { sync, callback, workUser ->
            val users = shadowOf(context.getSystemService(UserManager::class.java))
            users.setSerialNumberForUser(workUser, -1)
            device.install(WORK_TRANSIT.copy(label = "仕事用更新テスト"), silently = true)
            callback.notifyChanged(WORK_TRANSIT.entry.id.packageName, workUser)
            assertEquals(0, sync.sync().rebuiltApps)
            assertEquals(WORK_TRANSIT.entry.label, sync.index.labelOf(WORK_TRANSIT.entry.id))

            users.setSerialNumberForUser(workUser, WORK)
            assertEquals(1, sync.sync().rebuiltApps)
            assertEquals("仕事用更新テスト", sync.index.labelOf(WORK_TRANSIT.entry.id))
            assertEquals(0, sync.sync().rebuiltApps)
        }
    }

    @Test
    @Config(sdk = [28, 37], shadows = [CallbackLauncherApps::class])
    fun resolvedCallbackProfileIsKeptWhenALaterSerialQueryFails() {
        device.install(TRANSIT, WORK_TRANSIT)
        withWatchingSync { sync, callback, workUser ->
            device.install(WORK_TRANSIT.copy(label = "仕事用更新テスト"), silently = true)
            callback.notifyChanged(WORK_TRANSIT.entry.id.packageName, workUser)
            shadowOf(context.getSystemService(UserManager::class.java)).setSerialNumberForUser(workUser, -1)

            assertEquals(1, sync.sync().rebuiltApps)
            assertEquals(TRANSIT.entry.label, sync.index.labelOf(TRANSIT.entry.id))
            assertEquals("仕事用更新テスト", sync.index.labelOf(WORK_TRANSIT.entry.id))
            assertEquals(0, sync.sync().rebuiltApps)
        }
    }

    @Test
    @Config(sdk = [28, 37], shadows = [CallbackLauncherApps::class])
    fun callbackArrivingDuringTheSameProfileReadSurvivesTheOlderCompletion() {
        device.install(TRANSIT, WORK_TRANSIT)
        withWatchingSync { sync, callback, workUser ->
            callback.notifyChanged(WORK_TRANSIT.entry.id.packageName, workUser)
            device.beforeRead = {
                device.beforeRead = {}
                callback.notifyChanged(WORK_TRANSIT.entry.id.packageName, workUser)
            }
            assertEquals(1, sync.sync().rebuiltApps)
            assertEquals(1, sync.sync().rebuiltApps)
            assertEquals(0, sync.sync().rebuiltApps)
        }
    }

    private fun withWatchingSync(test: (IndexSync, CallbackLauncherApps, UserHandle) -> Unit) {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        val workUser = UserHandle.getUserHandleForUid(10 * 100_000)
        // ユーザー id と保存用のシリアル番号を取り違えないことも確かめる
        users.setSerialNumberForUser(Process.myUserHandle(), PERSONAL)
        users.setSerialNumberForUser(workUser, WORK)
        val sync = newSync(watch = true)
        sync.sync()
        sync.startWatching()
        try {
            test(sync, shadowOf(context.getSystemService(LauncherApps::class.java)) as CallbackLauncherApps, workUser)
        } finally {
            sync.stopWatching()
            sync.awaitIdle()
        }
    }

    /** OS からの通知元を任意にして、実際に登録された Callback を通す。 */
    @Implements(LauncherApps::class)
    class CallbackLauncherApps : ShadowLauncherApps() {
        private var registered: LauncherApps.Callback? = null

        @Implementation
        override fun registerCallback(
            callback: LauncherApps.Callback,
            handler: Handler,
        ) {
            registered = callback
        }

        @Implementation
        override fun unregisterCallback(callback: LauncherApps.Callback) {
            if (registered === callback) registered = null
        }

        fun notifyChanged(
            packageName: String,
            user: UserHandle,
        ) {
            checkNotNull(registered).onPackageChanged(packageName, user)
        }
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
