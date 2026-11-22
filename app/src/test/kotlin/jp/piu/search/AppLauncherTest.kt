// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUserManager

// 一時停止の判定は準備の時点、起動先は OS に渡す直前に引き直す（ui.md の「アプリを開くとき」）
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 37], shadows = [OpenFlowTest.LauncherShadow::class])
class AppLauncherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val users = context.getSystemService(UserManager::class.java)
    private val launcher = AppLauncher(context, ProfileUsers(context))
    private var launches = 0

    @Before
    fun prepare() {
        shadowOf(users).setSerialNumberForUser(Process.myUserHandle(), 0)
        OpenFlowTest.LauncherShadow.onLaunch = { launches++ }
    }

    @Test
    fun aPausedWorkProfileIsNotRecordedButAnotherPausedProfileDoesNotExcludeThePersonalApp() {
        addWorkProfile(paused = true)
        val work = launcher.prepare(AppId("io.example.app", "Main", WORK))
        assertFalse(work.recordsChoice)
        assertTrue(launcher.prepare(AppId("io.example.app", "Main", 0)).recordsChoice)
        // OS の再開の確認に渡すだけで、要求は受け付けられる
        assertTrue(work.launch())
        assertEquals(1, launches)
    }

    @Test
    fun theTargetIsLookedUpAgainRightBeforeLaunching() {
        val gone = launcher.prepare(AppId("io.example.app", "Main", 99))
        assertTrue(gone.recordsChoice)
        // 番号から引けないプロファイル（消えた）には頼まない
        assertFalse(gone.launch())
        assertEquals(0, launches)
    }

    private fun addWorkProfile(paused: Boolean) {
        val work = UserHandle.getUserHandleForUid(1_000_000)
        // Robolectric 4.17 の再開・停止要求は API 29 以降だけなので、API 28 でも停止済みの条件を直接作る。
        val quietFlag = if (paused) Class.forName("android.content.pm.UserInfo").getField("FLAG_QUIET_MODE").getInt(null) else 0
        shadowOf(users).addProfile(0, 10, "仕事用", ShadowUserManager.FLAG_MANAGED_PROFILE or quietFlag)
        shadowOf(users).setSerialNumberForUser(work, WORK)
    }

    private companion object {
        const val WORK = 10L
    }
}
