// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RescueNotice
import jp.piu.search.core.text.NormalizedText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

// 画面を作り直しても（向きの変更など）、まだ出していない知らせを失わない（M5-02）。本物の記録の書き手で確かめる
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP", application = TestApplication::class, shadows = [OpenFlowTest.LauncherShadow::class])
class RecreationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = context.appContainer

    @Before
    fun closeOnboarding() {
        container.settings.dismissOnboarding()
        OpenFlowTest.LauncherShadow.onLaunch = {}
    }

    @Test
    @Config(sdk = [28, 37])
    fun rotationDuringSavingLeavesTheNoticeForTheNextSearchEvenAfterSixSeconds() {
        val app = AppId("io.example.paypay", "Main", 0)
        val pair = LearnedPair(NormalizedText("おかね"), app)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            TestHooks.drain(container)
            TestHooks.index.searchIndex.value = SearchIndex(listOf(IndexedApp(app, "PayPay", emptyList())))
            scenario.onActivity { activity ->
                viewModel(activity).onQueryChange("おかね")
                viewModel(activity).showAllApps(true)
            }
            // AllApps という型だけでは、索引を待つ空の一覧も通る。実在する行の準備と描画を待って選ぶ
            compose.waitUntil(TIMEOUT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                (viewModelOf(scenario).uiState.value.content as? SearchContent.AllApps)
                    ?.rows
                    ?.any { it.app == app && it.label == "PayPay" } == true
            }
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            TestHooks.pause {
                compose.onNodeWithText("PayPay").performClick()
                scenario.recreate()
            }
            TestHooks.drain(container)
            assertEquals(
                "PayPay",
                container.records.state.value.notices
                    .single()
                    .label,
            )
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(7_000)
            // 作り直した画面は今回の起動に属する。今回の知らせはこの画面では出さない
            compose.onNodeWithText("取り消す").assertDoesNotExist()
            assertTrue(learned(pair)?.rescued == true)
            assertEquals(
                listOf(pair),
                container.records.state.value.notices
                    .map { it.pair },
            )
            scenario.onActivity(Activity::finish)
        }
        TestHooks.drain(container)
        ActivityScenario.launch(SearchActivity::class.java).use {
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            compose.onNodeWithText("「おかね」で PayPay を出すようにしました", substring = true).assertExists()
            compose.onNodeWithText("取り消す").performClick()
            TestHooks.drain(container)
            assertEquals(null, learned(pair))
        }
    }

    @Test
    fun rescueNoticeSurvivesRecreation() {
        rescue(NOW)
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
            TestHooks.drain(container)
            assertEquals(emptyList<RescueNotice>(), container.records.state.value.notices)
            assertEquals(
                LearnedMemory.EMPTY.records,
                container.records.state.value.records
                    ?.learned
                    ?.records,
            )
        }
    }

    @Test
    fun noticeSavedWhileTheNextScreenOpensCanStillBeUndone() {
        compose.mainClock.autoAdvance = false
        TestHooks.drain(container)
        lateinit var opened: ActivityScenario<SearchActivity>
        // 保存の途中で次の画面が開く。保存の完了で知らせが流れてくる
        TestHooks.pause {
            container.records.submit(
                RecordsRequest.Open(LearningDecision(null, listOf(DENSHA)), DENSHA.app, NOW, mapOf(DENSHA to "乗換案内")),
            )
            opened = ActivityScenario.launch(SearchActivity::class.java)
        }
        opened.use {
            TestHooks.drain(container)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            compose.onNodeWithText(NOTICE, substring = true).assertExists()
            compose.onNodeWithText("取り消す").performClick()
            TestHooks.drain(container)
            assertEquals(
                LearnedMemory.EMPTY.records,
                container.records.state.value.records
                    ?.learned
                    ?.records,
            )
        }
    }

    @Test
    fun newLearningDismissesTheOldUndoAndRecreationCannotRestoreIt() {
        rescue(NOW)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            compose.onNodeWithText(NOTICE, substring = true).assertExists()
            compose.onNodeWithText("取り消す").assertExists()

            request(RecordsRequest.Open(LearningDecision(DENSHA, emptyList()), DENSHA.app, NOW + 1))
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            compose.onNodeWithText(NOTICE, substring = true).assertDoesNotExist()
            compose.onNodeWithText("取り消す").assertDoesNotExist()

            scenario.recreate()
            TestHooks.drain(container)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            compose.onNodeWithText(NOTICE, substring = true).assertDoesNotExist()
            compose.onNodeWithText("取り消す").assertDoesNotExist()
            assertEquals(2, learned(DENSHA)?.count)
        }
    }

    @Test
    fun noticesAreNotKeptAfterTheScreenCloses() {
        rescue(NOW)
        val scenario = ActivityScenario.launch(SearchActivity::class.java)
        // アプリを開いたときと同じく、閉じる（作り直さない）。出しかけの知らせは戻さない
        scenario.onActivity(Activity::finish)
        scenario.close()
        TestHooks.drain(container)
        assertEquals(emptyList<RescueNotice>(), container.records.state.value.notices)
    }

    @Test
    fun importResultReachesTheRecreatedScreen() {
        val uri = Uri.parse("content://io.example.files/learning.json")
        val file = LearningBackup.encode(LearnedMemory.EMPTY.recorded(LearningDecision(DENSHA, emptyList()), NOW), PROFILES, NOW)
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(file.toByteArray()))

        ActivityScenario.launch(LearnedListActivity::class.java).use { scenario ->
            TestHooks.drain(container)
            // 一覧を読み終えてから記録の書き手を止め、読み込みが終わる前に画面を作り直す
            TestHooks.pause {
                compose.onNodeWithTag(LEARNED_IMPORT_TAG).performClick()
                scenario.onActivity { activity ->
                    val request = shadowOf(activity).nextStartedActivityForResult
                    shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
                }
                scenario.recreate()
            }
            TestHooks.drain(container)
            compose.waitUntil(TIMEOUT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                container.backupOutcome.current.value != null
            }
            compose.onNodeWithText("1 件を読み込み、今の学習と合わせました（読み込まなかった記録 0 件）").assertExists()
        }
        // 出し終える前に画面を閉じたので、箱には残る（次に開いたときに出す）
        assertEquals(
            BackupOutcome.Imported::class,
            container.backupOutcome.current.value
                ?.value
                ?.let { it::class },
        )
    }

    private fun rescue(at: Long) {
        TestHooks.drain(container)
        request(RecordsRequest.Open(LearningDecision(null, listOf(DENSHA)), DENSHA.app, at, mapOf(DENSHA to "乗換案内")))
    }

    private fun request(request: RecordsRequest) {
        runBlocking { withTimeout(TIMEOUT_MILLIS) { container.records.request(request) } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun learned(pair: LearnedPair) =
        container.records.state.value.records
            ?.learned
            ?.records
            ?.get(pair)

    private fun viewModelOf(scenario: ActivityScenario<SearchActivity>): SearchViewModel {
        lateinit var result: SearchViewModel
        scenario.onActivity { result = viewModel(it) }
        return result
    }

    private fun viewModel(activity: SearchActivity): SearchViewModel = ViewModelProvider(activity)[SearchViewModel::class.java]

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val SETTLE_MILLIS = 1_000L
        const val TIMEOUT_MILLIS = 10_000L
        const val NOTICE = "「でんしゃ」で 乗換案内 を出すようにしました"
        val DENSHA = LearnedPair(NormalizedText("でんしゃ"), AppId("jp.co.jorudan.nrkj", "jp.co.jorudan.nrkj.Main", 0))
        val PROFILES = BackupProfiles(own = 0, work = emptySet())
    }
}
