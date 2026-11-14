// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.os.Process
import android.os.UserManager
import android.util.AtomicFile
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun cleanupKeepsTheRecordsOfTheUserTheAppRunsInEvenIfItIsNotTheMainUser() {
        // 別のユーザー（主ユーザー以外）に入れた場合。自分の番号は 0 でなく、主ユーザーの番号 0 は他人のものになる。
        shadowOf(context.getSystemService(UserManager::class.java)).setSerialNumberForUser(Process.myUserHandle(), SECONDARY)
        val own = AppId("io.example.own", "Main", SECONDARY)
        val ownPair = LearnedPair(NormalizedText("よみ"), own)
        val original = open()
        listOf(own, PERSONAL_APP, WORK_APP).forEachIndexed { i, app -> original.launchStats.record(app, 100L + i) }
        original.learned.save(
            LearnedMemory.EMPTY,
            LearnedMemory.EMPTY.recorded(LearningDecision(ownPair, listOf(PERSONAL_PAIR, WORK_PAIR)), 300),
        )
        original.close()
        opened.remove(original)
        // 印の破損は別のユーザーでも起きる。整理が走っても、自分の記録を仕事用と取り違えて消さない。
        File(context.noBackupFilesDir, "installation-id").writeText("")
        val restored = open()
        assertEquals(
            setOf(own),
            restored.launchStats
                .load()
                .records.keys,
        )
        assertEquals(
            setOf(ownPair),
            restored.learned
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
    @Config(sdk = [28])
    fun interruptedFirstMarkerWriteCanRecover() {
        assertMarkerRecovery { file ->
            // Android 9 は初回の書き込みで本体を直接作る。確定せず閉じ、プロセス終了後の空の印を再現する。
            file.startWrite().close()
            assertTrue(file.baseFile.isFile)
            assertEquals(0L, file.baseFile.length())
        }
    }

    @Test
    @Config(sdk = [28, 37])
    fun emptyMarkerCanRecover() {
        assertMarkerRecovery { it.baseFile.writeText("") }
    }

    @Test
    @Config(sdk = [28, 37])
    fun malformedMarkerCanRecover() {
        assertMarkerRecovery { it.baseFile.writeText("01234567-89ab-") }
    }

    @Test
    @Config(sdk = [28, 37])
    fun unreadableMarkerCanRecover() {
        assertMarkerRecovery { file ->
            // 実行ユーザーの権限に左右されず、印の読み取りが IOException になる状態を作る。
            assertTrue(file.baseFile.mkdir())
            assertThrows(IOException::class.java) { file.openRead().bufferedReader().use { it.readText() } }
        }
    }

    @Test
    @Config(sdk = [28, 37])
    fun unwritableMarkerStillAllowsSafeDatabaseReadsAndIndexRebuilds() {
        val original = seed()
        original.close()
        opened.remove(original)
        context.noBackupFilesDir.deleteRecursively()
        assertTrue(context.noBackupFilesDir.mkdirs() || context.noBackupFilesDir.isDirectory)
        // 本体・旧版の退避先・新版の一時ファイルを塞ぎ、権限に依存せず印の再作成も失敗させる。
        listOf("installation-id", "installation-id.bak", "installation-id.new").forEach { name ->
            val directory = File(context.noBackupFilesDir, name)
            assertTrue(directory.mkdir())
            directory.resolve("blocked").writeText("blocked")
        }
        try {
            repeat(2) {
                val restored = open()
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
                assertEquals(SyncMode.FULL_REBUILD, sync(restored).sync().mode)
                assertEquals(
                    2,
                    restored.index
                        .load()
                        .apps.size,
                )
                // 印を保存できない間は、次のオープンでも仕事用を引き継がず整理する。
                restored.launchStats.record(WORK_APP, 400)
                val memory = restored.learned.load()
                restored.learned.save(memory, memory.recorded(LearningDecision(WORK_PAIR, emptyList()), 500))
                restored.close()
                opened.remove(restored)
            }
        } finally {
            context.noBackupFilesDir.deleteRecursively()
        }
    }

    @Test
    fun reopeningTheSameInstallationCanReadWhileAnotherConnectionIsWriting() {
        val original = seed()
        val reopened = open()
        val writer = original.room.openHelper.writableDatabase
        val reader = Executors.newSingleThreadExecutor()
        // WAL は別の接続での読み出しを許す。再オープンの印の確認だけで、書き込みを待たせない。
        writer.beginTransactionNonExclusive()
        try {
            val records = reader.submit(Callable { reopened.launchStats.load().records }).get(10, TimeUnit.SECONDS)
            assertEquals(setOf(PERSONAL_APP, WORK_APP), records.keys)
        } finally {
            writer.endTransaction()
            reader.shutdownNow()
            assertTrue(reader.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun indexVersionChangesDoNotLoseTheInstallationMarkOrWorkRecords() {
        val original = seed()
        original.close()
        opened.remove(original)
        val upgraded = LocalDatabase.open(context, FILE, SearchIndexVersion.CURRENT + 1, allowMainThread = true).also(opened::add)
        assertEquals(
            setOf(PERSONAL_APP, WORK_APP),
            upgraded.index
                .load()
                .apps
                .map { it.app.id }
                .toSet(),
        )
        assertNull(upgraded.index.load().state)
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

    private fun assertMarkerRecovery(damage: (AtomicFile) -> Unit) {
        val original = seed()
        val personalHistory =
            original.launchStats
                .load()
                .records
                .filterKeys { it == PERSONAL_APP }
        val personalLearning =
            original.learned
                .load()
                .records
                .filterKeys { it == PERSONAL_PAIR }
        val file = AtomicFile(File(context.noBackupFilesDir, "installation-id"))
        val oldToken = file.openRead().bufferedReader().use { it.readText() }
        original.close()
        opened.remove(original)
        file.delete()
        damage(file)

        val restored = open()
        assertEquals(personalHistory, restored.launchStats.load().records)
        assertEquals(personalLearning, restored.learned.load().records)
        assertTrue(
            restored.index
                .load()
                .apps
                .isEmpty(),
        )
        assertNull(restored.index.load().state)
        val token = file.openRead().bufferedReader().use { UUID.fromString(it.readText()).toString() }
        assertNotEquals(oldToken, token)
        device.install(InstalledApp(AppEntry(PERSONAL_APP, "復旧後の名前"), 1000))
        assertEquals(SyncMode.FULL_REBUILD, sync(restored).sync().mode)
        assertEquals(
            listOf("復旧後の名前", "仕事用"),
            restored.index
                .load()
                .apps
                .map { it.app.label },
        )

        // 復旧後の印を保存し、新しく作った仕事用の記録が次のオープンで再び消えないことも確かめる。
        restored.launchStats.record(WORK_APP, 400)
        val memory = restored.learned.load()
        restored.learned.save(memory, memory.recorded(LearningDecision(WORK_PAIR, emptyList()), 500))
        val history = restored.launchStats.load().records
        val learning = restored.learned.load().records
        restored.close()
        opened.remove(restored)
        val reopened = open()
        assertEquals(history, reopened.launchStats.load().records)
        assertEquals(learning, reopened.learned.load().records)
        assertEquals(SyncMode.CHANGES, sync(reopened).sync().mode)
        assertEquals(token, file.openRead().bufferedReader().use { it.readText() })
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

        // 主ユーザーのあとに作ったユーザーの番号。主ユーザー（0）と仕事用（WORK）のどちらとも違う。
        const val SECONDARY = 10L
        val PERSONAL_APP = AppId("io.example.personal", "Main", PERSONAL)
        val WORK_APP = AppId("io.example.work", "Main", WORK)
        val PERSONAL_PAIR = LearnedPair(NormalizedText("よみ"), PERSONAL_APP)
        val WORK_PAIR = LearnedPair(NormalizedText("よみ"), WORK_APP)
    }
}
