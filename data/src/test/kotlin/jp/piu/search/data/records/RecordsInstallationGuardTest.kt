// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.state.RecordsWrite
import jp.piu.search.data.LocalDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

// 記録の DB の転送の整理（受入の場面: 新規のインストール・同じ端末の更新・0.3 どうしの転送・番号の変わる転送・印の一時的な失敗）
@RunWith(AndroidJUnit4::class)
class RecordsInstallationGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val marker = File(context.noBackupFilesDir, RecordsDatabase.MARKER_FILE)
    private val opened = ArrayList<RecordsDatabase>()

    @After
    fun close() {
        opened.forEach(RecordsDatabase::close)
        context.deleteDatabase(FILE)
        context.noBackupFilesDir.deleteRecursively()
    }

    @Test
    fun aNewInstallationCreatesTheMarkerAndKeepsEveryProfileOnReopening() {
        assertTrue(!marker.exists())
        val db = open()
        assertTrue(
            db
                .load()
                .records.learned.records
                .isEmpty(),
        )
        assertTrue(marker.isFile)
        db.write(seedWrite())
        reopen()
        assertEquals(setOf(OWN, WORK), open().load().storedSerials)
    }

    @Test
    fun updatingFrom020OnTheSameDeviceKeepsWorkRecords() {
        // 0.2.0 の LocalDatabase が piu.db と印のファイルを書く
        LocalDatabase.open(context, LocalDatabase.FILE_NAME, allowMainThread = true).use { old ->
            old.launchStats.record(WORK_APP, 100)
            old.learned.save(
                LearnedMemory.EMPTY,
                LearnedMemory.EMPTY.recorded(LearningDecision(pair("よみ", OWN_APP), listOf(pair("よみ", WORK_APP))), 200),
            )
        }
        val db = RecordsDatabase.open(context, LocalDatabase.FILE_NAME, allowMainThread = true, ownSerial = { OWN }).also(opened::add)
        try {
            val stored = db.load()
            assertEquals(setOf(pair("よみ", OWN_APP), pair("よみ", WORK_APP)), stored.records.learned.records.keys)
            assertEquals(setOf(WORK_APP), stored.records.launches.records.keys)
        } finally {
            context.deleteDatabase(LocalDatabase.FILE_NAME)
        }
    }

    @Test
    fun aTransferBetweenUsersOfTheSameSerialDropsOnlyTheOtherProfiles() {
        open().write(seedWrite())
        reopen()
        val oldToken = marker.readText()
        // noBackupFilesDir は転送されない
        context.noBackupFilesDir.deleteRecursively()
        val restored = open()
        assertEquals(setOf(OWN), restored.load().storedSerials)
        assertNotEquals(oldToken, marker.readText())
        // 移行先で作った仕事用の記録は、次に開いても消えない
        restored.write(RecordsWrite(launchPut = mapOf(WORK_APP to LaunchRecord(1, 5))))
        reopen()
        assertEquals(setOf(OWN, WORK), open().load().storedSerials)
    }

    @Test
    @Config(sdk = [28, 37])
    fun aCorruptMarkerIsTreatedAsTheTransferLikeTheOldGuard() {
        open().write(seedWrite())
        reopen()
        // Android 9・10 の初回の書き込みの中断では、空の本体が残る。0.2.0 と同じく新しい印で整理する
        marker.writeText("")
        assertEquals(setOf(OWN), open().load().storedSerials)
        assertTrue(marker.readText().isNotEmpty())
    }

    @Test
    fun aFailedCleanupRollsBackAndIsRetriedOnTheNextLoad() {
        open().write(seedWrite())
        reopen()
        val oldToken =
            withRawDatabase(context, FILE) { db ->
                db.rawQuery("SELECT token FROM installation", null).use {
                    it.moveToFirst()
                    it.getString(0)
                }
            }
        context.noBackupFilesDir.deleteRecursively()
        withRawDatabase(context, FILE) {
            it.execSQL(
                "CREATE TRIGGER fail_transfer BEFORE DELETE ON launch_stat WHEN OLD.profile_serial != 0 " +
                    "BEGIN SELECT RAISE(ABORT, '整理の失敗'); END",
            )
        }
        val db = open()
        assertThrows(RuntimeException::class.java) { db.load() }
        withRawDatabase(context, FILE) { raw ->
            // 先に消した学習の行も戻り、古い印のままなので、次も整理を試す
            assertEquals(2L, raw.count("SELECT COUNT(*) FROM learned"))
            assertEquals(
                oldToken,
                raw.rawQuery("SELECT token FROM installation", null).use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
            raw.execSQL("DROP TRIGGER fail_transfer")
        }
        assertEquals(setOf(OWN), db.load().storedSerials)
    }

    @Test
    fun aFailedMarkerUpdateInTheDatabaseRollsBackTheCleanup() {
        open().write(seedWrite())
        reopen()
        val oldToken = withRawDatabase(context, FILE) { it.stringOf("SELECT token FROM installation") }
        context.noBackupFilesDir.deleteRecursively()
        // 整理の後、印の更新そのもので失敗させる
        withRawDatabase(context, FILE) {
            it.execSQL("CREATE TRIGGER fail_mark_update BEFORE UPDATE ON installation BEGIN SELECT RAISE(ABORT, '印の失敗'); END")
            it.execSQL("CREATE TRIGGER fail_mark_insert BEFORE INSERT ON installation BEGIN SELECT RAISE(ABORT, '印の失敗'); END")
        }
        val db = open()
        assertThrows(RuntimeException::class.java) { db.load() }
        withRawDatabase(context, FILE) { raw ->
            assertEquals(1L, raw.count("SELECT COUNT(*) FROM learned WHERE profile_serial = $WORK"))
            assertEquals(1L, raw.count("SELECT COUNT(*) FROM launch_stat WHERE profile_serial = $WORK"))
            assertEquals(oldToken, raw.stringOf("SELECT token FROM installation"))
            raw.execSQL("DROP TRIGGER fail_mark_update")
            raw.execSQL("DROP TRIGGER fail_mark_insert")
        }
        assertEquals(setOf(OWN), db.load().storedSerials)
    }

    @Test
    @Config(sdk = [28, 37])
    fun anUnreadableMarkerIsTreatedAsTheTransferLikeTheOldGuard() {
        open().write(seedWrite())
        reopen()
        // 印の場所を読めない物（ディレクトリ）に置き換える。0.2.0 と同じく、新しい印を作って整理する
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        assertEquals(setOf(OWN), open().load().storedSerials)
        assertTrue(marker.isFile)
    }

    @Test
    fun aMarkerThatCannotBeSavedCleansUpOnEveryOpenLikeTheOldGuard() {
        open().write(seedWrite())
        reopen()
        // 印の置き場をファイルで塞ぐ。保存できない間は、開くたびに転送とみなして仕事用の記録を引き継がない（0.2.0 と同じ）
        val dir = context.noBackupFilesDir
        dir.deleteRecursively()
        assertTrue(dir.createNewFile())
        try {
            repeat(2) {
                val db = open()
                assertEquals(setOf(OWN), db.load().storedSerials)
                db.write(RecordsWrite(launchPut = mapOf(WORK_APP to LaunchRecord(1, 5))))
                reopen()
            }
        } finally {
            assertTrue(dir.delete())
        }
    }

    private fun android.database.sqlite.SQLiteDatabase.stringOf(sql: String): String =
        rawQuery(sql, null).use {
            check(it.moveToFirst())
            it.getString(0)
        }

    private fun open(own: Long = OWN): RecordsDatabase =
        RecordsDatabase
            .open(context, FILE, allowMainThread = true, ownSerial = {
                own
            })
            .also(opened::add)

    private fun reopen() {
        opened.forEach(RecordsDatabase::close)
        opened.clear()
    }

    private companion object {
        const val FILE = "m802a-records-guard-test.db"
    }
}
