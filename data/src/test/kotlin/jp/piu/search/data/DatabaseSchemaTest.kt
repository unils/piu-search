// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.KeyForm
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchIndexVersion
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.PiuRoomDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// リポジトリの data/schemas の JSON（書き出したスキーマ）が使えることを確かめる。
// MigrationTestHelper は JSON から表を作り、Room は開くときに表の形の要約（identity hash）を自分の定義と比べる。
// 版を上げたら、ここに「古い版の JSON で作ったデータベースを移行して読む」テストを足す（1 → 2 は M3-01、2 → 3 は M4-02）
@RunWith(AndroidJUnit4::class)
class DatabaseSchemaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PiuRoomDatabase::class.java)

    @Test
    fun rowsWrittenWithTheExportedSchemaAreReadByTheCurrentCode() {
        helper.createDatabase(FILE, PiuRoomDatabase.VERSION).use { db ->
            insertIndexRows(db)
            db.execSQL("INSERT INTO launch_stat VALUES ('jp.co.jorudan.nrkj', 'jp.co.jorudan.nrkj.Launcher', 0, 3, 2000)")
            db.execSQL("INSERT INTO learned VALUES ('おかね', 'jp.co.jorudan.nrkj', 'jp.co.jorudan.nrkj.Launcher', 0, 2, 3000, 1, 0)")
        }

        val database = openWithRoom()
        assertIndexRowsAreRead(database)
        assertEquals(
            mapOf(AppId("jp.co.jorudan.nrkj", "jp.co.jorudan.nrkj.Launcher", 0) to LaunchRecord(3, 2000)),
            database.launchStats.load().records,
        )
        assertEquals(
            listOf(LearnedRecord(NormalizedText("おかね"), TRANSIT, count = 2, lastUsedAt = 3000, rescued = true, suppressed = false)),
            database.learned
                .load()
                .records.values
                .toList(),
        )
    }

    @Test
    fun version2IsMigratedTo3KeepingTheIndexAndLaunchStats() {
        helper.createDatabase(FILE, 2).use { db ->
            insertIndexRows(db)
            db.execSQL("INSERT INTO launch_stat VALUES ('jp.co.jorudan.nrkj', 'jp.co.jorudan.nrkj.Launcher', 0, 3, 2000)")
        }

        // 移行の後の表の形が、書き出した 3.json と同じかを確かめる
        helper.runMigrationsAndValidate(FILE, 3, true, *PiuRoomDatabase.MIGRATIONS).close()

        val database = openWithRoom()
        assertIndexRowsAreRead(database)
        assertEquals(mapOf(TRANSIT to LaunchRecord(3, 2000)), database.launchStats.load().records)
        assertTrue(
            database.learned
                .load()
                .records
                .isEmpty(),
        )
        val learned = LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(LearnedPair(NormalizedText("おかね"), TRANSIT))), 5000)
        database.learned.save(LearnedMemory.EMPTY, learned)
        assertEquals(learned.records, database.learned.load().records)
    }

    @Test
    fun version1IsMigratedTo2KeepingTheIndex() {
        helper.createDatabase(FILE, 1).use(::insertIndexRows)

        // 移行の後の表の形が、書き出した 2.json と同じかを確かめる
        helper.runMigrationsAndValidate(FILE, 2, true, *PiuRoomDatabase.MIGRATIONS).close()

        // 今の版まで開く（2 → 3 も通る）
        val database = openWithRoom()
        assertIndexRowsAreRead(database)
        assertTrue(
            database.launchStats
                .load()
                .records
                .isEmpty(),
        )
        val app = AppId("jp.co.jorudan.nrkj", "jp.co.jorudan.nrkj.Launcher", 0)
        database.launchStats.record(app, at = 5000)
        assertEquals(LaunchRecord(1, 5000), database.launchStats.load().records[app])
    }

    private fun openWithRoom(): LocalDatabase {
        val room =
            Room
                .databaseBuilder(context, PiuRoomDatabase::class.java, FILE)
                .addMigrations(*PiuRoomDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()
        helper.closeWhenFinished(room)
        return LocalDatabase(room)
    }

    private fun insertIndexRows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO app (id, package_name, activity_name, profile_serial, label, package_updated_at) " +
                "VALUES (1, 'jp.co.jorudan.nrkj', 'jp.co.jorudan.nrkj.Launcher', 0, '乗換案内', 1000)",
        )
        db.execSQL("INSERT INTO search_key VALUES (1, 0, 'のりかえあんない', 'READING', 'STRICT', '4')")
        db.execSQL("INSERT INTO search_key VALUES (1, 1, 'jorudan', 'PACKAGE_NAME', 'STRICT', '')")
        db.execSQL(
            "INSERT INTO meta (id, index_version, change_sequence, boot_count, locales) " +
                "VALUES (0, ${SearchIndexVersion.CURRENT}, 5, NULL, 'ja-JP')",
        )
    }

    private fun assertIndexRowsAreRead(database: LocalDatabase) {
        val loaded = database.index.load()

        assertEquals(SyncState(changeSequence = 5, bootCount = null, locales = "ja-JP"), loaded.state)
        val app = loaded.apps.single()
        assertEquals(AppId("jp.co.jorudan.nrkj", "jp.co.jorudan.nrkj.Launcher", 0), app.app.id)
        assertEquals(1000L, app.packageUpdatedAt)
        assertEquals(
            listOf(
                SearchKey(NormalizedText("のりかえあんない"), SearchKeyKind.READING, wordStarts = listOf(4), form = KeyForm.STRICT),
                SearchKey(NormalizedText("jorudan"), SearchKeyKind.PACKAGE_NAME),
            ),
            app.app.keys,
        )
    }

    private companion object {
        const val FILE = "schema-test.db"
        val TRANSIT = AppId("jp.co.jorudan.nrkj", "jp.co.jorudan.nrkj.Launcher", 0)
    }
}
