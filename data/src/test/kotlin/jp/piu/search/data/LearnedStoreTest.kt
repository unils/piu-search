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
