// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.SearchIndexVersion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// 起動の記録を、ファイルのデータベースを閉じて開き直しても読めること。索引を作り直しても消えないこと
@RunWith(AndroidJUnit4::class)
class LaunchStatStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<LocalDatabase>()

    @After
    fun closeDatabases() {
        opened.forEach(LocalDatabase::close)
        context.deleteDatabase(FILE)
    }

    private fun open(indexVersion: Int = SearchIndexVersion.CURRENT): LocalDatabase =
        LocalDatabase.open(context, FILE, indexVersion, allowMainThread = true).also(opened::add)

    @Test
    fun launchesAreCountedAndSurviveReopeningAndIndexRebuilds() {
        val database = open()
        database.launchStats.record(MAPS, at = 100)
        database.launchStats.record(MAPS, at = 300)
        database.launchStats.record(TRANSIT, at = 200)
        // 時計が戻っても、最後の時刻は戻さない
        database.launchStats.record(MAPS, at = 250)
        database.index.replaceAll(emptyList(), SyncState(1, 1, "ja-JP"))
        database.close()
        opened.remove(database)

        // 索引の版が変わると索引の表は空になるが、起動の記録は残る
        val reopened = open(indexVersion = SearchIndexVersion.CURRENT + 1)
        reopened.index.load()
        assertEquals(
            mapOf(MAPS to LaunchRecord(3, 300), TRANSIT to LaunchRecord(1, 200)),
            reopened.launchStats.load().records,
        )
    }

    private companion object {
        const val FILE = "launch-stat-test.db"
        val MAPS = AppId("io.example.maps", "io.example.maps.Main", 0)
        val TRANSIT = AppId("io.example.transit", "io.example.transit.Main", 10)
    }
}
