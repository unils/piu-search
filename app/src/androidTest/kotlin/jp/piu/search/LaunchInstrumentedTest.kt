// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.ActivityManager
import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

// 「打つ → 出る → 開く」を本物の Android で通す（M3 の終わりの条件）。
// 確定キーで 1 位のテスト用アプリが前面に出ること、開いた後に検索の画面が閉じて履歴画面（最近のタスク）に残らないこと
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LaunchInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<SearchActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun stopTestApp() {
        shell("am force-stop $TRANSIT")
    }

    @Test
    fun imeSearchActionOpensTopResultAndSearchLeavesRecents() {
        val field = compose.onNodeWithTag(SEARCH_FIELD_TAG)
        field.performTextInput("のりかえ")
        compose.waitUntilAtLeastOneExists(hasText(label("乗換案内テスト", "Transit Test")), TIMEOUT_MILLIS)

        field.performImeAction()

        waitFor("テスト用のアプリが前面に出る") { resumedActivityLines().any { TRANSIT in it } }
        waitFor("検索の画面が閉じる") { compose.activityRule.scenario.state == Lifecycle.State.DESTROYED }
        // このアプリのタスク（履歴画面に並ぶ単位）が残っていない
        val activityManager = instrumentation.targetContext.getSystemService(ActivityManager::class.java)
        waitFor("検索の画面のタスクが消える") {
            // 調べている間に消えたタスクの情報は例外になる。消えたものとして数える
            activityManager.appTasks.none { task ->
                runCatching {
                    task.taskInfo
                        ?.baseIntent
                        ?.component
                        ?.className
                }.getOrNull() == SearchActivity::class.java.name
            }
        }
        assertEquals(emptyList<String>(), resumedActivityLines().filter { SearchActivity::class.java.name in it })
    }

    /** 前面の Activity を示す行（API で書式が違う: mResumedActivity・ResumedActivity・topResumedActivity）。 */
    private fun resumedActivityLines(): List<String> = shell("dumpsys activity activities").lines().filter { "ResumedActivity" in it }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            assertTrue("時間内に「$what」にならなかった", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    /** 端末の言語での表示名。テスト用のアプリは日本語と英語の名前を持つ。 */
    private fun label(
        japanese: String,
        english: String,
    ): String =
        if (Resources
                .getSystem()
                .configuration.locales[0]
                .language == "ja"
        ) {
            japanese
        } else {
            english
        }

    private companion object {
        const val TRANSIT = "jp.piu.search.testapp.transit"

        // 最初の同期は全件の作り直し（辞書の読み込みを含む）で、遅いエミュレータでは数秒かかる
        const val TIMEOUT_MILLIS = 60_000L
        const val POLL_MILLIS = 200L
    }
}
