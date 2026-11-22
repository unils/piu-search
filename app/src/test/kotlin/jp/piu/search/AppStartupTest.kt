// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.index.IndexActor
import jp.piu.search.data.records.RecordsActor
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

// 0.2.0 の版 6 の piu.db がある端末で、製品の組み立てのまま起動する。記録が残り、索引の表は落ち、消えたプロファイルの記録は
// 検索画面を開かずに整理される（DB の層の移行そのものは data の RecordsMigrationTest）
@RunWith(AndroidJUnit4::class)
class AppStartupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aVersion6DatabaseKeepsItsRecordsAndLosesItsIndexOnTheFirstStart() {
        shadowOf(context.getSystemService(UserManager::class.java)).setSerialNumberForUser(Process.myUserHandle(), 0)
        // アプリの起動で作られた部品を止め、0.2.0 のファイルに置き換えてから、次のプロセスの起動のように作り直す
        stop(context.appContainer)
        val token = File(context.noBackupFilesDir, "installation-id").readText()
        context.deleteDatabase(RecordsActor.FILE_NAME)
        createVersion6(token)

        val next = AppContainer(context)
        try {
            runBlocking { withTimeout(TIMEOUT_MILLIS) { next.records.refresh().await() } }
            val records = checkNotNull(next.records.state.value.records)
            assertTrue(
                records.learned.records
                    .getValue(KEPT)
                    .rescued,
            )
            assertEquals(
                3,
                records.launches.records
                    .getValue(APP)
                    .count,
            )
            // 版 7 になり、索引の表は落ちる（索引は index.db に作り直す）
            val file = context.getDatabasePath(RecordsActor.FILE_NAME).path
            SQLiteDatabase.openDatabase(file, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(7, db.version)
                val index = "'app', 'search_key', 'meta', 'material_issue'"
                db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ($index)", null).use {
                    assertFalse(it.moveToFirst())
                }
                // 今ないプロファイル（11）の行は、検索画面を開かずに整理される
                db.rawQuery("SELECT COUNT(*) FROM learned WHERE profile_serial = 11", null).use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
            assertNull(records.learned.records[REMOVED])
            assertTrue(context.getDatabasePath(IndexActor.FILE_NAME).exists())
        } finally {
            stop(next)
        }
    }

    // 書き手が止まり、DB を閉じるまで待つ。子の coroutine は画面のスレッドに戻って終わるので、画面のスレッドを回しながら待つ
    private fun stop(container: AppContainer) {
        container.cancel()
        val job = checkNotNull(container.appScope.coroutineContext[Job])
        val until = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < until) { "部品が止まらない" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(SPIN_MILLIS)
        }
    }

    // 0.2.0 の Room の書き出したスキーマ（版 6）から作る。手で写すと形がずれて、移行の検証で落ちうるため
    private fun createVersion6(token: String) {
        val schema = JSONObject(File(SCHEMA).readText()).getJSONObject("database")
        val path = context.getDatabasePath(RecordsActor.FILE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO learned VALUES ('さがす', 'io.example.app', 'Main', 0, 2, 100, 1, 0)")
            db.execSQL("INSERT INTO learned VALUES ('しごと', 'io.example.work', 'Main', 11, 1, 100, 1, 0)")
            db.execSQL("INSERT INTO launch_stat VALUES ('io.example.app', 'Main', 0, 3, 100)")
            db.execSQL("INSERT INTO installation VALUES (0, ?)", arrayOf(token))
            db.execSQL(
                "INSERT INTO app (package_name, activity_name, profile_serial, label, package_updated_at) VALUES ('io.example.app', 'Main', 0, 'アプリ', 1)",
            )
            db.version = 6
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val SPIN_MILLIS = 10L
        const val SCHEMA = "../data/schemas/jp.piu.search.data.db.PiuRoomDatabase/6.json"
        val APP = AppId("io.example.app", "Main", 0)
        val KEPT = LearnedPair(NormalizedText("さがす"), APP)
        val REMOVED = LearnedPair(NormalizedText("しごと"), AppId("io.example.work", "Main", 11))
    }
}
