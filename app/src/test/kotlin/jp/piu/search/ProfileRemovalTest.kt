// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.LaunchStatStorage
import jp.piu.search.data.LearnedStorage
import jp.piu.search.data.ProfileReconciler
import jp.piu.search.data.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.concurrent.Executor

// 削除の後ろに積まれた操作が、消えたプロファイルや古い取り消し材料を復活させないことを固定する。
@RunWith(AndroidJUnit4::class)
class ProfileRemovalTest {
    private val queue = ArrayDeque<Runnable>()
    private val writer = Executor { queue.add(it) }
    private val storage = MemoryStorage()
    private var existing = setOf(0L, 11L)
    private var failUserQuery = false
    private val profiles =
        ProfileReconciler({ storage }) {
            check(!failUserQuery)
            existing
        }
    private val learning = LearningRepository({ storage.learned }, writer, profiles::isPresent)
    private val launches = LaunchHistoryRepository({ storage.launches }, writer, profiles::isPresent)

    init {
        profiles.addRemovalListener { removed ->
            learning.profilesRemoved(removed)
            launches.profilesRemoved(removed)
        }
    }

    @Test
    fun deletionRejectsQueuedRecordsSuppressionsImportsAndOldUndo() {
        record()
        drain()
        val notice = learning.pendingNotices.single()
        val file = LearningBackup.encode(learning.memory, BackupProfiles(0, setOf(11)), 1)
        existing = setOf(0)
        writer.execute {
            profiles.reconcile()
            // 削除が確定した番号は、その後の問い合わせが失敗しても復活させない
            failUserQuery = true
        }
        record()
        learning.suppress(PAIR, 3)
        learning.import(BackupProfiles(0, setOf(11)), 3, read = { file }, onDone = {})
        learning.undo(notice)
        drain()
        assertTrue(storage.memory.records.isEmpty())
        assertTrue(storage.history.records.isEmpty())
        assertTrue(learning.memory.records.isEmpty())
        assertTrue(launches.history.records.isEmpty())
        assertTrue(learning.pendingNotices.isEmpty())
        assertFalse(learning.canUndo(notice))
    }

    @Test
    fun snapshotsAndUndoChangeOnlyAfterSuccessfulDeletion() {
        record()
        drain()
        val notice = learning.pendingNotices.single()
        storage.onDelete = {
            assertEquals(storage.memory.records, learning.memory.records)
            assertEquals(storage.history.records, launches.history.records)
            assertTrue(learning.canUndo(notice))
        }
        existing = setOf(0)
        storage.failDelete = true
        assertThrows(IllegalStateException::class.java) { profiles.reconcile() }
        assertTrue(profiles.isPresent(11))
        assertTrue(learning.canUndo(notice))
        storage.failDelete = false
        profiles.reconcile()
        assertTrue(learning.memory.records.isEmpty())
        assertTrue(launches.history.records.isEmpty())
        assertFalse(learning.canUndo(notice))
    }

    @Test
    fun cleanupBeforeFirstLoadDoesNotMarkUnloadedSnapshotsReady() {
        storage.memory = LearnedMemory.EMPTY.recorded(LearningDecision(PERSONAL_PAIR, listOf(PAIR)), 1)
        storage.history = LaunchHistory.EMPTY.recorded(APP, 1).recorded(PERSONAL_PAIR.app, 1)
        existing = setOf(0)
        profiles.reconcile()
        assertEquals(LoadState.NotLoaded, learning.loadState)
        assertEquals(LoadState.NotLoaded, launches.loadState)
        learning.requestLoad()
        launches.requestLoad()
        drain()
        assertEquals(setOf(PERSONAL_PAIR), learning.memory.records.keys)
        assertEquals(setOf(PERSONAL_PAIR.app), launches.history.records.keys)
    }

    @Test
    fun lateFirstRecordIsRejectedEvenIfNothingWasStoredAtDeletion() {
        existing = setOf(0)
        writer.execute { profiles.reconcile() }
        record()
        drain()
        assertTrue(storage.memory.records.isEmpty())
        assertTrue(storage.history.records.isEmpty())
        assertTrue(learning.pendingNotices.isEmpty())
    }

    @Test
    fun newProfileIsCheckedAgainAndAQueryFailureDoesNotRejectIt() {
        existing = setOf(0)
        profiles.reconcile()
        existing = setOf(0, 11)
        record()
        drain()
        assertEquals(setOf(APP), storage.history.records.keys)
        learning.clear()
        launches.clear {}
        drain()
        val newApp = APP.copy(profileSerial = 12)
        failUserQuery = true
        launches.record(newApp, 2)
        learning.suppress(PAIR.copy(app = newApp), 2)
        drain()
        assertEquals(setOf(newApp), storage.history.records.keys)
        assertEquals(
            setOf(newApp),
            storage.memory.records.keys
                .map { it.app }
                .toSet(),
        )
    }

    @Test
    fun failedFirstCleanupDoesNotPartiallyDeleteLearningDuringAnotherWrite() {
        storage.memory = LearnedMemory.EMPTY.recorded(LearningDecision(PAIR, emptyList()), 1)
        storage.history = LaunchHistory.EMPTY.recorded(APP, 1)
        existing = setOf(0)
        storage.failDelete = true
        assertThrows(IllegalStateException::class.java) { profiles.reconcile() }
        learning.suppress(PERSONAL_PAIR, 2)
        drain()
        assertEquals(setOf(PAIR, PERSONAL_PAIR), storage.memory.records.keys)
        assertEquals(setOf(APP), storage.history.records.keys)
    }

    private fun record() {
        launches.record(APP, 1)
        learning.record(LearningDecision(null, listOf(PAIR)), 1) { "テスト" }
    }

    private fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    private class MemoryStorage : ProfileStorage {
        var memory = LearnedMemory.EMPTY
        var history = LaunchHistory.EMPTY
        var failDelete = false
        var onDelete: () -> Unit = {}
        val learned =
            object : LearnedStorage {
                override fun load(): LearnedMemory = memory

                override fun save(
                    before: LearnedMemory,
                    after: LearnedMemory,
                ) {
                    assertEquals(memory.records, before.records)
                    memory = after
                }

                override fun clear() {
                    memory = LearnedMemory.EMPTY
                }
            }
        val launches =
            object : LaunchStatStorage {
                override fun load(): LaunchHistory = history

                override fun record(
                    app: AppId,
                    at: Long,
                ) {
                    history = history.recorded(app, at)
                }

                override fun clear() {
                    history = LaunchHistory.EMPTY
                }
            }

        override fun serials(): Set<Long> =
            memory.records.keys.mapTo(HashSet()) { it.app.profileSerial } + history.records.keys.map { it.profileSerial }

        override fun delete(serials: Set<Long>) {
            onDelete()
            check(!failDelete)
            memory = LearnedMemory(memory.records.values.filter { it.app.profileSerial !in serials })
            history = LaunchHistory(history.records.filterKeys { it.profileSerial !in serials })
        }
    }

    private companion object {
        val APP = AppId("io.example.app", "Main", 11)
        val PAIR = LearnedPair(NormalizedText("さがす"), APP)
        val PERSONAL_PAIR = PAIR.copy(app = APP.copy(profileSerial = 0))
    }
}
