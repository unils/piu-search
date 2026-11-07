// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// 仕事用プロファイルの切り替え・「仕事用に N 件」・一時停止中の仕事用アプリを開いたときの OS の確認（M3-02）。
// 仕事用プロファイルを作った端末でだけ確かめる（作り方は CONTRIBUTING.md）。ない端末（CI）では記録して戻る。
// Assume で飛ばすと AGP の結果の XML に failure として残り紛らわしいため（data の InstalledAppReaderInstrumentedTest と同じ）
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WorkProfileInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = DeviceShell()
    private var workUser: UserHandle? = null
    private var removedFromPersonal = false
    private var scenario: ActivityScenario<SearchActivity>? = null

    @After
    fun restore() {
        scenario?.close()
        workUser?.let { setQuietMode(it, enabled = false) }
        if (removedFromPersonal) device.shell("cmd package install-existing --user 0 ${DeviceShell.GAME}")
        device.shell("am force-stop ${DeviceShell.GAME}")
    }

    @Test
    fun workMatchesSwitchAndPausedLaunch() {
        val work =
            context.getSystemService(LauncherApps::class.java).profiles.firstOrNull { it != Process.myUserHandle() }
                ?: return skip("仕事用プロファイルがない")
        val workId = userId(work)
        if (DeviceShell.GAME !in device.shell("pm list packages --user $workId ${DeviceShell.GAME}")) {
            return skip("仕事用プロファイルにテスト用のアプリが入っていない")
        }
        // 将棋のアプリを仕事用にだけ置く（個人用からは、データを残して外す）
        device.shell("pm uninstall --user 0 ${DeviceShell.GAME}")
        removedFromPersonal = true

        scenario = ActivityScenario.launch(SearchActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(PROFILE_SWITCH_TAG), DeviceShell.TIMEOUT_MILLIS)
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(if (DeviceShell.isJapanese) "しょうぎ" else "shogi")

        // 個人用（開いたときの選択）に一致がなく、仕事用に 1 件
        val hint = context.resources.getQuantityString(R.plurals.work_matches, 1, 1)
        compose.waitUntilAtLeastOneExists(hasTestTag(WORK_MATCHES_TAG) and hasText(hint), DeviceShell.TIMEOUT_MILLIS)
        compose.onNodeWithTag(WORK_MATCHES_TAG).performClick()
        val game = DeviceShell.label("将棋テスト", "Shogi Test")
        compose.waitUntilAtLeastOneExists(hasText(game), DeviceShell.TIMEOUT_MILLIS)

        // 一時停止の確かめには requestQuietModeEnabled（API 28 から）が要る
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return skip("API 28 未満では一時停止の操作を試せない")
        workUser = work
        setQuietMode(work, enabled = true)
        compose.waitUntilAtLeastOneExists(hasTestTag(WORK_PAUSED_TAG), DeviceShell.TIMEOUT_MILLIS)
        // 一時停止中も入口の一覧に出る（出なければ、同期で索引から消え、開けなくなる）
        assertTrue(context.getSystemService(LauncherApps::class.java).getActivityList(DeviceShell.GAME, work).isNotEmpty())

        compose.onNodeWithText(game).performClick()

        // OS が「仕事用アプリを再開しますか」を出す（AOSP の ActivityStartInterceptor.interceptQuietProfileIfNeeded）
        device.waitFor("OS の再開の確認が出る") { device.resumedActivityLines().any { "UnlaunchableAppActivity" in it } }
        device.shell("input keyevent KEYCODE_BACK")
    }

    @Test
    fun workAppInfoOpensTheWorkProfileSettings() {
        val work =
            context.getSystemService(LauncherApps::class.java).profiles.firstOrNull { it != Process.myUserHandle() }
                ?: return skip("仕事用プロファイルがない")
        val workId = userId(work)
        if (DeviceShell.GAME !in device.shell("pm list packages --user $workId ${DeviceShell.GAME}")) {
            return skip("仕事用プロファイルにテスト用のアプリが入っていない")
        }
        // 個人用から外し、個人用の設定ではそのアプリのページが開けない状態にする
        device.shell("pm uninstall --user 0 ${DeviceShell.GAME}")
        removedFromPersonal = true

        scenario = ActivityScenario.launch(SearchActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(PROFILE_SWITCH_TAG), DeviceShell.TIMEOUT_MILLIS)
        compose.onNodeWithText(context.getString(R.string.profile_work)).performClick()
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(if (DeviceShell.isJapanese) "しょうぎ" else "shogi")
        val game = DeviceShell.label("将棋テスト", "Shogi Test")
        compose.waitUntilAtLeastOneExists(hasText(game) and hasClickAction(), DeviceShell.TIMEOUT_MILLIS)

        compose.onNode(hasText(game) and hasClickAction()).performTouchInput { longClick() }
        compose.onNodeWithTag(rowActionTag(RowAction.APP_INFO)).performClick()

        // 仕事用プロファイルの設定（u<番号> の Settings）が前に出る（LauncherApps.startAppDetailsActivity）
        device.waitFor("仕事用プロファイルの設定が前に出る") {
            device.resumedActivityLines().any { "u$workId com.android.settings" in it }
        }
        device.shell("input keyevent KEYCODE_BACK")
    }

    private fun setQuietMode(
        user: UserHandle,
        enabled: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // 一時停止の操作は既定のホームアプリか shell にしか許されない。テストの間だけ shell の権限を借りる
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        try {
            context.getSystemService(UserManager::class.java).requestQuietModeEnabled(enabled, user)
        } finally {
            automation.dropShellPermissionIdentity()
        }
        device.waitFor(if (enabled) "仕事用プロファイルが止まる" else "仕事用プロファイルが動く") {
            context.getSystemService(UserManager::class.java).isQuietModeEnabled(user) == enabled
        }
    }

    /** OS のユーザーの番号（`UserHandle.getIdentifier` は公開されていないので、文字列の形から読む）。 */
    private fun userId(user: UserHandle): Int = Regex("""\{(\d+)\}""").find(user.toString())!!.groupValues[1].toInt()

    private fun skip(reason: String) {
        Log.w(TAG, "仕事用プロファイルの検査を飛ばした: $reason")
    }

    private companion object {
        const val TAG = "WorkProfileTest"
    }
}
