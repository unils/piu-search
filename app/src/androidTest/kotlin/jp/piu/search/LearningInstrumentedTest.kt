// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// 学習を本物の Android で通す（M4-02 の完了の条件）。「おかね」→ 一致なし →「すべてのアプリ」→ テスト用のアプリ（PayPay の代わり）を開く →
// 次に「おかね」でそのアプリが出る。知らせで取り消すと出ない。「この語では出さない」で出なくなる。
// 学習の一覧（M4-03）から消すと出なくなる
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LearningInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val device = DeviceShell()
    private val learning =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.appContainer.learning
    private val transit = DeviceShell.label("乗換案内テスト", "Transit Test")
    private val learnedReason = DeviceShell.label("学習: $QUERY", "Learned: $QUERY")

    @Before
    @After
    fun forgetTheQuery() {
        device.shell("am force-stop ${DeviceShell.TRANSIT}")
        learning.requestLoad()
        learning.awaitIdle()
        // 前のテストや手で試した記録（印を含む）を残さない
        learning.memory.records.keys
            .filter { it.query.value == QUERY }
            .forEach(learning::remove)
        learning.awaitIdle()
        learning.takeNotices()
    }

    @Test
    fun appOpenedFromAllAppsShowsUpForTheQueryNextTime() {
        learnByOpeningFromAllApps()

        ActivityScenario.launch(SearchActivity::class.java).use {
            // 前に開いたときの救済の知らせ
            compose.waitUntilAtLeastOneExists(hasText(undoLabel()), DeviceShell.TIMEOUT_MILLIS)
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(QUERY)
            compose.waitUntilAtLeastOneExists(hasText(transit), DeviceShell.TIMEOUT_MILLIS)
            compose.onNodeWithText(learnedReason).assertExists()
        }
    }

    @Test
    fun undoInTheNoticeForgetsTheRescue() {
        learnByOpeningFromAllApps()

        ActivityScenario.launch(SearchActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasText(undoLabel()), DeviceShell.TIMEOUT_MILLIS)
            compose.onNodeWithText(undoLabel()).performClick()
            learning.awaitIdle()
            assertTrue(
                learning.memory.records.keys
                    .none { it.query.value == QUERY },
            )

            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(QUERY)
            compose.waitUntilAtLeastOneExists(hasTestTag(NO_MATCH_TAG), DeviceShell.TIMEOUT_MILLIS)
        }
    }

    @Test
    fun suppressingHidesTheAppForTheQuery() {
        learnByOpeningFromAllApps()

        ActivityScenario.launch(SearchActivity::class.java).use {
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(QUERY)
            compose.waitUntilAtLeastOneExists(hasText(transit), DeviceShell.TIMEOUT_MILLIS)

            compose.onNode(hasText(transit) and hasClickAction()).performTouchInput { longClick() }
            compose.onNodeWithTag(rowActionTag(RowAction.SUPPRESS)).performClick()

            compose.waitUntilAtLeastOneExists(hasTestTag(NO_MATCH_TAG), DeviceShell.TIMEOUT_MILLIS)
            learning.awaitIdle()
            assertEquals(
                listOf(true),
                learning.memory.records.values
                    .filter { it.query.value == QUERY }
                    .map { it.suppressed },
            )
        }
    }

    @Test
    fun deletingFromTheLearnedListHidesTheAppForTheQuery() {
        learnByOpeningFromAllApps()
        learning.takeNotices()

        ActivityScenario.launch(SearchActivity::class.java).use {
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(QUERY)
            compose.waitUntilAtLeastOneExists(hasText(transit), DeviceShell.TIMEOUT_MILLIS)

            // 入口: 行の長押しのメニューの「学習の一覧」
            compose.onNode(hasText(transit) and hasClickAction()).performTouchInput { longClick() }
            compose.onNodeWithTag(rowActionTag(RowAction.MANAGE_LEARNING)).performClick()
            val delete = hasContentDescription(DeviceShell.label("「$QUERY」と $transit の組を消す", "Delete “$QUERY” for $transit"))
            compose.waitUntilAtLeastOneExists(hasTestTag(LEARNED_LIST_TAG), DeviceShell.TIMEOUT_MILLIS)
            compose.onNodeWithTag(LEARNED_LIST_TAG).performScrollToNode(delete)
            compose.onNode(delete).performClick()
            compose.waitUntilDoesNotExist(delete, DeviceShell.TIMEOUT_MILLIS)
            learning.awaitIdle()
            assertTrue(
                learning.memory.records.keys
                    .none { it.query.value == QUERY },
            )

            // 「戻る」で検索の画面に、検索語を残したまま戻る。その語ではもう出ない
            Espresso.pressBack()
            compose.waitUntilAtLeastOneExists(hasTestTag(NO_MATCH_TAG), DeviceShell.TIMEOUT_MILLIS)
        }
    }

    /** 「おかね」で一致がなく、すべてのアプリからテスト用の乗換のアプリを開く。 */
    private fun learnByOpeningFromAllApps() {
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(QUERY)
            compose.waitUntilAtLeastOneExists(hasTestTag(NO_MATCH_TAG), DeviceShell.TIMEOUT_MILLIS)
            compose.onNodeWithTag(ALL_APPS_BUTTON_TAG).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ALL_APPS_LIST_TAG), DeviceShell.TIMEOUT_MILLIS)
            compose.onNodeWithTag(ALL_APPS_LIST_TAG).performScrollToNode(hasText(transit))
            compose.onNodeWithText(transit).performClick()

            device.waitFor("テスト用のアプリが前面に出る") { device.resumedActivityLines().any { DeviceShell.TRANSIT in it } }
            device.waitFor("検索の画面が閉じる") { scenario.state == Lifecycle.State.DESTROYED }
        }
        learning.awaitIdle()
        assertTrue(
            learning.memory.records.keys
                .any { it.query.value == QUERY && it.app.packageName == DeviceShell.TRANSIT },
        )
    }

    private fun undoLabel(): String = DeviceShell.label("取り消す", "Undo")

    private companion object {
        const val QUERY = "おかね"
    }
}
