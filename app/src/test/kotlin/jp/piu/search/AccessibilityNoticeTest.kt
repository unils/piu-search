// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 救済の知らせの時間と重なり（#28・#29）、行の読み上げでの仕事用の区別（#27）、起動前の保存失敗の知らせ。 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP")
class AccessibilityNoticeTest {
    @get:Rule val compose = createComposeRule()

    private val icons =
        object : AppIcons {
            override fun cached(app: AppId): ImageBitmap? = null

            override suspend fun load(app: AppId): ImageBitmap? = null
        }

    private fun notice(label: String) =
        RescueNotice(LearnedPair(NormalizedText("おかね"), AppId("io.example.$label", "Main", 0)), label, null, 0L)

    @Test
    fun noticeFollowsTheRecommendedTimeoutInsteadOfAFixedSixSeconds() {
        var notices by mutableStateOf(listOf(notice("ペイ")))
        compose.setContent {
            PiuTheme {
                SearchScreen(
                    "",
                    {},
                    SearchContent.Waiting,
                    icons,
                    {},
                    {},
                    autoFocus = false,
                    notices = notices,
                    onNoticeDone = { done -> notices = notices.filterNot { it === done } },
                    // 「操作の時間」が長い設定を、基準の時間の延長で模す
                    noticeBaseMillis = 20_000L,
                )
            }
        }
        compose.mainClock.advanceTimeBy(NOTICE_MILLIS + 1_000)
        compose.onNodeWithText("「おかね」で ペイ を出すようにしました").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(20_000L)
        compose.waitForIdle()
        compose.onNodeWithText("「おかね」で ペイ を出すようにしました").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [28], qualifiers = "ja-rJP")
    fun recommendedTimeoutIsNotAvailableOnApi28() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(6_000L, noticeMillis(context, 6_000L))
    }

    @Test
    @Config(sdk = [29], qualifiers = "ja-rJP", shadows = [LongTimeoutAccessibilityShadow::class])
    fun noticeIsExtendedToTheAccessibilityManagersRecommendedTimeout() {
        var notices by mutableStateOf(listOf(notice("ペイ")))
        compose.setContent {
            PiuTheme {
                SearchScreen(
                    "",
                    {},
                    SearchContent.Waiting,
                    icons,
                    {},
                    {},
                    autoFocus = false,
                    notices = notices,
                    onNoticeDone = { done -> notices = notices.filterNot { it === done } },
                )
            }
        }
        // 基準は 6 秒のまま。推奨が 20 秒なので、6 秒を過ぎても残る
        compose.mainClock.advanceTimeBy(NOTICE_MILLIS + 1_000)
        compose.onNodeWithText("「おかね」で ペイ を出すようにしました").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(LongTimeoutAccessibilityShadow.RECOMMENDED_MILLIS)
        compose.waitForIdle()
        compose.onNodeWithText("「おかね」で ペイ を出すようにしました").assertDoesNotExist()
    }

    @Test
    fun typingDropsAllPendingNoticesAndNoNextNoticeCoversTheResults() {
        var query by mutableStateOf("")
        var notices by mutableStateOf(listOf(notice("ペイ"), notice("バンク")))
        compose.setContent {
            PiuTheme {
                SearchScreen(
                    query,
                    { query = it },
                    SearchContent.Waiting,
                    icons,
                    {},
                    {},
                    autoFocus = false,
                    notices = notices,
                    onNoticeDone = { done -> notices = notices.filterNot { it === done } },
                )
            }
        }
        compose.onNodeWithText("「おかね」で ペイ を出すようにしました").assertIsDisplayed()
        compose.runOnIdle { query = "あ" }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("「おかね」で バンク を出すようにしました").assertDoesNotExist()
        compose.onNodeWithText("「おかね」で ペイ を出すようにしました").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<RescueNotice>(), notices) }
    }

    @Test
    fun workRowIsReadAsWorkOnlyWhenMarked() {
        val personal = ResultRow(AppId("io.example.mail", "Main", 0), "メール")
        val work = ResultRow(AppId("io.example.mail", "Main", 10), "メール", isWork = true)
        compose.setContent {
            PiuTheme { SearchScreen("め", {}, SearchContent.Results(listOf(personal, work)), icons, {}, {}, autoFocus = false) }
        }
        val workState = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "仕事用")
        compose.onAllNodes(hasText("メール")).assertCountEquals(2)
        compose.onAllNodes(workState, useUnmergedTree = false).assertCountEquals(1)
    }

    @Test
    fun failedOpenRecordIsShownQuietlyAndHiddenAfterwards() {
        var failed by mutableStateOf(true)
        compose.setContent {
            PiuTheme { SearchScreen("", {}, SearchContent.Waiting, icons, {}, {}, autoFocus = false, openRecordFailed = failed) }
        }
        compose.onNodeWithText("前に選んだ記録を保存できなかった").assertIsDisplayed()
        compose.runOnIdle { failed = false }
        compose.onNodeWithText("前に選んだ記録を保存できなかった").assertDoesNotExist()
    }
}

/** ユーザー補助の「操作の時間」が長い設定を模す。Robolectric の標準の shadow は推奨時間を持たない。 */
@org.robolectric.annotation.Implements(android.view.accessibility.AccessibilityManager::class)
class LongTimeoutAccessibilityShadow : org.robolectric.shadows.ShadowAccessibilityManager() {
    @org.robolectric.annotation.Implementation
    fun getRecommendedTimeoutMillis(
        originalTimeout: Int,
        uiContentFlags: Int,
    ): Int = maxOf(originalTimeout, RECOMMENDED_MILLIS.toInt())

    companion object {
        const val RECOMMENDED_MILLIS = 20_000L
    }
}
