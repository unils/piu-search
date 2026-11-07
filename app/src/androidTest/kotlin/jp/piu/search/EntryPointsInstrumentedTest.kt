// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Activity
import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// 呼び出しの入口（ui.md の「呼び出し」）と長押しのメニュー（M3-03）を本物の Android で確かめる。
// アイコン・アシスタント・タイルのどれから何度呼んでも画面が 1 つにまとまること、既定のアシスタントの候補に入ること、
// タイルから開けること、長押しからアプリ情報が開くこと、初回の案内のボタンで OS の設定が開くこと
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EntryPointsInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = DeviceShell()
    private val restore = ArrayList<() -> Unit>()

    @After
    fun cleanUp() {
        restore.asReversed().forEach { it() }
        instrumentation.runOnMainSync { searchActivities().forEach(Activity::finish) }
        device.waitFor("検索の画面が閉じる") { onMain { searchActivities() }.isEmpty() }
    }

    @Test
    fun everyEntryReusesTheSingleScreenAndStartsEmpty() {
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launcher)
        val first = awaitResumedSearch()
        val field = compose.onNodeWithTag(SEARCH_FIELD_TAG)

        val entries =
            listOf(
                "アシスタント" to Intent(Intent.ACTION_ASSIST).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "タイル" to SearchTileService.searchIntent(context),
                "アイコン" to Intent(launcher),
            )
        for ((i, entry) in entries.withIndex()) {
            val (name, intent) = entry
            field.performTextInput("のりかえ")
            compose.waitUntil(DeviceShell.TIMEOUT_MILLIS) { fieldText() == "のりかえ" }

            // 届いたことを見分ける印。画面は extra を読まない
            context.startActivity(intent.putExtra(EXTRA_ENTRY, i))

            // 同じ画面が intent を受けるまで、Compose を読まずに待つ。
            // 受けるとき、画面は onPause → onNewIntent → onResume を通り、その間は Compose の木がテストから見えない
            // （ComposeRootRegistry は RESUMED の間だけ登録する）。間に読むと「No compose hierarchies found」で落ちる。
            // 画面のスレッドで、印のある intent を受けて RESUMED に戻ったのを見れば、その遷移は済んでいる
            waitShowingFront("$name から呼んだ intent を受ける") {
                onMain { first.intent.getIntExtra(EXTRA_ENTRY, -1) == i && first in searchActivities(Stage.RESUMED) }
            }
            // 同じ画面が intent を受け、空の検索語に戻る（新しい画面は積まれない）
            compose.waitUntil(DeviceShell.TIMEOUT_MILLIS) { fieldText() == "" }
            assertEquals("$name から呼んでも同じ画面", listOf(first), onMain { searchActivities() })
            val records = device.shell("dumpsys activity activities").lines().count { "Hist" in it && SEARCH_ACTIVITY in it }
            assertEquals("$name から呼んだ後の、OS の側の画面の数", 1, records)
        }
    }

    @Test
    fun becomesTheAssistantAndOpensWithTheAssistKey() {
        val component = ComponentName(context, SearchActivity::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val role = AssistantRole(context)
            assertTrue(role.isAvailable())
            val before =
                device
                    .shell("cmd role get-role-holders $ROLE")
                    .trim()
                    .split(';', '\n')
                    .filter { it.isNotBlank() }
            restore += {
                device.shell("cmd role remove-role-holder $ROLE ${context.packageName}")
                before.forEach { device.shell("cmd role add-role-holder $ROLE $it") }
            }
            // 役割の管理（PermissionController）は、候補でないアプリを持ち主にしない（AssistantRoleBehavior の条件）。
            // 条件を飛ばす設定（set-bypassing-role-qualification）は既定で切れている
            device.shell("cmd role add-role-holder $ROLE ${context.packageName}")
            device.waitFor("既定のアシスタントになる") { role.isHeld() }
        } else {
            // Android 9 以前は役割がなく、設定の値で既定のアシスタントを決める（AssistUtils.getAssistComponentForUser）
            val before = device.shell("settings get secure assistant").trim()
            restore += {
                if (before == "null" || before.isEmpty()) {
                    device.shell("settings delete secure assistant")
                } else {
                    device.shell("settings put secure assistant $before")
                }
            }
            device.shell("settings put secure assistant ${component.flattenToShortString()}")
        }

        // アシストのキー: 電源ボタンの長押し・隅からのスワイプと同じく、SystemUI の AssistManager が ACTION_ASSIST で開く
        device.shell("input keyevent KEYCODE_HOME")
        device.shell("input keyevent KEYCODE_ASSIST")
        awaitResumedSearch()
        assertEquals(Intent.ACTION_ASSIST, onMain { searchActivities().single().intent.action })
    }

    @Test
    fun quickSettingsTileOpensSearch() {
        val tile = ComponentName(context, SearchTileService::class.java).flattenToShortString()
        restore += {
            device.shell("cmd statusbar collapse")
            device.shell("cmd statusbar remove-tile $tile")
        }
        device.shell("input keyevent KEYCODE_HOME")
        device.shell("cmd statusbar add-tile $tile")
        device.shell("cmd statusbar expand-settings")

        // 足したばかりのタイルは、SystemUI がサービスにつなぎ終えるまで押しても無視される
        // （TileLifecycleManager の「Managed to get click on non-listening state」）。開くまで押し直す
        val deadline = SystemClock.uptimeMillis() + DeviceShell.TIMEOUT_MILLIS
        while (onMain { searchActivities(Stage.RESUMED) }.isEmpty()) {
            assertTrue("時間内にタイルから開かなかった", SystemClock.uptimeMillis() < deadline)
            device.shell("cmd statusbar click-tile $tile")
            val until = SystemClock.uptimeMillis() + TILE_RETRY_MILLIS
            while (SystemClock.uptimeMillis() < until && onMain { searchActivities(Stage.RESUMED) }.isEmpty()) {
                SystemClock.sleep(POLL_MILLIS)
            }
        }
        compose.onNodeWithTag(SEARCH_FIELD_TAG).assertExists()
    }

    @Test
    fun longPressOpensAppInfoAndBackReturnsToSearch() {
        restore += { device.shell("input keyevent KEYCODE_HOME") }
        ActivityScenario.launch(SearchActivity::class.java)
        val transit = DeviceShell.label("乗換案内テスト", "Transit Test")
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("のりかえ")
        compose.waitUntilAtLeastOneExists(hasText(transit) and hasClickAction(), DeviceShell.TIMEOUT_MILLIS)

        // このアプリから出す intent を記録する（止めずに通す）
        var started: Intent? = null
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) started = intent
                    return null
                }
            }
        instrumentation.addMonitor(monitor)
        restore += { instrumentation.removeMonitor(monitor) }

        compose.onNode(hasText(transit) and hasClickAction()).performTouchInput { longClick() }
        compose.onNodeWithTag(rowActionTag(RowAction.APP_INFO)).performClick()

        // そのアプリのページを頼み、OS の設定が前に出る
        device.waitFor("アプリ情報を頼む") { started != null }
        assertEquals("package:${DeviceShell.TRANSIT}", started?.dataString)
        device.waitFor("設定が前に出る") { device.resumedActivityLines().any { "com.android.settings" in it } }
        // 新しいタスクにしないので、「戻る」で検索の画面に戻る
        backToSearch()
    }

    @Test
    fun onboardingButtonOpensAssistantSettings() {
        // 案内を閉じていない状態から始める
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        restore += { device.shell("input keyevent KEYCODE_HOME") }
        ActivityScenario.launch(SearchActivity::class.java)

        compose.onNodeWithTag(ONBOARDING_ASSISTANT_TAG).performClick()

        // AOSP では Settings（アシストと音声入力）か PermissionController（デジタル アシスタント アプリ）
        device.waitFor("OS の設定が前に出る") {
            device.resumedActivityLines().any { line ->
                context.packageName !in line && ("settings" in line || "permissioncontroller" in line)
            }
        }
        Log.i(TAG, "アシスタントの設定: ${device.resumedActivityLines()}")
        backToSearch()
        // 設定を開いただけでは閉じたことにしない
        compose.onNodeWithTag(ONBOARDING_TAG).assertExists()
        compose.onNodeWithTag(ONBOARDING_CLOSE_TAG).performClick()
        assertTrue(SettingsStore(context).onboardingDismissed)
    }

    /**
     * 「戻る」を押して検索の画面に戻る。開いたばかりの窓は入力を受ける前に「戻る」を捨てる
     * （ViewRootImpl の「Dropping event due to no window focus」。API 26 で起きた）ので、戻るまで押し直す。
     */
    private fun backToSearch() {
        val deadline = SystemClock.uptimeMillis() + DeviceShell.TIMEOUT_MILLIS
        while (onMain { searchActivities(Stage.RESUMED) }.isEmpty()) {
            assertTrue("時間内に「戻る」で検索の画面に戻らなかった", SystemClock.uptimeMillis() < deadline)
            device.shell("input keyevent KEYCODE_BACK")
            val until = SystemClock.uptimeMillis() + BACK_RETRY_MILLIS
            while (SystemClock.uptimeMillis() < until && onMain { searchActivities(Stage.RESUMED) }.isEmpty()) {
                SystemClock.sleep(POLL_MILLIS)
            }
        }
    }

    /**
     * 検索の画面が前に出て、窓に付くまで待つ。RESUMED になるのは窓に付く（最初の描画の）前で、
     * Compose の木は窓に付いてからテストに見えるので、両方を待つ。
     */
    private fun awaitResumedSearch(): SearchActivity {
        waitShowingFront("検索の画面が前に出て窓に付く") {
            onMain {
                searchActivities(Stage.RESUMED)
                    .singleOrNull()
                    ?.window
                    ?.decorView
                    ?.isAttachedToWindow == true
            }
        }
        return onMain { searchActivities(Stage.RESUMED).single() }
    }

    /**
     * [condition] を待つ。時間切れなら、そのとき前面にあった Activity を失敗の文に添える。
     * 「No compose hierarchies found」は検索の画面が RESUMED でないときに起き、手元では再現できなかったので、
     * 再び起きたときに、何が前に出ていたかを残すため。
     */
    private fun waitShowingFront(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            device.waitFor(what, condition)
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}。前面: ${device.resumedActivityLines()}", e)
        }
    }

    private fun fieldText(): String? =
        compose
            .onNodeWithTag(SEARCH_FIELD_TAG)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text

    /** 生きている検索の画面。[stages] を省くと、作られてから壊されるまでのすべて。画面のスレッドから呼ぶ。 */
    private fun searchActivities(vararg stages: Stage = LIVE_STAGES): List<SearchActivity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return stages.flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<SearchActivity>().distinct()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** 既定のアシスタントの役割。RoleManager は API 29 からなので、古い端末でテストのクラスを読むときに解決させないよう分ける。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private class AssistantRole(
        context: Context,
    ) {
        private val roleManager = context.getSystemService(RoleManager::class.java)

        fun isAvailable(): Boolean = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)

        /** このアプリが持ち主か。持ち主を調べる権限がなくても、自分のことは引ける。 */
        fun isHeld(): Boolean = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    private companion object {
        const val TAG = "EntryPointsTest"
        const val ROLE = "android.app.role.ASSISTANT"
        const val SEARCH_ACTIVITY = "jp.piu.search/.SearchActivity"
        const val EXTRA_ENTRY = "jp.piu.search.test.ENTRY"
        const val TILE_RETRY_MILLIS = 3_000L
        const val BACK_RETRY_MILLIS = 2_000L
        const val POLL_MILLIS = 200L
        val LIVE_STAGES =
            arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
    }
}
