// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// 計算を明示的に止め、経過時間の比較に頼らず書き込みと前提の再確認を確かめる。
@RunWith(AndroidJUnit4::class)
class IndexSyncConcurrencyTest {
    @Test
    fun requestedSyncDoesNotBlockLearningWhileReadingApps() = checkLearningCanBeSaved(direct = false)

    @Test
    fun directSyncDoesNotBlockLearningWhileReadingApps() = checkLearningCanBeSaved(direct = true)

    private fun checkLearningCanBeSaved(direct: Boolean) =
        withFixture { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.device.beforeRead = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            val caller = Executors.newSingleThreadExecutor()
            try {
                val finished =
                    if (direct) {
                        caller.submit<SyncReport> { fixture.sync.sync() }
                    } else {
                        fixture.sync.requestSync()
                        null
                    }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val saved =
                    FutureTask {
                        val memory = LearnedMemory.EMPTY.recorded(LearningDecision(PAIR, emptyList()), 1)
                        fixture.database.learned.save(LearnedMemory.EMPTY, memory)
                        fixture.database.learned
                            .load()
                            .records
                    }
                fixture.writer.execute(saved)
                // 読み取りを再開する前に保存が終わることを求める。タイムアウトを延ばして計算の終了を待たない。
                assertEquals(setOf(PAIR), saved.get(3, TimeUnit.SECONDS).keys)
                assertEquals(1L, release.count)
                if (finished != null) assertFalse(finished.isDone)
            } finally {
                release.countDown()
                caller.shutdown()
                assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS))
                fixture.sync.awaitIdle()
            }
        }

    @Test
    fun profileRemovedDuringCalculationIsNotRestoredByTheOldResult() =
        withFixture { fixture ->
            fixture.device.install(WORK_APP)
            fixture.sync.sync()
            fixture.writer.run {
                fixture.database.learned.save(
                    LearnedMemory.EMPTY,
                    LearnedMemory.EMPTY.recorded(LearningDecision(WORK_PAIR, emptyList()), 1),
                )
                fixture.database.launchStats.record(WORK_APP.entry.id, 1)
            }
            fixture.device.locales = "en-US"
            fixture.withPausedRead { finished ->
                fixture.writer.run {
                    fixture.device.removeProfile(WORK)
                    fixture.profiles.reconcile()
                    assertEquals(setOf(PERSONAL), fixture.database.profiles.serials())
                }
                assertFalse(finished.isDone)
            }
            assertEquals(setOf(PERSONAL), fixture.writer.run { fixture.database.profiles.serials() })
            assertEquals(
                setOf(APP.entry.id),
                fixture.sync.index
                    .inNameOrder()
                    .map { it.app.id }
                    .toSet(),
            )
            assertTrue(
                fixture.writer.run {
                    fixture.database.learned
                        .load()
                        .records
                        .isEmpty()
                },
            )
            assertTrue(
                fixture.writer.run {
                    fixture.database.launchStats
                        .load()
                        .records
                        .isEmpty()
                },
            )
            assertTrue(fixture.reads >= 2)
        }

    @Test
    fun storedIndexVersionChangedDuringCalculationDiscardsTheOldResult() =
        withFixture { fixture ->
            fixture.sync.sync()
            fixture.device.locales = "en-US"
            val published = ArrayList<String?>()
            fixture.sync.addListener { published += it.labelOf(APP.entry.id) }
            fixture.withPausedRead {
                fixture.writer.run {
                    IndexStore(fixture.database.room, indexVersion = 999).replaceAll(
                        listOf(StoredApp(IndexedApp(APP.entry.id, "保存された別の版", emptyList()), 2)),
                        SyncState(0, 1, "en-US"),
                    )
                }
                fixture.device.install(APP.copy(entry = APP.entry.copy(label = "新しいテスト")))
            }
            assertEquals("新しいテスト", fixture.sync.index.labelOf(APP.entry.id))
            assertEquals(
                "新しいテスト",
                fixture.writer.run {
                    fixture.database.index
                        .load()
                        .apps
                        .single()
                        .app.label
                },
            )
            assertFalse(published.contains(APP.entry.label))
            assertTrue(fixture.reads >= 2)
        }

    @Test
    fun savedSyncStateChangedDuringDeltaCalculationDiscardsTheOldResult() =
        withFixture { fixture ->
            fixture.sync.sync()
            val stale = APP.copy(entry = APP.entry.copy(label = "古い計算テスト"), packageUpdatedAt = 2)
            fixture.device.install(stale)
            val published = ArrayList<String?>()
            fixture.sync.addListener { published += it.labelOf(APP.entry.id) }
            fixture.withPausedRead {
                fixture.writer.run {
                    fixture.database.index.replaceAll(
                        listOf(StoredApp(IndexedApp(APP.entry.id, "別の保存テスト", emptyList()), 2)),
                        SyncState(0, 999, "ja-JP"),
                    )
                }
                fixture.device.install(APP.copy(entry = APP.entry.copy(label = "新しいテスト"), packageUpdatedAt = 3))
            }
            assertEquals("新しいテスト", fixture.sync.index.labelOf(APP.entry.id))
            assertEquals(
                "新しいテスト",
                fixture.writer.run {
                    fixture.database.index
                        .load()
                        .apps
                        .single()
                        .app.label
                },
            )
            assertFalse(published.contains(stale.entry.label))
            assertTrue(fixture.reads >= 2)
        }

    private fun withFixture(test: (Fixture) -> Unit) {
        LocalDatabase.open(ApplicationProvider.getApplicationContext(), null, allowMainThread = true).use { database ->
            DatabaseWriter().use { writer ->
                val fixture = Fixture(database, writer)
                try {
                    test(fixture)
                } finally {
                    fixture.sync.awaitIdle()
                }
            }
        }
    }

    private class Fixture(
        val database: LocalDatabase,
        val writer: DatabaseWriter,
    ) {
        val device = FakeDevice().apply { install(APP) }
        val profiles = ProfileReconciler({ database.profiles }, device::existingProfiles)
        private val pause = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()
        var reads = 0
        private val source =
            object : DeviceApps by device {
                override fun read(packages: Set<String>?): List<InstalledApp> {
                    val result = device.read(packages)
                    reads++
                    pause.getAndSet(null)?.let { (entered, release) ->
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    return result
                }
            }
        private val analyzer = CountingAnalyzer()
        private val generator = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList())))
        val sync = IndexSync(database.index, source, { generator }, analyzer, null, writer, profiles)

        fun withPausedRead(change: (Future<SyncReport>) -> Unit) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            pause.set(entered to release)
            reads = 0
            val caller = Executors.newSingleThreadExecutor()
            val finished = caller.submit<SyncReport> { sync.sync() }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                change(finished)
            } finally {
                release.countDown()
                try {
                    finished.get(10, TimeUnit.SECONDS)
                } finally {
                    caller.shutdown()
                    assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS))
                }
            }
        }
    }

    private companion object {
        val APP = InstalledApp(AppEntry(AppId("io.example.app", "Main", PERSONAL), "元のテスト"), 1)
        val WORK_APP = APP.copy(entry = APP.entry.copy(id = APP.entry.id.copy(profileSerial = WORK)))
        val PAIR = LearnedPair(NormalizedText("さがす"), APP.entry.id)
        val WORK_PAIR = PAIR.copy(app = WORK_APP.entry.id)
    }
}
