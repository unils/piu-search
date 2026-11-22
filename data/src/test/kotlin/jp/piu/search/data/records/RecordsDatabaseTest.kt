// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.state.RecordsWrite
import jp.piu.search.core.state.UserRecords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// 記録の DB の読み書きが、1 つのトランザクションで全部か無かになることを、本物の SQLite で確かめる
@RunWith(AndroidJUnit4::class)
class RecordsDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<RecordsDatabase>()

    @After
    fun close() {
        opened.forEach(RecordsDatabase::close)
        context.deleteDatabase(FILE)
    }

    @Test
    fun writtenRecordsAreReadBackWithTheSerialsOfEveryTable() {
        val db = open()
        db.write(seedWrite())
        // 起動の記録だけの番号も、表から直に数える
        db.write(RecordsWrite(launchPut = mapOf(OWN_APP.copy(profileSerial = 12) to LaunchRecord(1, 5))))
        val stored = db.load()
        assertEquals(
            setOf(record("よみ", OWN_APP), record("よみ", WORK_APP)),
            stored.records.learned.records.values
                .toSet(),
        )
        assertEquals(LaunchRecord(3, 1000), stored.records.launches.records[WORK_APP])
        assertEquals(setOf(OWN, WORK, 12L), stored.storedSerials)
    }

    @Test
    fun launchRecordsAreReplacedWithTheValuesOfTheSnapshot() {
        val db = open()
        db.write(seedWrite())
        db.write(RecordsWrite(launchPut = mapOf(OWN_APP to LaunchRecord(3, 2000))))
        assertEquals(
            LaunchRecord(3, 2000),
            db
                .load()
                .records.launches.records[OWN_APP],
        )
    }

    @Test
    fun overLongLegacyQueriesAreDeletedWhenRead() {
        val db = open()
        db.write(seedWrite())
        withRawDatabase(context, FILE) {
            it.execSQL("INSERT INTO learned VALUES ('${"あ".repeat(65)}', 'io.example.old', 'Main', 13, 1, 1, 0, 0)")
        }
        // 写しに見えない行は、読むときに消す（0.2.0 の LearnedStore と同じ）。消えたので番号も数えない
        assertEquals(setOf(OWN, WORK), db.load().storedSerials)
        assertEquals(0L, withRawDatabase(context, FILE) { it.count("SELECT COUNT(*) FROM learned WHERE profile_serial = 13") })
    }

    @Test
    fun aWriteThatFailsInTheMiddleOfTheTransactionChangesNoTable() {
        val db = open()
        db.write(seedWrite())
        val before = db.load()
        // 学習の行を書き、消した後、起動の記録の行を書くところで失敗させる
        withRawDatabase(context, FILE) {
            it.execSQL("CREATE TRIGGER fail_launch BEFORE INSERT ON launch_stat BEGIN SELECT RAISE(ABORT, '書き込みの失敗'); END")
        }
        val write =
            RecordsWrite(
                learnedPut = listOf(record("あたらしい", OWN_APP)),
                learnedDelete = setOf(pair("よみ", WORK_APP)),
                launchPut = mapOf(OWN_APP to LaunchRecord(3, 2000)),
            )
        assertThrows(RuntimeException::class.java) { db.write(write) }
        assertEquals(before, db.load())

        withRawDatabase(context, FILE) { it.execSQL("DROP TRIGGER fail_launch") }
        db.write(write)
        val after = db.load().records
        assertTrue(pair("あたらしい", OWN_APP) in after.learned.records)
        assertTrue(pair("よみ", WORK_APP) !in after.learned.records)
        assertEquals(LaunchRecord(3, 2000), after.launches.records[OWN_APP])
    }

    @Test
    fun pruningDeletesTheProfileFromBothTablesTogether() {
        val db = open()
        db.write(seedWrite())
        withRawDatabase(context, FILE) {
            it.execSQL(
                "CREATE TRIGGER fail_prune BEFORE DELETE ON launch_stat WHEN OLD.profile_serial = $WORK " +
                    "BEGIN SELECT RAISE(ABORT, '整理の失敗'); END",
            )
        }
        assertThrows(RuntimeException::class.java) { db.write(RecordsWrite(deleteSerials = setOf(WORK))) }
        // 学習の表を先に消していても戻る。両表の和集合でなく、表ごとに確かめる
        withRawDatabase(context, FILE) { raw ->
            assertEquals(1L, raw.count("SELECT COUNT(*) FROM learned WHERE profile_serial = $WORK"))
            assertEquals(1L, raw.count("SELECT COUNT(*) FROM launch_stat WHERE profile_serial = $WORK"))
        }
        withRawDatabase(context, FILE) { it.execSQL("DROP TRIGGER fail_prune") }
        db.write(RecordsWrite(deleteSerials = setOf(WORK)))
        assertEquals(setOf(OWN), db.load().storedSerials)
    }

    @Test
    fun clearDeletesEveryRowOfTheChosenTableOnly() {
        val db = open()
        db.write(seedWrite())
        db.write(RecordsWrite(clearLearned = true))
        val stored = db.load()
        assertTrue(
            stored.records.learned.records
                .isEmpty(),
        )
        assertEquals(setOf(OWN_APP, WORK_APP), stored.records.launches.records.keys)
    }

    @Test
    fun aCorruptDatabaseIsRebuiltEmptyAndLeavesAMark() {
        open().write(seedWrite())
        opened.forEach(RecordsDatabase::close)
        opened.clear()
        corrupt(context, FILE)
        // 壊れることはまれなので、Room の既定と同じく消して新しく始める。そのことを利用者に 1 回知らせるための印を残す
        val db = open()
        val stored = db.load()
        assertEquals(UserRecords.EMPTY, stored.records)
        assertTrue(stored.storedSerials.isEmpty())
        assertTrue(rebuiltMark(context, FILE).isFile)
        // 作り直した DB には書ける
        db.write(seedWrite())
        assertEquals(setOf(OWN, WORK), db.load().storedSerials)
    }

    private fun open(): RecordsDatabase = RecordsDatabase.open(context, FILE, allowMainThread = true, ownSerial = { OWN }).also(opened::add)

    private companion object {
        const val FILE = "m802a-records-database-test.db"
    }
}
