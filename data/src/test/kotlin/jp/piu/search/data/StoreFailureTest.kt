// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.LaunchStatDao
import jp.piu.search.data.db.LaunchStatRow
import jp.piu.search.data.db.LearnedDao
import jp.piu.search.data.db.LearnedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

// DAO が途中で例外を投げても差分全体を巻き戻し、同じ変更前の写しで再試行できることを確かめる。
@RunWith(AndroidJUnit4::class)
class StoreFailureTest {
    @Test
    fun failedDiffRollsBackReplacementsAndDeletesAndCanBeRetried() {
        LocalDatabase.open(ApplicationProvider.getApplicationContext(), null, allowMainThread = true).use { db ->
            val before = LearnedMemory.EMPTY.recorded(LearningDecision(PAIR, listOf(OTHER)), 1)
            db.learned.save(LearnedMemory.EMPTY, before)
            val real = db.room.learnedDao()
            var fail = true
            val dao =
                object : LearnedDao by real {
                    override fun delete(
                        profileSerial: Long,
                        packageName: String,
                        activityName: String,
                        query: String,
                    ) {
                        real.delete(profileSerial, packageName, activityName, query)
                        check(!fail)
                    }
                }
            val store = LearnedStore(db.room, dao)
            val after = before.recorded(LearningDecision(PAIR, emptyList()), 2).removed(OTHER)
            assertThrows(IllegalStateException::class.java) { store.save(before, after) }
            assertEquals(before.records, db.learned.load().records)
            fail = false
            store.save(before, after)
            assertEquals(after.records, db.learned.load().records)
            assertEquals(
                2,
                store
                    .load()
                    .records
                    .getValue(PAIR)
                    .count,
            )
        }
    }

    @Test
    fun failedLegacyCleanupIsRetriedAndClearDeletesUnloadedRows() {
        LocalDatabase.open(ApplicationProvider.getApplicationContext(), null, allowMainThread = true).use { db ->
            val real = db.room.learnedDao()
            real.put(listOf(LearnedRow("あ".repeat(65), APP.packageName, APP.activityName, 0, 1, 1, true, false)))
            var fail = true
            val dao =
                object : LearnedDao by real {
                    override fun delete(
                        profileSerial: Long,
                        packageName: String,
                        activityName: String,
                        query: String,
                    ) {
                        real.delete(profileSerial, packageName, activityName, query)
                        check(!fail)
                    }
                }
            val store = LearnedStore(db.room, dao)
            assertThrows(IllegalStateException::class.java) { store.load() }
            assertEquals(1, real.all().size)
            fail = false
            assertEquals(0, store.load().records.size)
            assertEquals(0, real.all().size)
            real.put(listOf(LearnedRow("あ".repeat(65), APP.packageName, APP.activityName, 0, 1, 1, true, false)))
            store.clear()
            assertEquals(0, real.all().size)
        }
    }

    @Test
    fun failedLaunchInsertRollsBackAndCanBeRetried() {
        LocalDatabase.open(ApplicationProvider.getApplicationContext(), null, allowMainThread = true).use { db ->
            val real = db.room.launchStatDao()
            var fail = true
            val dao =
                object : LaunchStatDao by real {
                    override fun insert(row: LaunchStatRow) {
                        real.insert(row)
                        check(!fail)
                    }
                }
            val store = LaunchStatStore(db.room, dao)
            assertThrows(IllegalStateException::class.java) { store.record(APP, 1) }
            assertEquals(0, real.all().size)
            fail = false
            store.record(APP, 2)
            assertEquals(
                1,
                store
                    .load()
                    .records
                    .getValue(APP)
                    .count,
            )
        }
    }

    private companion object {
        val APP = AppId("io.example.app", "Main", 0)
        val PAIR = LearnedPair(NormalizedText("さがす"), APP)
        val OTHER = LearnedPair(NormalizedText("べつ"), APP)
    }
}
