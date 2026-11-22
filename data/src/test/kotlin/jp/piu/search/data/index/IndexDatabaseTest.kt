// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.Stage
import jp.piu.search.data.records.corrupt
import jp.piu.search.data.records.withRawDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// index.db の保存と読み戻しを、本物の SQLite（Robolectric）で確かめる。読めない DB は空から作り直す
@RunWith(AndroidJUnit4::class)
class IndexDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<IndexDatabase>()
    private val reader =
        FakeReader().apply {
            install(
                MAPS,
                FakeApp(
                    mapOf("Main" to "地図", "Nav" to "ナビ"),
                    signers = listOf("CN=Maps"),
                    genres =
                        mapOf("Nav" to listOf(GenreId("map"))),
                ),
            )
            install(MAIL, app("メール"))
            install(WORK_MAIL, app("メール"))
            failures[MAIL] = Stage.LABEL to TRANSIENT
        }

    @After
    fun close() {
        opened.forEach(IndexDatabase::close)
        context.deleteDatabase(FILE)
    }

    @Test
    fun aSavedSnapshotIsReadBackEqualWithTheTimeItWasSaved() {
        val first = snapshotOf(reader)
        reader.statuses[WORK] = ListingStatus.UNAVAILABLE
        val snapshot = snapshotOf(reader, previous = first)
        assertEquals(ListingStatus.UNAVAILABLE, snapshot.listings[WORK])
        open().save(snapshot, 123)
        val stored = open().load()
        assertEquals(snapshot, stored.snapshot)
        assertEquals(123L, stored.savedAt)
    }

    @Test
    fun aWriteThatFailsInTheMiddleOfTheTransactionLeavesThePreviousSnapshot() {
        val db = open()
        val first = snapshotOf(reader)
        db.save(first, 1)
        reader.install(MAPS, app("地図2").copy(apkKey = "apk-2"))
        val second = snapshotOf(reader, previous = first)
        // 組と一覧を消して書き直した後、時刻を書くところで失敗させる
        raw { it.execSQL("CREATE TRIGGER fail_saved BEFORE INSERT ON saved BEGIN SELECT RAISE(ABORT, '書き込みの失敗'); END") }
        assertThrows(RuntimeException::class.java) { db.save(second, 2) }
        assertEquals(first, db.load().snapshot)
        assertEquals(1L, db.load().savedAt)

        raw { it.execSQL("DROP TRIGGER fail_saved") }
        db.save(second, 2)
        assertEquals(second, open().load().snapshot)
    }

    @Test
    fun aDatabaseOfAnotherVersionIsDiscarded() {
        open().save(snapshotOf(reader), 1)
        opened.forEach(IndexDatabase::close)
        // 新しい版を試した後に古い版を入れ直したときの形（版が上）。下がるときも Room が捨てる
        raw {
            it.execSQL("CREATE TABLE future (id INTEGER)")
            it.version = IndexRoomDatabase.VERSION + 1
        }
        val db = open()
        assertEquals(IndexSnapshot.EMPTY, db.load().snapshot)
        assertNull(db.load().savedAt)
        val snapshot = snapshotOf(reader)
        db.save(snapshot, 2)
        assertEquals(snapshot, open().load().snapshot)
    }

    @Test
    fun aDatabaseWhoseSchemaDoesNotMatchItsVersionIsDeletedAndRebuilt() {
        open().save(snapshotOf(reader), 1)
        opened.forEach(IndexDatabase::close)
        // 版は同じで形が違う（版を上げ忘れた変更）。Room のスキーマの検証が落ちる
        raw { it.execSQL("UPDATE room_master_table SET identity_hash = 'other'") }
        val db = open()
        assertEquals(IndexSnapshot.EMPTY, db.load().snapshot)
        val snapshot = snapshotOf(reader)
        db.save(snapshot, 2)
        assertEquals(snapshot, open().load().snapshot)
    }

    @Test
    fun aCorruptFileIsRebuiltEmpty() {
        open().save(snapshotOf(reader), 1)
        opened.forEach(IndexDatabase::close)
        opened.clear()
        corrupt(context, FILE)
        val db = open()
        assertEquals(IndexSnapshot.EMPTY, db.load().snapshot)
        val snapshot = snapshotOf(reader)
        db.save(snapshot, 2)
        assertEquals(snapshot, open().load().snapshot)
    }

    @Test
    fun rowsThatCannotBeDecodedOrFailTheConsistencyCheckAreDropped() {
        val snapshot = snapshotOf(reader)
        open().save(snapshot, 1)
        val broken = checkNotNull(snapshot.packages[MAPS]).let { it.copy(observed = it.observed.copy(entries = setOf("Other"))) }
        raw {
            it.execSQL(
                "UPDATE package_state SET state = ? WHERE package_name = ?",
                arrayOf<Any>(StateCodec.encode(broken), MAPS.packageName),
            )
            it.execSQL(
                "UPDATE package_state SET state = ? WHERE profile_serial = ? AND package_name = ?",
                arrayOf<Any>(byteArrayOf(1, 2, 3), WORK, WORK_MAIL.packageName),
            )
        }
        val restored = open().load().snapshot
        // 合わない組は写しに入れず、次の同期でまだない組として作り直す
        assertEquals(setOf(MAIL), restored.packages.keys)
        assertEquals(snapshot.packages[MAIL], restored.packages[MAIL])
        assertTrue(restored.listings.isNotEmpty())
    }

    private fun open(): IndexDatabase = IndexDatabase.open(context, FILE, allowMainThread = true).also(opened::add)

    private fun raw(action: (SQLiteDatabase) -> Unit) = withRawDatabase(context, FILE, action)

    private companion object {
        const val FILE = "m802b-index-database-test.db"
        val MAPS = PackageKey(OWN, "io.example.maps")
        val MAIL = PackageKey(OWN, "io.example.mail")
        val WORK_MAIL = PackageKey(WORK, "io.example.mail")
    }
}
