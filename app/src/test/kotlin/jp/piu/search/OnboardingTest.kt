// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

// 初回の案内（ui.md の「呼び出し」）。閉じるまで出し、閉じたら画面を開き直しても、設定を読み直しても出ない
@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun shownUntilClosedThenNeverAgain() {
        ActivityScenario.launch(SearchActivity::class.java).use {
            compose.onNodeWithTag(ONBOARDING_TAG).assertExists()
            compose.onNodeWithTag(ONBOARDING_CLOSE_TAG).performClick()
            compose.onNodeWithTag(ONBOARDING_TAG).assertDoesNotExist()
        }
        assertTrue("設定に覚えた", SettingsStore(context).onboardingDismissed)

        ActivityScenario.launch(SearchActivity::class.java).use {
            compose.onNodeWithTag(SEARCH_FIELD_TAG).assertExists()
            compose.onNodeWithTag(ONBOARDING_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun shownAgainIfTheScreenClosedWithoutClosingIt() {
        ActivityScenario.launch(SearchActivity::class.java).use {
            compose.onNodeWithTag(ONBOARDING_TAG).assertExists()
        }
        assertFalse(SettingsStore(context).onboardingDismissed)
        ActivityScenario.launch(SearchActivity::class.java).use {
            compose.onNodeWithTag(ONBOARDING_TAG).assertExists()
        }
    }

    @Test
    fun typingReplacesItWithResultsAndClearingBringsItBack() {
        ActivityScenario.launch(SearchActivity::class.java).use {
            val field = compose.onNodeWithTag(SEARCH_FIELD_TAG)
            field.performTextInput("のりかえ")
            compose.onNodeWithTag(ONBOARDING_TAG).assertDoesNotExist()
            field.performTextClearance()
            compose.onNodeWithTag(ONBOARDING_TAG).assertExists()
        }
    }

    @Test
    fun assistantButtonOpensVoiceInputSettings() {
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            compose.onNodeWithTag(ONBOARDING_ASSISTANT_TAG).performClick()
            scenario.onActivity { activity ->
                val started: Intent? = shadowOf(activity).nextStartedActivity
                assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, started?.action)
            }
            // 設定を開いただけでは閉じたことにしない
            compose.onNodeWithTag(ONBOARDING_TAG).assertExists()
        }
    }
}
