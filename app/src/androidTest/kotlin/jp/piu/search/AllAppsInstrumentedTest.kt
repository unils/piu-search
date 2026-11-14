// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// 検索で出なかったアプリを「すべてのアプリ」から開ける（M3-02）。検索語は検索窓に残る
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AllAppsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<SearchActivity>()

    private val device = DeviceShell()
    private val learning =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.appContainer.learning

    /**
     * 「zzzq」の学習を消す。すべてのアプリから開くと救済で「zzzq」→ 乗換案内テストを覚えるので、
     * アプリのデータが残ったまま次に回すと「zzzq」で学習の行が出て、「見つかりません」にならなかった（時々落ちた原因）。
     */
    @Before
    @After
    fun forgetTheQuery() {
        device.shell("am force-stop ${DeviceShell.TRANSIT}")
        learning.requestLoad()
        learning.awaitIdle()
        learning.memory.records.keys
            .filter { it.query.value == QUERY }
            .forEach(learning::remove)
        learning.awaitIdle()
        learning.pendingNotices.forEach(learning::consumeNotice)
    }

    @Test
    fun appIsOpenedFromAllApps() {
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(QUERY)
        compose.waitUntilAtLeastOneExists(hasTestTag(NO_MATCH_TAG), DeviceShell.TIMEOUT_MILLIS)

        compose.onNodeWithTag(ALL_APPS_BUTTON_TAG).performClick()

        compose.waitUntilAtLeastOneExists(hasTestTag(ALL_APPS_LIST_TAG), DeviceShell.TIMEOUT_MILLIS)
        compose.onNodeWithTag(SEARCH_FIELD_TAG).assert(hasText(QUERY))
        val transit = DeviceShell.label("乗換案内テスト", "Transit Test")
        compose.onNodeWithTag(ALL_APPS_LIST_TAG).performScrollToNode(hasText(transit))
        compose.onNodeWithText(transit).performClick()

        device.waitFor("テスト用のアプリが前面に出る") { device.resumedActivityLines().any { DeviceShell.TRANSIT in it } }
        device.waitFor("検索の画面が閉じる") { compose.activityRule.scenario.state == Lifecycle.State.DESTROYED }
    }

    private companion object {
        /** どのアプリにも当たらない検索語。 */
        const val QUERY = "zzzq"
    }
}
