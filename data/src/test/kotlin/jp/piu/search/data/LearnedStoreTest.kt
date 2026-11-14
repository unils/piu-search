// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchIndexVersion
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.LearnedRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 学習を、ファイルのデータベースを閉じて開き直しても読めること。索引の版が上がって索引を作り直しても消えないこと（M4-02 の完了の条件）
@RunWith(AndroidJUnit4::class)
class LearnedStoreTest {
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
    fun learningSurvivesReopeningAndIndexRebuilds() {
        val database = open()
        val learned = saveSample(database)
        database.index.replaceAll(emptyList(), SyncState(1, 1, "ja-JP"))
        database.close()
        opened.remove(database)

        // 索引の版が変わると索引の表は空になるが、学習は残る
        val reopened = open(indexVersion = SearchIndexVersion.CURRENT + 1)
        assertTrue(
            reopened.index
                .load()
                .apps
                .isEmpty(),
        )
        assertEquals(learned.records, reopened.learned.load().records)
    }

    @Test
    fun onlyChangedPairsAreWrittenAndRemovedPairsAreDeleted() {
        val database = open()
        val learned = saveSample(database)

        // 選択を 1 回足し、救済を消し、印を付ける
        val next =
            learned
                .recorded(LearningDecision(MAPS_PAIR, emptyList()), NOW + DAY)
                .removed(OKANE_PAIR)
                .suppressed(LearnedPair(NormalizedText("ちず"), TRANSIT), NOW + DAY)
        database.learned.save(learned, next)

        val loaded = database.learned.load().records
        assertEquals(next.records, loaded)
        assertEquals(2, loaded.getValue(MAPS_PAIR).count)
        assertTrue(loaded.getValue(LearnedPair(NormalizedText("ちず"), TRANSIT)).suppressed)
        assertTrue(OKANE_PAIR !in loaded)
    }

    @Test
    fun forgettingIsWrittenThroughTheSameDiff() {
        val database = open()
        val learned = saveSample(database)
        val later = NOW + LearningConfig.DEFAULT.forgetAfterMillis
        database.learned.save(learned, learned.forgotten(later))
        assertEquals(emptyMap<LearnedPair, LearnedRecord>(), database.learned.load().records)
    }

    @Test
    fun loadingRemovesLegacyOverlongRowsFromTheRawTable() {
        val database = open()
        val dao = database.room.learnedDao()
        val rows =
            listOf("あ", "𠮷").flatMap { character ->
                listOf(64, 65).flatMap { length ->
                    listOf(false, true).map { suppressed ->
                        LearnedRow(
                            character.repeat(length),
                            MAPS.packageName,
                            MAPS.activityName,
                            if (suppressed) 10 else 0,
                            useCount = 3,
                            lastUsedAt = NOW,
                            rescued = true,
                            suppressed = suppressed,
                        )
                    }
                }
            }
        // 学習の上限を通さず旧版の行を作る。load().records だけでは DB に残った行を見つけられないため
        dao.put(rows)
        assertEquals(8, dao.all().size)
        val expected = rows.filter { it.query.codePointCount(0, it.query.length) == 64 }.toSet()

        val loaded = database.learned.load()

        assertEquals(
            expected
                .map {
                    it.query to it.profileSerial
                }.toSet(),
            loaded.records.keys
                .map { it.query.value to it.app.profileSerial }
                .toSet(),
        )
        assertEquals(expected, dao.all().toSet())
        database.close()
        opened.remove(database)
        assertEquals(
            expected,
            open()
                .room
                .learnedDao()
                .all()
                .toSet(),
        )
    }

    @Test
    fun clearRemovesBothProfilesButKeepsTheIndexAndLaunchRecords() {
        val database = open()
        val learned = saveSample(database).suppressed(LearnedPair(NormalizedText("ちず"), TRANSIT), NOW)
        database.learned.save(LearnedMemory.EMPTY, learned)
        val state = SyncState(1, 1, "ja-JP")
        database.index.replaceAll(emptyList(), state)
        database.launchStats.record(MAPS, NOW)
        val history = database.launchStats.load().records

        database.learned.clear()

        assertTrue(
            database.room
                .learnedDao()
                .all()
                .isEmpty(),
        )
        assertEquals(state, database.index.load().state)
        assertEquals(history, database.launchStats.load().records)
        // 消去後の記録は通常どおり保存できる
        saveSample(database)
        assertEquals(
            2,
            database.room
                .learnedDao()
                .all()
                .size,
        )
    }

    private fun saveSample(database: LocalDatabase): LearnedMemory {
        val learned =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(MAPS_PAIR, listOf(OKANE_PAIR)), NOW)
        database.learned.save(LearnedMemory.EMPTY, learned)
        assertEquals(learned.records, database.learned.load().records)
        return learned
    }

    private companion object {
        const val FILE = "learned-test.db"
        const val DAY = LearningConfig.DAY_MILLIS
        const val NOW = 1_000L * DAY
        val MAPS = AppId("io.example.maps", "io.example.maps.Main", 0)

        // 仕事用プロファイルの同じアプリは別に学習する
        val TRANSIT = AppId("io.example.transit", "io.example.transit.Main", 10)
        val MAPS_PAIR = LearnedPair(NormalizedText("ちず"), MAPS)
        val OKANE_PAIR = LearnedPair(NormalizedText("おかね"), TRANSIT)
    }
}
