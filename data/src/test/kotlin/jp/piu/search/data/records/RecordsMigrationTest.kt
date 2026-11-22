// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.data.db.PiuRoomDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// 0.2.0 の版 6 の piu.db（PiuRoomDatabase の書き出したスキーマ 6.json で作る）を、記録の DB の版 7 に移行する
@RunWith(AndroidJUnit4::class)
class RecordsMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val old = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PiuRoomDatabase::class.java)

    @get:Rule
    val records = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RecordsRoomDatabase::class.java)

    @After
    fun delete() {
        context.deleteDatabase(FILE)
    }

    @Test
    fun version6IsMigratedTo7KeepingTheRecordsAndDroppingTheIndex() {
        old.createDatabase(FILE, 6).use(::insertVersion6Rows)
        // 移行の後の表の形が 7.json と同じで、索引の表が残っていないこと（validateDroppedTables）
        records.runMigrationsAndValidate(FILE, 7, true, Migration6To7).close()
        withRawDatabase(context, FILE) { db ->
            assertEquals(TOKEN, db.stringOf("SELECT token FROM installation WHERE id = 0"))
        }
        assertRecordsAreKept()
    }

    @Test
    fun aMigrationEndedHalfwayLeavesVersion6AndTheNextOpenFinishesIt() {
        old.createDatabase(FILE, 6).use(::insertVersion6Rows)
        // 本物の移行を最後の DDL（列の追加）まで当てた直後に終了する。Room は移行を 1 つのトランザクションで当てるので、すべて戻る
        val interrupted =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    Migration6To7.migrate(db)
                    throw Ended()
                }
            }
        val room =
            Room
                .databaseBuilder(context, RecordsRoomDatabase::class.java, FILE)
                .addMigrations(interrupted)
                .allowMainThreadQueries()
                .build()
        assertThrows(Ended::class.java) { room.recordsDao().learned() }
        room.close()
        withRawDatabase(context, FILE) { db ->
            assertEquals(6L, db.count("PRAGMA user_version"))
            assertEquals(1L, db.count("SELECT COUNT(*) FROM app"))
            assertEquals(1L, db.count("SELECT COUNT(*) FROM search_key"))
            assertEquals(1L, db.count("SELECT COUNT(*) FROM meta"))
            assertEquals(1L, db.count("SELECT COUNT(*) FROM material_issue"))
            assertEquals(2L, db.count("SELECT COUNT(*) FROM learned"))
            assertEquals(1L, db.count("SELECT COUNT(*) FROM launch_stat"))
        }
        // 次は製品と同じ開き方（版 6 までの移行の後ろに 6 → 7）で開く
        assertRecordsAreKept()
        withRawDatabase(context, FILE) { db ->
            assertEquals(7L, db.count("PRAGMA user_version"))
            assertEquals(0L, db.count("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('app', 'search_key', 'meta', 'material_issue')"))
        }
    }

    @Test
    fun version5IsMigratedThroughTheOldChain() {
        old.createDatabase(FILE, 5).use { db ->
            db.execSQL("INSERT INTO installation VALUES (0, '$TOKEN')")
            db.execSQL("INSERT INTO learned VALUES ('よみ', '${OWN_APP.packageName}', 'Main', $ME, 1, 1000, 0, 0)")
        }
        markWith(TOKEN)
        val db = RecordsDatabase.open(context, FILE, allowMainThread = true, ownSerial = { ME })
        try {
            assertEquals(
                setOf(record("よみ", MY_APP)),
                db
                    .load()
                    .records.learned.records.values
                    .toSet(),
            )
        } finally {
            db.close()
        }
    }

    // 同じ端末の更新: 印のファイルは 0.2.0 が書いたもので、DB の印と一致する。仕事用の記録も残す
    private fun assertRecordsAreKept() {
        markWith(TOKEN)
        val db = RecordsDatabase.open(context, FILE, allowMainThread = true, ownSerial = { ME })
        try {
            val stored = db.load()
            assertEquals(
                setOf(record("よみ", MY_APP, count = 2, at = 3000), record("よみ", WORK_APP)),
                stored.records.learned.records.values
                    .toSet(),
            )
            assertEquals(mapOf(MY_APP to LaunchRecord(3, 2000)), stored.records.launches.records)
            assertEquals(setOf(ME, WORK), stored.storedSerials)
        } finally {
            db.close()
        }
        withRawDatabase(context, FILE) { assertEquals(1L, it.count("SELECT COUNT(*) FROM installation WHERE token = '$TOKEN'")) }
    }

    private fun markWith(token: String) {
        File(context.noBackupFilesDir, RecordsDatabase.MARKER_FILE).writeText(token)
    }

    private fun insertVersion6Rows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO app (id, package_name, activity_name, profile_serial, label, package_updated_at, apk_fingerprint) " +
                "VALUES (1, '${OWN_APP.packageName}', 'Main', $ME, '名前', 1000, 'fp')",
        )
        db.execSQL("INSERT INTO search_key VALUES (1, 0, 'なまえ', 'READING', 'STRICT', '')")
        db.execSQL("INSERT INTO meta (id, index_version, change_sequence, boot_count, locales) VALUES (0, 1, 1, 1, 'ja-JP')")
        db.execSQL(
            "INSERT INTO material_issue VALUES ($ME, '${OWN_APP.packageName}', 'LABEL', 'java.io.IOException', NULL, NULL, 1, 1, " +
                "'2026-11-15', '2026-11-15')",
        )
        db.execSQL("INSERT INTO installation VALUES (0, '$TOKEN')")
        db.execSQL("INSERT INTO launch_stat VALUES ('${OWN_APP.packageName}', 'Main', $ME, 3, 2000)")
        db.execSQL("INSERT INTO learned VALUES ('よみ', '${OWN_APP.packageName}', 'Main', $ME, 2, 3000, 0, 0)")
        db.execSQL("INSERT INTO learned VALUES ('よみ', '${WORK_APP.packageName}', 'Main', $WORK, 1, 1000, 0, 0)")
    }

    private fun android.database.sqlite.SQLiteDatabase.stringOf(sql: String): String? =
        rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else "（行がない）" }

    private class Ended : RuntimeException("移行の途中での終了")

    private companion object {
        const val FILE = "m802a-records-migration-test.db"

        // 主ユーザー以外に入れた場合の自分の番号。自分の番号の行を、0 の行と取り違えずに残すことも確かめる
        const val ME = 12L
        val MY_APP = AppId(OWN_APP.packageName, "Main", ME)
        const val TOKEN = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
