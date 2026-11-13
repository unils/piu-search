// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 起動の記録だけを消し、検索キー・学習を巻き添えにしない。
@RunWith(AndroidJUnit4::class)
class LaunchStatClearTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = LocalDatabase.open(context, null, allowMainThread = true)

    @After
    fun close() = database.close()

    @Test
    fun clearRemovesBothProfilesButKeepsLearningAndIndex() {
        val personal = AppId("io.example.app", "Main", PERSONAL)
        val work = personal.copy(profileSerial = WORK)
        val learned = LearnedMemory.EMPTY.recorded(LearningDecision(LearnedPair(NormalizedText("よみ"), personal), emptyList()), 100)
        database.learned.save(LearnedMemory.EMPTY, learned)
        val state = SyncState(1, 1, "ja-JP")
        database.index.replaceAll(emptyList(), state)
        database.launchStats.record(personal, 100)
        database.launchStats.record(work, 200)

        database.launchStats.clear()

        assertTrue(
            database.launchStats
                .load()
                .records
                .isEmpty(),
        )
        assertEquals(learned.records, database.learned.load().records)
        assertEquals(state, database.index.load().state)
        database.launchStats.record(personal, 300)
        assertEquals(
            1,
            database.launchStats
                .load()
                .records
                .getValue(personal)
                .count,
        )
    }
}
