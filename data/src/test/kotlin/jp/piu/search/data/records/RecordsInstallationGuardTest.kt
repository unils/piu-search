// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.state.RecordsWrite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
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

    @get:Rule
    val legacy = legacyMigrationHelper()

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
        // 0.2.0 が書いた piu.db（版 6）と印のファイル。0.2.0 の InstallationGuard は noBackupFilesDir の installation-id に
        // UUID の文字列（UTF-8）を書き、同じ値を installation の表に入れた。名前と書式はここに固定する（変えると同じ端末の更新で仕事用の記録が消える）
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        legacy.createDatabase(FILE, 6).use { db ->
            db.execSQL("INSERT INTO installation VALUES (0, '$token')")
            db.execSQL("INSERT INTO launch_stat VALUES ('${WORK_APP.packageName}', 'Main', $WORK, 1, 100)")
            db.execSQL("INSERT INTO learned VALUES ('よみ', '${OWN_APP.packageName}', 'Main', $OWN, 1, 200, 0, 0)")
            db.execSQL("INSERT INTO learned VALUES ('よみ', '${WORK_APP.packageName}', 'Main', $WORK, 1, 200, 1, 0)")
        }
        File(context.noBackupFilesDir, "installation-id").writeText(token, Charsets.UTF_8)
        val stored = open().load()
        assertEquals(setOf(pair("よみ", OWN_APP), pair("よみ", WORK_APP)), stored.records.learned.records.keys)
        assertEquals(setOf(WORK_APP), stored.records.launches.records.keys)
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

    // 途中までしか書かれていない印も、新しい印で整理する。直した印は保存され、その後に書いた仕事用の記録は次に開いても残る
    @Test
    @Config(sdk = [28, 37])
    fun aMalformedMarkerIsReplacedAndTheNewMarkerKeepsLaterWorkRecords() {
        open().write(seedWrite())
        reopen()
        val oldToken = marker.readText()
        marker.writeText("01234567-89ab-")
        val db = open()
        assertEquals(setOf(OWN), db.load().storedSerials)
        val token = marker.readText()
        assertNotEquals(oldToken, token)
        db.write(RecordsWrite(launchPut = mapOf(WORK_APP to LaunchRecord(1, 5))))
        reopen()
        assertEquals(setOf(OWN, WORK), open().load().storedSerials)
        assertEquals(token, marker.readText())
    }

    // 印が一致する再オープンの照合は読み取りだけで終える（書き込みのロックを取らない）。WAL（先行書き込みのログ）では、
    // 別の接続の書き込みの途中でも、照合が待たされない
    @Test
    fun theGuardOfTheSameInstallationDoesNotWaitForAnotherConnectionThatIsWriting() {
        open().write(seedWrite())
        reopen()
        val room =
            androidx.room.Room
                .databaseBuilder(context, RecordsRoomDatabase::class.java, FILE)
                .addMigrations(*RecordsRoomDatabase.MIGRATIONS)
                .build()
        val writer =
            android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(FILE).path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
            )
        val reader =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            // Room の接続を先に開いておく（開くときの検査は照合の外）
            reader.submit(java.util.concurrent.Callable { room.recordsDao().installation() }).get(10, java.util.concurrent.TimeUnit.SECONDS)
            writer.beginTransactionNonExclusive()
            try {
                writer.execSQL("INSERT INTO launch_stat VALUES ('io.example.later', 'Main', $WORK, 1, 9)")
                reader
                    .submit(java.util.concurrent.Callable { RecordsInstallationGuard(InstallationMarker(marker)) { OWN }.run(room) })
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                writer.endTransaction()
            }
            assertEquals(1L, withRawDatabase(context, FILE) { it.count("SELECT COUNT(*) FROM launch_stat WHERE profile_serial = $WORK") })
        } finally {
            writer.close()
            room.close()
            reader.shutdownNow()
            assertTrue(reader.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        }
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
