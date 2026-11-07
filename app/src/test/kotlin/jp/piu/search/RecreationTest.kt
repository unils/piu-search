// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch

// 画面を作り直しても（向きの変更など）、まだ出していない知らせを失わない（M5-02）
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP")
class RecreationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = context.appContainer

    @Before
    fun closeOnboarding() {
        SettingsStore(context).dismissOnboarding()
    }

    @Test
    fun rescueNoticeSurvivesRecreation() {
        container.learning.record(LearningDecision(null, listOf(DENSHA)), NOW) { "乗換案内" }
        container.learning.awaitIdle()
        // 知らせは数秒で下がるので、時計を止めて、出ている間に作り直す
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            compose.onNodeWithText(NOTICE, substring = true).assertExists()

            scenario.recreate()
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            compose.onNodeWithText(NOTICE, substring = true).assertExists()

            // 出し終えた知らせは、次に開いたときには出さない
            compose.onNodeWithText("取り消す").performClick()
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            assertEquals(emptyList<RescueNotice>(), container.learning.takeNotices())
            container.learning.awaitIdle()
            assertEquals(LearnedMemory.EMPTY.records, container.learning.memory.records)
        }
    }

    @Test
    fun noticesAreNotKeptAfterTheScreenCloses() {
        container.learning.record(LearningDecision(null, listOf(DENSHA)), NOW) { "乗換案内" }
        val scenario = ActivityScenario.launch(SearchActivity::class.java)
        // アプリを開いたときと同じく、閉じる（作り直さない）。出しかけの知らせは戻さない
        scenario.onActivity(Activity::finish)
        scenario.close()
        assertEquals(emptyList<RescueNotice>(), container.learning.takeNotices())
    }

    @Test
    fun importResultReachesTheRecreatedScreen() {
        val uri = Uri.parse("content://io.example.files/learning.json")
        val file = LearningBackup.encode(LearnedMemory.EMPTY.recorded(LearningDecision(DENSHA, emptyList()), NOW), PROFILES, NOW)
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(file.toByteArray()))
        val release = CountDownLatch(1)

        ActivityScenario.launch(LearnedListActivity::class.java).use { scenario ->
            container.learning.awaitIdle()
            // 一覧を読み終えてから記録のスレッドを止め、読み込みが終わる前に画面を作り直す
            container.learning.runAfterPending { release.await() }
            compose.onNodeWithTag(LEARNED_IMPORT_TAG).performClick()
            scenario.onActivity { activity ->
                val request = shadowOf(activity).nextStartedActivityForResult
                shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            }
            scenario.recreate()
            release.countDown()
            container.learning.awaitIdle()

            compose.onNodeWithText("1 件を読み込み、今の学習と合わせました").assertExists()
        }
        // 出し終える前に画面を閉じたので、箱には残る（次に開いたときに出す）
        assertEquals(BackupOutcome.Imported::class, container.backupOutcome.current?.let { it::class })
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val SETTLE_MILLIS = 1_000L
        const val NOTICE = "「でんしゃ」で 乗換案内 を出すようにしました"
        val DENSHA = LearnedPair(NormalizedText("でんしゃ"), AppId("jp.co.jorudan.nrkj", "jp.co.jorudan.nrkj.Main", 0))
        val PROFILES = BackupProfiles(own = 0, work = emptySet())
    }
}
