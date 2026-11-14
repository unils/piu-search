// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 索引の外部キーに頼れない学習と起動の記録も、同じ削除の境界に入ることを確かめる。
@RunWith(AndroidJUnit4::class)
class ProfileStoreTest {
    @Test
    fun removalDeletesAllThreeKindsAndCascadesSearchKeys() =
        withDatabase { db ->
            seed(db)
            db.profiles.delete(setOf(WORK.profileSerial))
            assertEquals(setOf(0L), db.profiles.serials())
            assertEquals(
                listOf(PERSONAL),
                db.index
                    .load()
                    .apps
                    .map { it.app.id },
            )
            assertEquals(
                setOf(PERSONAL),
                db.learned
                    .load()
                    .records.keys
                    .map { it.app }
                    .toSet(),
            )
            assertEquals(
                setOf(PERSONAL),
                db.launchStats
                    .load()
                    .records.keys,
            )
            assertEquals(
                1,
                db.room
                    .indexDao()
                    .keys()
                    .size,
            )
        }

    @Test
    fun failureInLastDeleteRollsBackIndexAndLearningAndCanBeRetried() =
        withDatabase { db ->
            seed(db)
            val before = db.index.load()
            val learned = db.learned.load().records
            val history = db.launchStats.load().records
            db.room.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_profile_delete BEFORE DELETE ON launch_stat " +
                    "BEGIN SELECT RAISE(ABORT, 'test failure'); END",
            )
            assertThrows(RuntimeException::class.java) { db.profiles.delete(setOf(WORK.profileSerial)) }
            assertEquals(before, db.index.load())
            assertEquals(learned, db.learned.load().records)
            assertEquals(history, db.launchStats.load().records)
            db.room.openHelper.writableDatabase
                .execSQL("DROP TRIGGER fail_profile_delete")
            db.profiles.delete(setOf(WORK.profileSerial))
            assertEquals(setOf(0L), db.profiles.serials())
        }

    @Test
    fun startupReconcilesTheUnionEvenWithoutAnIndexOrLaunchHistory() =
        withDatabase { db ->
            val memory = LearnedMemory.EMPTY.recorded(LearningDecision(pair(WORK), emptyList()), 1)
            db.learned.save(LearnedMemory.EMPTY, memory)
            assertEquals(setOf(WORK.profileSerial), db.profiles.serials())
            val profiles = ProfileReconciler({ db.profiles }) { setOf(0L) }
            assertEquals(setOf(WORK.profileSerial), profiles.reconcile())
            assertTrue(
                db.learned
                    .load()
                    .records
                    .isEmpty(),
            )
            assertTrue(db.profiles.serials().isEmpty())
        }

    @Test
    fun unavailableAndFailedUserQueriesDoNotRemoveAnything() =
        withDatabase { db ->
            seed(db)
            val before = db.profiles.serials()
            // 一時停止中も UserManager の一覧には存在する。LauncherApps の可視性には頼らない。
            assertTrue(ProfileReconciler({ db.profiles }) { before }.reconcile().isEmpty())
            assertTrue(ProfileReconciler({ db.profiles }) { null }.reconcile().isEmpty())
            assertTrue(ProfileReconciler({ db.profiles }) { throw SecurityException("test failure") }.reconcile().isEmpty())
            assertEquals(before, db.profiles.serials())
        }

    private fun seed(db: LocalDatabase) {
        db.index.replaceAll(
            listOf(PERSONAL, WORK).map {
                StoredApp(IndexedApp(it, "テスト", listOf(SearchKey(NormalizedText("てすと"), SearchKeyKind.READING))), 1)
            },
            SyncState(0, 1, "ja-JP"),
        )
        val memory = LearnedMemory.EMPTY.recorded(LearningDecision(pair(PERSONAL), listOf(pair(WORK))), 1)
        db.learned.save(LearnedMemory.EMPTY, memory)
        listOf(PERSONAL, WORK).forEach { db.launchStats.record(it, 1) }
    }

    private fun withDatabase(test: (LocalDatabase) -> Unit) {
        LocalDatabase.open(ApplicationProvider.getApplicationContext(), null, allowMainThread = true).use(test)
    }

    private fun pair(app: AppId) = LearnedPair(NormalizedText("さがす"), app)

    private companion object {
        val PERSONAL = AppId("io.example.app", "Main", 0)
        val WORK = PERSONAL.copy(profileSerial = 11)
    }
}
