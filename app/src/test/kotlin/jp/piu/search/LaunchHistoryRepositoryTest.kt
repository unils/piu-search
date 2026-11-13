// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.data.LocalDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// 読み込み前の削除でも古い記録が復活せず、削除後の起動は新しく記録される。
@RunWith(AndroidJUnit4::class)
class LaunchHistoryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = LocalDatabase.open(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val repository = LaunchHistoryRepository({ database.launchStats }, executor)

    @After
    fun close() {
        executor.shutdown()
        database.close()
        context.deleteDatabase(LocalDatabase.FILE_NAME)
    }

    @Test
    fun clearingBeforeLoadRemovesPersistedRecordsAndNotifiesListeners() {
        onDb { database.launchStats.record(APP, 100) }
        val heard = ArrayList<LaunchHistory>()
        repository.addListener { heard += it }
        var cleared = false
        repository.clear { cleared = it is StorageResult.Success }
        onDb {}
        assertTrue(cleared)
        assertEquals(LaunchHistory.EMPTY, repository.history)
        assertEquals(LaunchHistory.EMPTY, heard.last())
        assertTrue(
            onDb {
                database.launchStats
                    .load()
                    .records
                    .isEmpty()
            },
        )

        repository.record(APP, 200)
        onDb {}
        assertEquals(
            1,
            repository.history.records
                .getValue(APP)
                .count,
        )
        assertEquals(repository.history.records, onDb { database.launchStats.load().records })
    }

    @Test
    fun failedClearKeepsTheVisibleHistoryAndReportsFailure() {
        var fail = false
        val failing =
            LaunchHistoryRepository({
                check(!fail)
                database.launchStats
            }, executor)
        failing.record(APP, 100)
        onDb {}
        val before = failing.history
        fail = true
        var cleared = true
        failing.clear { cleared = it is StorageResult.Success }
        onDb {}
        assertFalse(cleared)
        assertEquals(before, failing.history)
        fail = false
        failing.clear { cleared = it is StorageResult.Success }
        onDb {}
        assertTrue(cleared)
        assertTrue(failing.history.records.isEmpty())
    }

    private fun <T> onDb(block: () -> T): T = executor.submit(Callable { block() }).get()

    private companion object {
        val APP = AppId("io.example.app", "Main", 0)
    }
}
