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
            withRawDatabase(context, LocalDatabase.FILE_NAME) { assertEquals(OWN, it.longOrNull("SELECT owner_serial FROM installation")) }
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
    fun aTransferThatChangesTheSerialMovesTheOwnRecordsToTheNewSerial() {
        val source = AppId(OWN_APP.packageName, "Main", 10)
        open(own = 10).write(
            RecordsWrite(
                // 移行元の 0 の行は、移行先の番号と同じでも別のプロファイルのもの。中身を変えて、付け替えた行と見分ける
                learnedPut = listOf(record("よみ", source, count = 4), record("よみ", OWN_APP, count = 9), record("よみ", WORK_APP)),
                launchPut = mapOf(source to LaunchRecord(4, 400), OWN_APP to LaunchRecord(9, 900)),
            ),
        )
        reopen()
        context.noBackupFilesDir.deleteRecursively()
        // 移行元の 0 と 11 は、移行元の別のプロファイル。移行先の同じ番号と取り違えないよう捨てる
        val stored = open(own = OWN).load()
        assertEquals(
            setOf(record("よみ", OWN_APP, count = 4)),
            stored.records.learned.records.values
                .toSet(),
        )
        assertEquals(mapOf(OWN_APP to LaunchRecord(4, 400)), stored.records.launches.records)
    }

    @Test
    fun aTransferFrom020ThatChangesTheSerialCannotKeepTheOwnRecords() {
        // 版 6 の DB には、移行元の自分の番号がない。番号が変わると、どの行が自分のものか分からないので、今の番号の行だけを残す
        LocalDatabase.open(context, LocalDatabase.FILE_NAME, allowMainThread = true).use { it.launchStats.record(OWN_APP, 100) }
        context.noBackupFilesDir.deleteRecursively()
        val db = RecordsDatabase.open(context, LocalDatabase.FILE_NAME, allowMainThread = true, ownSerial = { 10 }).also(opened::add)
        try {
            assertTrue(db.load().storedSerials.isEmpty())
        } finally {
            context.deleteDatabase(LocalDatabase.FILE_NAME)
        }
    }

    @Test
    @Config(sdk = [28, 37])
    fun aMarkerThatIsTemporarilyUnreadableHoldsTheCleanup() {
        open().write(seedWrite())
        reopen()
        val token = marker.readText()
        // 印のファイルの場所を、読めない物（ディレクトリ）に一時的に置き換える
        val aside = File(context.noBackupFilesDir, "aside")
        assertTrue(marker.renameTo(aside))
        assertTrue(marker.mkdir())
        val db = open()
        assertThrows(InstallationCheckPendingException::class.java) { db.load() }
        // 転送とみなして消さず、印も作り直さない
        withRawDatabase(context, FILE) { assertEquals(2L, it.count("SELECT COUNT(DISTINCT profile_serial) FROM learned")) }
        assertTrue(marker.isDirectory)

        assertTrue(marker.delete())
        assertTrue(aside.renameTo(marker))
        // 同じ部品のまま、読めるようになった次の読み込みで照合し、同じインストールなので消さない
        assertEquals(setOf(OWN, WORK), db.load().storedSerials)
        assertEquals(token, marker.readText())
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
    fun aMarkerThatCannotBeSavedFailsTheLoadInsteadOfCleaningUpWithAnUnsavedToken() {
        open().write(seedWrite())
        reopen()
        val oldToken = withRawDatabase(context, FILE) { it.stringOf("SELECT token FROM installation") }
        // 印がなく（DB だけが転送された）、印のファイルも書けない。印の置き場をファイルで塞ぐ
        val dir = context.noBackupFilesDir
        dir.deleteRecursively()
        assertTrue(dir.createNewFile())
        try {
            repeat(2) {
                val db = open()
                assertThrows(InstallationCheckPendingException::class.java) { db.load() }
                assertThrows(InstallationCheckPendingException::class.java) {
                    db.write(
                        RecordsWrite(
                            launchPut =
                                mapOf(WORK_APP to LaunchRecord(7, 7)),
                        ),
                    )
                }
                reopen()
            }
            // 保存していない乱数で整理も印の更新もしない（次の起動で、その間に受けた記録まで消さないため）
            withRawDatabase(context, FILE) { raw ->
                assertEquals(oldToken, raw.stringOf("SELECT token FROM installation"))
                assertEquals(1L, raw.count("SELECT COUNT(*) FROM learned WHERE profile_serial = $WORK"))
                assertEquals(1L, raw.count("SELECT COUNT(*) FROM launch_stat WHERE profile_serial = $WORK"))
            }
        } finally {
            assertTrue(dir.delete())
        }
        // 印を保存できるようになったら、転送として 1 回だけ整理する。その後に受けた仕事用の記録は残る
        val restored = open()
        assertEquals(setOf(OWN), restored.load().storedSerials)
        restored.write(RecordsWrite(launchPut = mapOf(WORK_APP to LaunchRecord(1, 5))))
        reopen()
        assertEquals(setOf(OWN, WORK), open().load().storedSerials)
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
    fun anUnknownOwnSerialHoldsTheTransferUntilItIsKnown() {
        val source = AppId(OWN_APP.packageName, "Main", 10)
        open(own = 10).write(RecordsWrite(learnedPut = listOf(record("よみ", source, count = 4), record("よみ", WORK_APP))))
        reopen()
        context.noBackupFilesDir.deleteRecursively()
        // 移行先で自分の番号が取れない（負の値）。付け替え先を決められないので、整理も印の更新もしない
        var own = -1L
        val db = RecordsDatabase.open(context, FILE, allowMainThread = true, ownSerial = { own }).also(opened::add)
        assertThrows(InstallationCheckPendingException::class.java) { db.load() }
        withRawDatabase(context, FILE) { raw ->
            assertEquals(2L, raw.count("SELECT COUNT(*) FROM learned"))
            assertEquals(10L, raw.longOrNull("SELECT owner_serial FROM installation"))
        }
        own = OWN
        assertEquals(
            setOf(record("よみ", OWN_APP, count = 4)),
            db
                .load()
                .records.learned.records.values
                .toSet(),
        )
        withRawDatabase(context, FILE) { assertEquals(OWN, it.longOrNull("SELECT owner_serial FROM installation")) }
    }

    @Test
    fun anUnknownOwnSerialOnTheSameInstallationKeepsTheOwner() {
        open(own = 10).write(seedWrite())
        reopen()
        // 同じインストールなら整理は要らない。読めるが、分からない番号で所有者を上書きしない
        val stored = open(own = -1).load()
        assertEquals(setOf(OWN, WORK), stored.storedSerials)
        withRawDatabase(context, FILE) { assertEquals(10L, it.longOrNull("SELECT owner_serial FROM installation")) }
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
