// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchIndexVersion
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.PiuRoomDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// データベースだけが別のインストールへ移る状況を作る。番号や更新時刻が偶然同じでも、前の端末の索引と仕事用の記録を使わない。
@RunWith(AndroidJUnit4::class)
class DeviceTransferTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<LocalDatabase>()
    private val device = FakeDevice()
    private val analyzer = CountingAnalyzer()

    @After
    fun close() {
        opened.forEach(LocalDatabase::close)
        context.deleteDatabase(FILE)
    }

    @Test
    fun transferredDatabaseRebuildsTheIndexAndDropsWorkRecordsBeforeAnyRead() {
        val original = seed()
        original.close()
        opened.remove(original)
        // noBackupFilesDir は転送されない。DB を残し、このインストールだけの印をなくして移行を再現する。
        context.noBackupFilesDir.deleteRecursively()
        val restored = open()

        // 学習や起動の記録が索引より先に読まれても、仕事用を返さない。同じ番号の仕事用が移行先にあっても捨てる。
        assertEquals(
            setOf(PERSONAL_APP),
            restored.launchStats
                .load()
                .records.keys,
        )
        assertEquals(
            setOf(PERSONAL_PAIR),
            restored.learned
                .load()
                .records.keys,
        )
        assertTrue(
            restored.index
                .load()
                .apps
                .isEmpty(),
        )
        assertNull(restored.index.load().state)
        device.install(InstalledApp(AppEntry(PERSONAL_APP, "移行先の名前"), 1000))
        assertEquals(SyncMode.FULL_REBUILD, sync(restored).sync().mode)
        assertEquals(
            listOf("移行先の名前", "仕事用"),
            restored.index
                .load()
                .apps
                .map { it.app.label },
        )

        restored.close()
        opened.remove(restored)
        val reopened = open()
        assertEquals(SyncMode.CHANGES, sync(reopened).sync().mode)
        assertEquals(
            setOf(PERSONAL_PAIR),
            reopened.learned
                .load()
                .records.keys,
        )
    }

    @Test
    fun normalReopeningKeepsBothProfilesRecords() {
        val original = seed()
        original.close()
        opened.remove(original)
        val reopened = open()
        assertEquals(
            setOf(PERSONAL_APP, WORK_APP),
            reopened.launchStats
                .load()
                .records.keys,
        )
        assertEquals(
            setOf(PERSONAL_PAIR, WORK_PAIR),
            reopened.learned
                .load()
                .records.keys,
        )
        assertEquals(SyncMode.CHANGES, sync(reopened).sync().mode)
    }

    @Test
    fun indexVersionChangesDoNotLoseTheInstallationMarkOrWorkRecords() {
        val original = seed()
        original.close()
        opened.remove(original)
        val upgraded = LocalDatabase.open(context, FILE, SearchIndexVersion.CURRENT + 1, allowMainThread = true).also(opened::add)
        assertTrue(
            upgraded.index
                .load()
                .apps
                .isEmpty(),
        )
        upgraded.close()
        opened.remove(upgraded)
        val reopened = open()
        assertEquals(
            setOf(PERSONAL_APP, WORK_APP),
            reopened.launchStats
                .load()
                .records.keys,
        )
        assertEquals(
            setOf(PERSONAL_PAIR, WORK_PAIR),
            reopened.learned
                .load()
                .records.keys,
        )
    }

    @Test
    fun failedCleanupRollsBackAndIsRetriedBeforeRecordsCanBeRead() {
        val original = seed()
        original.close()
        opened.remove(original)
        context.noBackupFilesDir.deleteRecursively()
        withRawRoom { room ->
            room.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_transfer BEFORE DELETE ON learned WHEN OLD.profile_serial != 0 " +
                    "BEGIN SELECT RAISE(ABORT, '整理の失敗'); END",
            )
        }
        val interrupted = open()
        assertThrows(RuntimeException::class.java) { interrupted.launchStats.load() }
        interrupted.close()
        opened.remove(interrupted)
        withRawRoom { room ->
            // 先に消した索引も戻り、古い印のままなので、次回も整理を試す。
            assertEquals(2, room.indexDao().apps().size)
            assertEquals(2, room.learnedDao().all().size)
            assertEquals(2, room.launchStatDao().all().size)
            room.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_transfer")
        }
        val retry = open()
        assertTrue(
            retry.index
                .load()
                .apps
                .isEmpty(),
        )
        assertEquals(
            setOf(PERSONAL_APP),
            retry.launchStats
                .load()
                .records.keys,
        )
        assertEquals(
            setOf(PERSONAL_PAIR),
            retry.learned
                .load()
                .records.keys,
        )
    }

    private fun withRawRoom(action: (PiuRoomDatabase) -> Unit) {
        val room = rawRoom()
        try {
            action(room)
        } finally {
            room.close()
        }
    }

    private fun rawRoom(): PiuRoomDatabase =
        Room
            .databaseBuilder(context, PiuRoomDatabase::class.java, FILE)
            .addMigrations(*PiuRoomDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun seed(): LocalDatabase {
        device.install(
            InstalledApp(AppEntry(PERSONAL_APP, "元の名前"), 1000),
            InstalledApp(AppEntry(WORK_APP, "仕事用"), 1000),
        )
        return open().also { db ->
            sync(db).sync()
            db.launchStats.record(PERSONAL_APP, 100)
            db.launchStats.record(WORK_APP, 200)
            val memory = LearnedMemory.EMPTY.recorded(LearningDecision(PERSONAL_PAIR, listOf(WORK_PAIR)), 300)
            db.learned.save(LearnedMemory.EMPTY, memory)
        }
    }

    private fun open(): LocalDatabase = LocalDatabase.open(context, FILE, allowMainThread = true).also(opened::add)

    private fun sync(database: LocalDatabase): IndexSync {
        val generator = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList())))
        return IndexSync(database.index, device, { generator }, analyzer, launcherApps = null)
    }

    private companion object {
        const val FILE = "device-transfer-test.db"
        val PERSONAL_APP = AppId("io.example.personal", "Main", PERSONAL)
        val WORK_APP = AppId("io.example.work", "Main", WORK)
        val PERSONAL_PAIR = LearnedPair(NormalizedText("よみ"), PERSONAL_APP)
        val WORK_PAIR = LearnedPair(NormalizedText("よみ"), WORK_APP)
    }
}
