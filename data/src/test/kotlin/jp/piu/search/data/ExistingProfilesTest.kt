// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUserManager

// 存在の問い合わせが、一時停止や利用可能なランチャーの一覧に置き換わらないことを API の境界でも確かめる。
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 37])
class ExistingProfilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(UserManager::class.java)

    @Test
    fun quietAndDisabledUsersStillExist() {
        val shadow = shadowOf(manager)
        shadow.setSerialNumberForUser(Process.myUserHandle(), 0)
        shadow.addProfile(0, 10, "仕事用", ShadowUserManager.FLAG_MANAGED_PROFILE)
        val work = manager.userProfiles.first { it != Process.myUserHandle() }
        shadow.setSerialNumberForUser(work, 11)
        manager.requestQuietModeEnabled(true, work)
        shadow.setIsUserEnabled(10, false)
        assertEquals(setOf(0L, 11L), ExistingProfiles(context).serials())
    }

    @Test
    fun unknownSerialMakesTheWholeQueryInconclusive() {
        val shadow = shadowOf(manager)
        shadow.setSerialNumberForUser(Process.myUserHandle(), 0)
        shadow.addProfile(0, 10, "仕事用", ShadowUserManager.FLAG_MANAGED_PROFILE)
        shadow.setSerialNumberForUser(manager.userProfiles.first { it != Process.myUserHandle() }, -1)
        assertNull(ExistingProfiles(context).serials())
    }
}
