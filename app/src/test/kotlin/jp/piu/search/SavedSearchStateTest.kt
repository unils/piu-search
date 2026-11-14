// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUserManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 37])
class SavedSearchStateTest {
    @Test
    fun processRecreationRestoresTheQueryButDoesNotRestoreTheInputHistory() {
        val saved = Bundle()
        val original = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val before = controller(original.get())
        before.onQueryChange("おかね")
        before.onQueryChange("paypay")
        original
            .saveInstanceState(saved)
            .pause()
            .stop()
            .destroy()

        Robolectric.buildActivity(SearchActivity::class.java).create(saved).start().resume().visible().use { recreated ->
            val after = controller(recreated.get())
            assertNotSame(before, after)
            assertEquals("paypay", after.query)
            val decision = after.finishSession(OpenRequest(AppId("io.example.paypay", "Main", 0), OpenRoute.SEARCH_RESULT, "paypay"))
            assertTrue(decision.rescues.none { it.query.value == "おかね" })
        }
    }

    @Test
    fun configurationChangeKeepsTheSameViewModelTabsAndAllApps() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val users = context.getSystemService(UserManager::class.java)
        shadowOf(users).setSerialNumberForUser(Process.myUserHandle(), 0)
        shadowOf(users).addProfile(0, 10, "仕事用", ShadowUserManager.FLAG_MANAGED_PROFILE)
        shadowOf(users).setSerialNumberForUser(UserHandle.getUserHandleForUid(1_000_000), 10)
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            val before = controller(scenario.get())
            before.onWorkProfileChanged(WorkProfileState(setOf(10), paused = false))
            before.selectProfile(ProfileTab.WORK)
            before.onQueryChange("さがす")
            before.showAllApps(true)
            scenario.recreate()
            val after = controller(scenario.get())
            assertSame(before, after)
            assertEquals("さがす", after.query)
            assertEquals(true, after.showingAllApps)
            assertEquals(ProfileTab.WORK, after.profileTab)
        }
    }

    private fun controller(activity: SearchActivity): SearchController =
        SearchActivity::class.java
            .getDeclaredField("controller")
            .apply { isAccessible = true }
            .get(activity) as SearchController
}
