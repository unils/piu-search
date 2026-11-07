// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// 設定画面（M5-01）を本物の Android で確かめる: 検索窓を上にすると検索の画面に効くこと、OS の「アプリ情報」から設定画面が開くこと
@RunWith(AndroidJUnit4::class)
class SettingsInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = DeviceShell()

    @Before
    fun startFromDefaults() {
        clearSettings()
        // 案内が検索窓の上の場所を取らないよう閉じておく
        SettingsStore(context).dismissOnboarding()
    }

    @After
    fun cleanUp() {
        clearSettings()
        instrumentation.runOnMainSync { liveActivities().forEach(Activity::finish) }
        device.shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun choosingTopInSettingsMovesTheSearchBarUp() {
        ActivityScenario.launch(SearchActivity::class.java)
        val height = compose.onRoot().getBoundsInRoot().bottom
        val before = compose.onNodeWithTag(SEARCH_FIELD_TAG).getBoundsInRoot().top
        assertTrue("既定では検索窓が下の側: $before / $height", before > height / 4)

        // 検索窓の中の歯車から設定画面へ
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        awaitResumed<SettingsActivity>()
        compose.onNodeWithTag(searchBarOptionTag(SearchBarPosition.TOP)).performScrollTo().performClick()

        // 「戻る」で検索の画面に戻ると、上に置かれる
        pressBackUntilResumed<SearchActivity>()
        compose.waitUntil(DeviceShell.TIMEOUT_MILLIS) {
            compose.onNodeWithTag(SEARCH_FIELD_TAG).getBoundsInRoot().top < height / 4
        }
    }

    @Test
    fun osAppInfoOpensTheSettingsScreen() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // アプリ情報の画面の「アプリ内の設定」（新しい版は行、古い版は見出しの歯車）を、画面の木から探して押す
        var target: String? = null
        device.waitFor("アプリ情報にアプリ内の設定の入口が出る") {
            target = findAppSettingsEntry()
            target != null
        }
        Log.i(TAG, "アプリ内の設定の入口: $target")
        device.shell("input tap $target")

        awaitResumed<SettingsActivity>()
        compose.onNodeWithTag(SETTINGS_TAG).assertExists()
    }

    /**
     * 前面の窓の画面の木（アクセシビリティのノード）から入口を探し、押す位置（"x y"）を返す。なければ null。
     * `uiautomator dump` は、テストが UiAutomation をつないでいる間は使えないので、同じ UiAutomation で木を読む。
     */
    private fun findAppSettingsEntry(): String? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val matches =
                node.text?.toString() in ENTRY_TEXTS ||
                    node.contentDescription?.toString() in ENTRY_TEXTS ||
                    (node.className == IMAGE_BUTTON && ENTRY_IDS.any { node.viewIdResourceName.orEmpty().endsWith(it) })
            if (matches && node.isVisibleToUser) {
                val bounds = Rect().also(node::getBoundsInScreen)
                return "${bounds.centerX()} ${bounds.centerY()}"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private inline fun <reified A : Activity> awaitResumed() {
        device.waitFor("${A::class.java.simpleName} が前に出る") { onMain { resumed().any { it is A } } }
    }

    /** 開いたばかりの窓は「戻る」を捨てることがある（EntryPointsInstrumentedTest と同じ）ので、戻るまで押し直す。 */
    private inline fun <reified A : Activity> pressBackUntilResumed() {
        val deadline = SystemClock.uptimeMillis() + DeviceShell.TIMEOUT_MILLIS
        while (!onMain { resumed().any { it is A } }) {
            assertTrue("時間内に「戻る」で戻らなかった", SystemClock.uptimeMillis() < deadline)
            device.shell("input keyevent KEYCODE_BACK")
            SystemClock.sleep(BACK_RETRY_MILLIS)
        }
    }

    private fun clearSettings() {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun resumed(): Collection<Activity> = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)

    private fun liveActivities(): List<Activity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it) }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object {
        const val TAG = "SettingsTest"
        const val BACK_RETRY_MILLIS = 2_000L

        /** 入口の文字（AOSP の Settings の app_settings_link など）。エミュレータの言語の英語と日本語。 */
        val ENTRY_TEXTS = setOf("Additional settings in the app", "アプリ内のその他の設定", "App settings", "アプリの設定")

        /**
         * 古い版（Android 8 の AOSP）の見出しの歯車。AppHeaderController が ACTION_APPLICATION_PREFERENCES を解決できたときだけ
         * 見出しの ImageButton（left_button）に出す。下の「アンインストール」「強制停止」も同じ id の Button なので、型で見分ける
         */
        val ENTRY_IDS = listOf(":id/left_button", ":id/right_button")
        const val IMAGE_BUTTON = "android.widget.ImageButton"
    }
}
