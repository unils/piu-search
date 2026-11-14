// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUserManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 37], shadows = [AppSourceTest.EntryInfo::class])
class AppSourceTest {
    @Test
    fun syntheticAppDetailsIsExcludedFromEveryEnumerationPath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val user = Process.myUserHandle()
        shadowOf(context.getSystemService(UserManager::class.java)).apply {
            setSerialNumberForUser(user, 0)
            setUserState(user, ShadowUserManager.UserState.STATE_RUNNING_UNLOCKED)
        }
        val packageName = "jp.piu.search.testapp.hidden"
        val actual = ComponentName(packageName, "$packageName.Main")
        val similarlyNamed = ComponentName(packageName, "$packageName.AppDetailsActivity")
        val synthetic = ComponentName(packageName, "android.app.AppDetailsActivity")
        val launcher = shadowOf(context.getSystemService(LauncherApps::class.java))
        for (component in listOf(actual, similarlyNamed, synthetic)) {
            val info = Shadow.newInstanceOf(LauncherActivityInfo::class.java)
            Shadow.extract<EntryInfo>(info).component = component
            launcher.addActivity(user, info)
        }
        val source = AppSource(context)
        val expected = setOf(actual.className, similarlyNamed.className)
        assertEquals(expected, source.activities().map { it.id.activityName }.toSet())
        assertEquals(expected, source.activities(setOf(packageName)).map { it.id.activityName }.toSet())
        assertEquals(expected, source.stamps(false).map { it.id.activityName }.toSet())
        assertEquals(
            expected,
            source
                .launcherProfiles(setOf(packageName))
                .getValue(0)
                .items
                .map { it.componentName.className }
                .toSet(),
        )
    }

    /** OS が合成した入口も LauncherApps に並べ、アプリ側の除外だけを確かめる。 */
    @Implements(LauncherActivityInfo::class)
    class EntryInfo {
        lateinit var component: ComponentName

        @Implementation
        fun getComponentName(): ComponentName = component

        @Implementation
        fun getUser(): UserHandle = Process.myUserHandle()

        @Implementation
        fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply { packageName = component.packageName }

        @Implementation
        fun getLabel(): CharSequence = "Test entry"

        @Implementation(minSdk = 31)
        fun getActivityInfo(): android.content.pm.ActivityInfo = android.content.pm.ActivityInfo()
    }
}
