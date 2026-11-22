// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 索引の書き手が同期の規則を正しくつなぎ、写し・知らせ・未保存の印を守ることを、偽の読み手と本物の Room（Robolectric）で確かめる。
// 規則そのものの性質（法則 1〜5）は core:state で確かめてあるので、ここでは書き手の順序と保存を見る
// runCurrent（仮想の時計を進めずに、今動ける coroutine を動かす）は 1.9 で実験的な API
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class IndexActorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reader = FakeReader()
    private val keys = FakeKeys()
    private val opened = ArrayList<IndexDatabase>()

    @After
    fun close() {
        opened.forEach(IndexDatabase::close)
        context.deleteDatabase(FILE)
    }

    @Test
    fun theFirstSyncPublishesAndSavesAndAReopenedWriterRestoresTheSameSnapshot() =
        runTest {
            reader.install(MAPS, app("地図", listOf("CN=Maps")))
            reader.install(WORK_MAIL, app("メール"))
            val actor = actor(FaultyIndexStorage(database()))
            actor.requestSync().await()
            val snapshot = actor.snapshot.value
            assertEquals(setOf(MAPS, WORK_MAIL), snapshot.packages.keys)
            assertTrue("pub:CN=Maps" in snapshot.keyTexts(MAPS))
            assertFalse(actor.status.value.unsaved)
            assertNotNull(actor.status.value.lastSavedAt)

            val reopened = actor(FaultyIndexStorage(database()))
            runCurrent()
            assertEquals(snapshot, reopened.snapshot.value)
        }

    @Test
    fun aSecondSyncWithoutChangesReadsNoMaterialsBuildsNoKeysAndWritesNothing() =
        runTest {
            reader.install(MAPS, app("地図"))
            reader.install(WORK_MAIL, app("メール"))
            val storage = FaultyIndexStorage(database())
            val actor = actor(storage)
            actor.requestSync().await()
            val reads = reader.reads.size
            val builds = keys.calls
            val saves = storage.saves.size

            actor.requestSync().await()
            assertEquals(reads, reader.reads.size)
            assertEquals(builds, keys.calls)
            assertEquals(saves, storage.saves.size)
            assertEquals(2, reader.observeCalls)
        }

    @Test
    fun aFailedSaveKeepsThePublishedSnapshotAndTheNextSyncRewritesItWithoutReadingMaterials() =
        runTest {
            reader.install(MAPS, app("地図"))
            val storage = FaultyIndexStorage(database()).apply { failSaves = 1 }
            val actor = actor(storage)
            actor.requestSync().await()
            val published = actor.snapshot.value
            assertEquals(setOf(MAPS), published.packages.keys)
            assertTrue(actor.status.value.unsaved)
            assertTrue(storage.saves.isEmpty())

            // 同じ入力の次の同期。材料もキーも作らず、今の写しを丸ごと書き直す
            val reads = reader.reads.size
            val builds = keys.calls
            actor.requestSync().await()
            assertEquals(reads, reader.reads.size)
            assertEquals(builds, keys.calls)
            assertEquals(listOf(published), storage.saves)
            assertFalse(actor.status.value.unsaved)

            // 開き直すと同じ写しが戻る
            assertEquals(published, database().load().snapshot)
        }

    @Test
    fun retryingTheSaveAloneWritesTheSnapshotWithoutObservingTheDevice() =
        runTest {
            reader.install(MAPS, app("地図"))
            val storage = FaultyIndexStorage(database()).apply { failSaves = 2 }
            val actor = actor(storage)
            actor.requestSync().await()
            actor.retrySave().await()
            assertTrue(actor.status.value.unsaved)
            actor.retrySave().await()
            assertFalse(actor.status.value.unsaved)
            assertEquals(1, reader.observeCalls)
            assertEquals(actor.snapshot.value, database().load().snapshot)
        }

    @Test
    fun aNoticeIsDroppedOnlyWhenTheSnapshotItWasAppliedToIsSaved() =
        runTest {
            reader.install(MAPS, app("地図"))
            reader.serials[OWN_USER] = OWN
            val storage = FaultyIndexStorage(database())
            val actor = actor(storage)
            actor.requestSync().await()
            storage.failSaves = 1
            reader.reads.clear()
            reader.install(MAPS, app("地図2").copy(apkKey = "apk-2"))
            actor.packagesChanged(listOf(MAPS.packageName), OWN_USER)
            actor.requestSync().await()
            assertEquals(listOf(MAPS), reader.reads)
            // 当てたが保存できていない。読み直す対象にはしない（保存待ち）
            assertEquals(0 to 1, actor.pendingNotices())
            actor.requestSync().await()
            assertEquals(listOf(MAPS), reader.reads)
            assertEquals(0 to 0, actor.pendingNotices())
        }

    @Test
    fun aNoticeReceivedDuringASyncIsNotConsumedByItAndTheFollowingSyncReadsIt() =
        runTest {
            reader.install(MAPS, app("地図"))
            reader.install(MAIL, app("メール"))
            reader.serials[OWN_USER] = OWN
            val actor = actor(FaultyIndexStorage(database()))
            actor.setForeground(true)
            actor.requestSync().await()
            reader.reads.clear()

            // 地図の知らせで始めた同期の途中に、メールの知らせが届く
            var sent = false
            reader.duringRead = {
                if (!sent) {
                    sent = true
                    actor.packagesChanged(listOf(MAIL.packageName), OWN_USER)
                }
            }
            actor.packagesChanged(listOf(MAPS.packageName), OWN_USER)
            runCurrent()
            // 前面なので、続く同期が 1 回起きてメールを読む
            assertEquals(listOf(MAPS, MAIL), reader.reads)
            assertEquals(0 to 0, actor.pendingNotices())
        }

    @Test
    fun aNoticeReceivedDuringASyncIsHeldWhileInTheBackground() =
        runTest {
            reader.install(MAPS, app("地図"))
            reader.serials[OWN_USER] = OWN
            val actor = actor(FaultyIndexStorage(database()))
            actor.requestSync().await()
            reader.reads.clear()
            reader.duringRead = { actor.packagesChanged(listOf(MAPS.packageName), OWN_USER) }
            reader.install(MAPS, app("地図2").copy(apkKey = "apk-2"))
            actor.requestSync().await()
            runCurrent()
            assertEquals(listOf(MAPS), reader.reads)
            assertEquals(1 to 0, actor.pendingNotices())

            // 裏の間は溜めるだけ。前面になったら同期する
            reader.duringRead = {}
            actor.packagesChanged(listOf(MAIL.packageName), OWN_USER)
            runCurrent()
            assertEquals(2 to 0, actor.pendingNotices())
            actor.setForeground(true)
            runCurrent()
            assertEquals(listOf(MAPS, MAPS), reader.reads)
            assertEquals(0 to 0, actor.pendingNotices())
        }

    @Test
    fun aNoticeWhoseSerialCannotBeResolvedStaysAndIsReadWhenItIsResolved() =
        runTest {
            reader.install(WORK_MAIL, app("メール"))
            val actor = actor(FaultyIndexStorage(database()))
            actor.requestSync().await()
            reader.reads.clear()
            actor.packagesChanged(listOf(WORK_MAIL.packageName), WORK_USER)
            actor.requestSync().await()
            assertTrue(reader.reads.isEmpty())
            assertEquals(1 to 0, actor.pendingNotices())

            reader.serials[WORK_USER] = WORK
            actor.requestSync().await()
            assertEquals(listOf(WORK_MAIL), reader.reads)
            assertEquals(0 to 0, actor.pendingNotices())
        }

    @Test
    fun aNoticeForAProfileThatCannotBeReadStaysAfterTheSync() =
        runTest {
            reader.install(WORK_MAIL, app("メール"))
            reader.serials[WORK_USER] = WORK
            val actor = actor(FaultyIndexStorage(database()))
            actor.requestSync().await()
            val before = actor.snapshot.value.packages[WORK_MAIL]
            reader.statuses[WORK] = ListingStatus.UNAVAILABLE
            actor.packagesChanged(listOf(WORK_MAIL.packageName), WORK_USER)
            actor.requestSync().await()
            assertEquals(1 to 0, actor.pendingNotices())
            assertEquals(before, actor.snapshot.value.packages[WORK_MAIL])

            reader.statuses.remove(WORK)
            reader.reads.clear()
            actor.requestSync().await()
            assertEquals(listOf(WORK_MAIL), reader.reads)
            assertEquals(0 to 0, actor.pendingNotices())
        }

    @Test
    fun localeOsAndAppUpdatesWhileStoppedAreReadAfterRestarting() =
        runTest {
            reader.install(MAPS, app("地図"))
            reader.install(MAIL, app("メール"))
            reader.failures[MAIL] = Stage.SIGNER to BUG
            val first = actor(FaultyIndexStorage(database()))
            first.requestSync().await()
            assertEquals(
                Stage.SIGNER,
                first.snapshot.value.packages[MAIL]
                    ?.attempt
                    ?.issue
                    ?.stage,
            )
            reader.failures.clear()

            // このアプリの更新だけ: 欠けのある組だけを読み直す
            reader.reads.clear()
            val updated = actor(FaultyIndexStorage(database()), appVersion = 2)
            updated.requestSync().await()
            assertEquals(listOf(MAIL), reader.reads)
            assertNull(
                updated.snapshot.value.packages[MAIL]
                    ?.attempt
                    ?.issue,
            )

            // 言語と OS の更新: すべての組を読み直す
            reader.reads.clear()
            reader.environment = reader.environment.copy(locale = "en-US", osBuild = "build-2")
            val restarted = actor(FaultyIndexStorage(database()), appVersion = 2)
            restarted.requestSync().await()
            assertEquals(setOf(MAPS, MAIL), reader.reads.toSet())
            assertEquals(
                "en-US",
                restarted.snapshot.value.packages[MAPS]
                    ?.current
                    ?.materialKey
                    ?.locale,
            )
        }

    @Test
    fun changedEntriesWithTheSameApkAndArchivingAndRestoringAreRead() =
        runTest {
            reader.install(MAPS, app("地図", listOf("CN=Maps")))
            val actor = actor(FaultyIndexStorage(database()))
            actor.requestSync().await()

            reader.reads.clear()
            reader.install(MAPS, FakeApp(mapOf("Main" to "地図", "Nav" to "ナビ"), signers = listOf("CN=Maps")))
            actor.requestSync().await()
            assertEquals(listOf(MAPS), reader.reads)
            assertEquals(
                setOf("Main", "Nav"),
                actor.snapshot.value.packages[MAPS]
                    ?.observed
                    ?.entries,
            )

            // アーカイブ: 補助の材料を読まないので、提供元のキーが消える。復元で戻る
            reader.install(MAPS, reader.app(MAPS).copy(archived = true))
            actor.requestSync().await()
            assertFalse("pub:CN=Maps" in actor.snapshot.value.keyTexts(MAPS))
            reader.install(MAPS, reader.app(MAPS).copy(archived = false))
            actor.requestSync().await()
            assertTrue("pub:CN=Maps" in actor.snapshot.value.keyTexts(MAPS))
            assertEquals(3, reader.reads.size)
        }

    @Test
    fun aSignerThatBecomesReadableFixesThePublisherKeyOnTheRetry() =
        runTest {
            reader.install(MAPS, app("地図", listOf("CN=Maps")))
            reader.failures[MAPS] = Stage.SIGNER to TRANSIENT
            val actor = actor(FaultyIndexStorage(database()))
            actor.requestSync().await()
            assertFalse("pub:CN=Maps" in actor.snapshot.value.keyTexts(MAPS))
            assertEquals(
                true,
                actor.snapshot.value.packages[MAPS]
                    ?.attempt
                    ?.retry,
            )

            // 一時的な失敗の再試行の枠で、次の同期に 1 回だけ読み直す
            reader.failures.clear()
            actor.requestSync().await()
            assertTrue("pub:CN=Maps" in actor.snapshot.value.keyTexts(MAPS))
            assertNull(
                actor.snapshot.value.packages[MAPS]
                    ?.attempt
                    ?.issue,
            )
        }

    @Test
    fun keysThatFailedAreRebuiltWhenTheGeneratorChanges() =
        runTest {
            reader.install(MAPS, app("地図"))
            keys.failing += MAPS
            val actor = actor(FaultyIndexStorage(database()))
            actor.requestSync().await()
            assertEquals(
                Stage.KEYS,
                actor.snapshot.value.packages[MAPS]
                    ?.attempt
                    ?.issue
                    ?.stage,
            )
            assertFalse("name:地図" in actor.snapshot.value.keyTexts(MAPS))

            // 不具合の疑いは自動で再試行しない。生成器を直して版を上げると作り直す
            keys.failing.clear()
            actor.requestSync().await()
            assertFalse("name:地図" in actor.snapshot.value.keyTexts(MAPS))
            keys.generatorVersion = 8
            actor.requestSync().await()
            assertTrue("name:地図" in actor.snapshot.value.keyTexts(MAPS))
            assertNull(
                actor.snapshot.value.packages[MAPS]
                    ?.attempt
                    ?.issue,
            )
        }

    @Test
    fun aRemovedProfileIsDroppedFromTheSnapshot() =
        runTest {
            reader.install(MAPS, app("地図"))
            reader.install(WORK_MAIL, app("メール"))
            val actor = actor(FaultyIndexStorage(database()))
            actor.requestSync().await()
            reader.profiles.remove(WORK)
            actor.requestSync().await()
            assertEquals(setOf(MAPS), actor.snapshot.value.packages.keys)
            assertEquals(
                setOf(MAPS),
                database()
                    .load()
                    .snapshot.packages.keys,
            )
        }

    @Test
    fun anUnreadableIndexStartsFromEmptyAndTheFirstSyncRewritesEverything() =
        runTest {
            reader.install(MAPS, app("地図"))
            actor(FaultyIndexStorage(database())).requestSync().await()
            val storage = FaultyIndexStorage(database()).apply { failLoads = 1 }
            reader.reads.clear()
            val actor = actor(storage)
            runCurrent()
            assertEquals(IndexSnapshot.EMPTY, actor.snapshot.value)
            assertEquals("java.io.IOException", actor.status.value.loadFailure)
            // 前面になっても、同期の前に空の写しで上書きしない
            actor.setForeground(true)
            runCurrent()
            assertTrue(storage.saves.isEmpty())

            actor.requestSync().await()
            assertEquals(listOf(MAPS), reader.reads)
            assertEquals(1, storage.saves.size)
        }

    @Test
    fun manyConcurrentSendersLeaveTheSnapshotEqualToTheDatabase() {
        // 本物のスレッドで、書き手も多くの送り手も並行に動かす
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val names = (0 until 6).map { "io.example.app$it" }
            names.forEach { reader.install(PackageKey(OWN, it), app(it)) }
            reader.serials[OWN_USER] = OWN
            val storage = FaultyIndexStorage(database())
            val actor = IndexActor(scope, storage, reader, keys, 1, Dispatchers.IO)
            runBlocking(Dispatchers.Default) {
                val senders =
                    (0 until 8).map { sender ->
                        async(Dispatchers.Default) {
                            (0 until 30).flatMap { i ->
                                val name = names[(sender + i) % names.size]
                                actor.setForeground(i % 3 != 0)
                                actor.packagesChanged(listOf(name), OWN_USER)
                                if (i % 5 == 0) listOf(actor.requestSync()) else emptyList()
                            }
                        }
                    }
                withTimeout(TIMEOUT) {
                    senders.awaitAll().flatten().awaitAll()
                    actor.requestSync().await()
                }
            }
            runBlocking { withTimeout(TIMEOUT) { assertEquals(0 to 0, actor.pendingNotices()) } }
            assertEquals(storage.saves.last(), actor.snapshot.value)
            assertEquals(actor.snapshot.value, database().load().snapshot)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSecondWriterForTheSameDatabaseIsRefusedUntilTheFirstStops() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val open = {
            IndexActor.open(context, FILE, scope, 1, reader, keys, Dispatchers.IO) { database() }
        }
        val first = open()
        val refused = runCatching { open() }
        assertTrue(refused.exceptionOrNull() is IllegalStateException)
        runBlocking {
            scope.cancel()
            first.job.join()
        }
        val again = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            IndexActor.open(context, FILE, again, 1, reader, keys, Dispatchers.IO) { database() }
        } finally {
            again.cancel()
        }
    }

    private fun database(): IndexDatabase = IndexDatabase.open(context, FILE, allowMainThread = true).also(opened::add)

    private fun TestScope.actor(
        storage: IndexStorage,
        appVersion: Int = 1,
    ): IndexActor = IndexActor(backgroundScope, storage, reader, keys, appVersion, StandardTestDispatcher(testScheduler), clock = { NOW })

    private companion object {
        const val FILE = "m802b-index-actor-test.db"
        const val NOW = 1_700_000_000_000L
        const val TIMEOUT = 30_000L
        val MAPS = PackageKey(OWN, "io.example.maps")
        val MAIL = PackageKey(OWN, "io.example.mail")
        val WORK_MAIL = PackageKey(WORK, "io.example.mail")
    }
}
