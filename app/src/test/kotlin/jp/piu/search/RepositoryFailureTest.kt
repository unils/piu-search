// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.LaunchStatStorage
import jp.piu.search.data.LearnedStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor

// 保存の失敗の後も、差分の基準と取り消しの材料が DB と一致することを固定する。
@RunWith(AndroidJUnit4::class)
class RepositoryFailureTest {
    private val queue = ArrayDeque<Runnable>()
    private val executor = Executor { queue.add(it) }
    private val learned = FakeLearnedStorage()
    private val repository = LearningRepository({ learned }, executor)

    @Test
    fun aRecordIsOneJobAndOnlyNotifiesAfterSaving() {
        learned.onRead = { assertEquals(LoadState.Loading, repository.loadState) }
        learned.onWrite = {
            assertTrue(repository.memory.records.isEmpty())
            assertTrue(repository.takeNotices().isEmpty())
        }
        repository.addListener { snapshot ->
            assertEquals(learned.memory.records, snapshot.records)
            assertTrue(repository.takeNotices().isEmpty())
        }
        var completed = false
        repository.record(RESCUE, 1, onDone = {
            assertTrue(it is StorageResult.Success)
            assertEquals(1, repository.takeNotices().size)
            completed = true
        }) { "アプリ" }
        assertEquals(1, queue.size)
        assertFalse(completed)
        drain()
        assertTrue(completed)
    }

    @Test
    fun failedRecordDoesNotPublishOrCreateNoticesAndRetryKeepsCounts() {
        record()
        drain()
        val before = repository.memory
        var heard = 0
        repository.addListener { heard++ }
        var result: StorageResult<List<RescueNotice>>? = null
        learned.failWrite = true
        repository.record(RESCUE, 2, onDone = { result = it }) { "アプリ" }
        assertTrue(repository.takeNotices().isEmpty())
        drain()
        assertTrue(result is StorageResult.Failure)
        assertSame(before, repository.memory)
        assertEquals(0, heard)
        assertTrue(repository.takeNotices().isEmpty())
        learned.failWrite = false
        repository.record(RESCUE, 3, onDone = { result = it }) { "アプリ" }
        drain()
        assertTrue(result is StorageResult.Success)
        assertEquals(
            2,
            learned.memory.records
                .getValue(PAIR)
                .count,
        )
        assertEquals(1, repository.takeNotices().size)
        assertEquals(repository.memory.records, learned.memory.records)
    }

    @Test
    fun failedDeleteThenSuccessfulDeleteAndRecordDoesNotRestoreDeletedRows() {
        record()
        drain()
        val before = repository.memory
        learned.failWrite = true
        var result: StorageResult<Unit>? = null
        repository.remove(PAIR) { result = it }
        drain()
        assertTrue(result is StorageResult.Failure)
        assertSame(before, repository.memory)
        learned.failWrite = false
        repository.remove(PAIR) { result = it }
        drain()
        record()
        drain()
        assertEquals(
            1,
            learned.memory.records
                .getValue(PAIR)
                .count,
        )
        assertEquals(repository.memory.records, learned.memory.records)
    }

    @Test
    fun failedUndoRetainsItsMaterialAndFailedClearRetainsNotices() {
        record()
        repository.record(RESCUE, 2) { "アプリ" }
        drain()
        val notice = repository.takeNotices().single()
        repository.returnNotices(listOf(notice))
        val before = repository.memory
        learned.failWrite = true
        var result: StorageResult<Unit>? = null
        repository.clear { result = it }
        drain()
        assertTrue(result is StorageResult.Failure)
        assertEquals(listOf(notice), repository.takeNotices())
        repository.undo(notice) { result = it }
        drain()
        assertTrue(result is StorageResult.Failure)
        assertSame(before, repository.memory)
        learned.failWrite = false
        repository.undo(notice) { result = it }
        drain()
        assertTrue(result is StorageResult.Success)
        assertFalse(
            learned.memory.records
                .getValue(PAIR)
                .rescued,
        )
        assertEquals(
            1,
            learned.memory.records
                .getValue(PAIR)
                .count,
        )
    }

    @Test
    fun failedLoadRejectsAllWritesAndExportsThenNextOperationRetries() {
        learned.memory = LearnedMemory.EMPTY.recorded(SELECTION, 1).recorded(SELECTION, 2)
        learned.failRead = true
        assertEquals(LoadState.NotLoaded, repository.loadState)
        var result: StorageResult<Unit>? = null
        repository.requestLoad()
        drain()
        assertTrue(repository.loadState is LoadState.Failed)
        repository.remove(PAIR) { result = it }
        repository.clear { result = it }
        repository.suppress(PAIR, 3) { result = it }
        repository.forgetExpired(Long.MAX_VALUE) { result = it }
        record()
        var imported: BackupReadResult? = null
        repository.import(PROFILES, 3, read = { file() }, onDone = { imported = it })
        var wrote = false
        repository.export(PROFILES, 3, write = { wrote = true }, onDone = { assertNull(it) })
        drain()
        assertTrue(result is StorageResult.Failure)
        assertEquals(BackupReadResult.Failure(BackupFailure.DATABASE_READ), imported)
        assertEquals(0, learned.writes)
        assertFalse(wrote)
        assertTrue(repository.memory.records.isEmpty())
        learned.failRead = false
        record()
        drain()
        assertTrue(repository.loadState is LoadState.Ready)
        assertEquals(
            3,
            learned.memory.records
                .getValue(PAIR)
                .count,
        )
    }

    @Test
    fun failedImportSuppressAndExpiryLeaveTheSnapshotUnchanged() {
        record()
        drain()
        val before = repository.memory
        learned.failWrite = true
        var imported: BackupReadResult? = null
        repository.import(PROFILES, 3, read = { file() }, onDone = { imported = it })
        drain()
        assertEquals(BackupReadResult.Failure(BackupFailure.DATABASE_WRITE), imported)
        var result: StorageResult<Unit>? = null
        repository.suppress(PAIR, 4) { result = it }
        drain()
        assertTrue(result is StorageResult.Failure)
        repository.forgetExpired(1 + LearningConfig.DEFAULT.forgetAfterMillis) { result = it }
        drain()
        assertTrue(result is StorageResult.Failure)
        assertSame(before, repository.memory)
        learned.failWrite = false
        repository.forgetExpired(1 + LearningConfig.DEFAULT.forgetAfterMillis) { result = it }
        drain()
        assertTrue(result is StorageResult.Success)
        assertTrue(learned.memory.records.isEmpty())
    }

    @Test
    fun launchFailuresKeepTheHistoryAndRetryLoadsExistingCounts() {
        val storage = FakeLaunchStorage()
        storage.history = LaunchHistory.EMPTY.recorded(APP, 1)
        storage.failRead = true
        val launches = LaunchHistoryRepository({ storage }, executor)
        var result: StorageResult<Unit>? = null
        launches.record(APP, 2) { result = it }
        drain()
        assertTrue(result is StorageResult.Failure)
        assertEquals(0, storage.writes)
        assertTrue(launches.loadState is LoadState.Failed)
        storage.failRead = false
        launches.requestLoad()
        drain()
        val before = launches.history
        var heard = 0
        launches.addListener { heard++ }
        storage.failWrite = true
        launches.record(APP, 3) { result = it }
        drain()
        assertTrue(result is StorageResult.Failure)
        assertSame(before, launches.history)
        launches.clear { result = it }
        drain()
        assertTrue(result is StorageResult.Failure)
        assertSame(before, launches.history)
        assertEquals(0, heard)
        storage.failWrite = false
        launches.record(APP, 4) { result = it }
        drain()
        assertTrue(result is StorageResult.Success)
        assertEquals(
            2,
            launches.history.records
                .getValue(APP)
                .count,
        )
        assertEquals(storage.history.records, launches.history.records)
    }

    @Test
    fun logsExcludeIdentifiersUrisAndExceptionMessages() {
        ShadowLog.clear()
        learned.failRead = true
        repository.requestLoad()
        drain()
        learned.failRead = false
        record()
        drain()
        learned.failWrite = true
        repository.record(RESCUE, 3) { "アプリ" }
        repository.export(PROFILES, 3, write = { throw IOException(SECRET) }, onDone = {})
        repository.import(PROFILES, 3, read = { throw IOException(SECRET) }, onDone = {})
        val launchStorage = FakeLaunchStorage().apply { failRead = true }
        val launches = LaunchHistoryRepository({ launchStorage }, executor)
        launches.record(APP, 3)
        drain()
        launchStorage.failRead = false
        launchStorage.failWrite = true
        launches.record(APP, 4)
        drain()
        val logs = ShadowLog.getLogs().filter { it.tag in setOf("PiuLearning", "PiuLaunchHistory") }
        assertTrue(logs.isNotEmpty())
        logs.forEach {
            assertFalse(it.msg.contains(APP.packageName))
            assertFalse(it.msg.contains(APP.activityName))
            assertFalse(it.msg.contains("content://"))
            assertNull(it.throwable)
        }
    }

    private fun record() = repository.record(SELECTION, 1) { "アプリ" }

    private fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    private fun file() = LearningBackup.encode(LearnedMemory.EMPTY.recorded(RESCUE, 1), PROFILES, 1)

    private class FakeLearnedStorage : LearnedStorage {
        var memory = LearnedMemory.EMPTY
        var failRead = false
        var failWrite = false
        var writes = 0
        var onRead: () -> Unit = {}
        var onWrite: () -> Unit = {}

        override fun load(): LearnedMemory {
            onRead()
            check(!failRead) { SECRET }
            return memory
        }

        override fun save(
            before: LearnedMemory,
            after: LearnedMemory,
        ) {
            onWrite()
            check(!failWrite) { SECRET }
            assertEquals(memory.records, before.records)
            memory = after
            writes++
        }

        override fun clear() {
            check(!failWrite) { SECRET }
            memory = LearnedMemory.EMPTY
            writes++
        }
    }

    private class FakeLaunchStorage : LaunchStatStorage {
        var history = LaunchHistory.EMPTY
        var failRead = false
        var failWrite = false
        var writes = 0

        override fun load(): LaunchHistory {
            check(!failRead) { SECRET }
            return history
        }

        override fun record(
            app: AppId,
            at: Long,
        ) {
            check(!failWrite) { SECRET }
            history = history.recorded(app, at)
            writes++
        }

        override fun clear() {
            check(!failWrite) { SECRET }
            history = LaunchHistory.EMPTY
            writes++
        }
    }

    private companion object {
        val APP = AppId("io.example.privateapp", "PrivateActivity", 0)
        val PAIR = LearnedPair(NormalizedText("さがす"), APP)
        val SELECTION = LearningDecision(PAIR, emptyList())
        val RESCUE = LearningDecision(null, listOf(PAIR))
        val PROFILES = BackupProfiles(0, emptySet())
        const val SECRET = "io.example.privateapp PrivateActivity content://io.example.files/private"
    }
}
