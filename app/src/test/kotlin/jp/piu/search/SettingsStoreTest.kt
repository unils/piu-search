// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun writesChangeTheFlowRightAwayAndANewStoreStartsFromTheSavedValues() {
        val store = SettingsStore(context)
        assertEquals(Settings(), store.settings.value)
        val top = SearchSettings(SearchBarPosition.TOP, showReasons = false, workProfileMode = WorkProfileMode.MIXED)
        store.search = top
        store.dismissOnboarding()
        // 画面は流れを購読するので、設定画面から戻ったときに読み直さなくても効く
        assertEquals(Settings(top, onboardingDismissed = true), store.settings.value)
        assertEquals(Settings(top, onboardingDismissed = true), SettingsStore(context).settings.value)
        store.showOnboardingAgain()
        assertEquals(false, store.settings.value.onboardingDismissed)
    }
}
