// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
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

// 設定（M5-01）: 保存と読み直し、設定画面の操作、OS のアプリ情報からの入口、検索の画面への反映
@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun storeHasDefaultsAndKeepsWhatWasSaved() {
        val store = SettingsStore(context)
        assertEquals(SearchSettings(SearchBarPosition.BOTTOM, showReasons = true, WorkProfileMode.SWITCH), store.search)

        store.search = SearchSettings(SearchBarPosition.TOP, showReasons = false, WorkProfileMode.HIDDEN)

        // 別の入れ物から読んでも同じ（ファイルに書いた）
        assertEquals(SearchSettings(SearchBarPosition.TOP, showReasons = false, WorkProfileMode.HIDDEN), SettingsStore(context).search)
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        // 後の版で消した値を読んでも落ちない
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("search_bar_position", "LEFT")
            .putString("work_profile_mode", "SPLIT")
            .commit()
        assertEquals(SearchSettings(), SettingsStore(context).search)
    }

    @Test
    fun onboardingCanBeShownAgain() {
        val store = SettingsStore(context)
        store.dismissOnboarding()
        assertTrue(store.onboardingDismissed)
        store.showOnboardingAgain()
        assertFalse(store.onboardingDismissed)
    }

    @Test
    fun osAppInfoFindsTheSettingsScreen() {
        // AOSP の設定は、この intent を自分のパッケージで解決できるときに「アプリ内の設定」の歯車を出す
        val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(context.packageName)
        val found = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        assertEquals(listOf(SettingsActivity::class.java.name), found)
    }

    @Test
    fun choicesAreSavedRightAway() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            compose.onNodeWithTag(searchBarOptionTag(SearchBarPosition.TOP)).performScrollTo().performClick()
            compose.onNodeWithTag(SETTINGS_SHOW_REASONS_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(workProfileOptionTag(WorkProfileMode.MIXED)).performScrollTo().performClick()
            assertEquals(SearchSettings(SearchBarPosition.TOP, showReasons = false, WorkProfileMode.MIXED), SettingsStore(context).search)

            compose.onNodeWithTag(workProfileOptionTag(WorkProfileMode.HIDDEN)).performScrollTo().performClick()
            compose.onNodeWithTag(SETTINGS_SHOW_REASONS_TAG).performScrollTo().performClick()
            assertEquals(SearchSettings(SearchBarPosition.TOP, showReasons = true, WorkProfileMode.HIDDEN), SettingsStore(context).search)
        }
    }

    @Test
    fun rowsOpenLearningLicensesAndSource() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            compose.onNodeWithTag(SETTINGS_LEARNED_TAG).performScrollTo().performClick()
            assertEquals(LearnedListActivity::class.java.name, nextStarted(scenario)?.component?.className)

            compose.onNodeWithTag(SETTINGS_LICENSES_TAG).performScrollTo().performClick()
            assertEquals(LicensesActivity::class.java.name, nextStarted(scenario)?.component?.className)

            compose.onNodeWithTag(SETTINGS_SOURCE_TAG).performScrollTo().performClick()
            val source = nextStarted(scenario)
            assertEquals(Intent.ACTION_VIEW, source?.action)
            assertEquals(BuildConfig.SOURCE_URL, source?.dataString)
        }
    }

    @Test
    fun showingTheGuideAgainOpensTheSearchScreenWithIt() {
        SettingsStore(context).dismissOnboarding()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            compose.onNodeWithTag(SETTINGS_ONBOARDING_AGAIN_TAG).performScrollTo().performClick()
            assertFalse(SettingsStore(context).onboardingDismissed)
            assertEquals(SearchActivity::class.java.name, nextStarted(scenario)?.component?.className)
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun searchScreenOpensSettingsFromTheGearAndRereadsThemOnReturn() {
        SettingsStore(context).dismissOnboarding()
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            compose.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
            scenario.onActivity { activity ->
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(ComponentName(context, SettingsActivity::class.java), started?.component)
            }
            val bottom = compose.onNodeWithTag(SEARCH_FIELD_TAG).getBoundsInRoot().top

            // 設定画面で上に変えて戻った（画面が裏に回って前に戻る）
            SettingsStore(context).search = SearchSettings(searchBarPosition = SearchBarPosition.TOP)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            val top = compose.onNodeWithTag(SEARCH_FIELD_TAG).getBoundsInRoot().top
            assertTrue("検索窓が上に移る: 下のとき $bottom、上のとき $top", top < bottom)
        }
    }

    @Test
    fun settingsAreRereadWhenTheSearchScreenResumesWithoutStopping() {
        // 設定画面を開いてすぐ戻ると、OS が検索の画面の onStop を出す前に戻ることがある（onPause → onResume だけ。
        // SettingsInstrumentedTest.choosingTopInSettingsMovesTheSearchBarUp が時々落ちた原因）
        SettingsStore(context).dismissOnboarding()
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            val bottom = compose.onNodeWithTag(SEARCH_FIELD_TAG).getBoundsInRoot().top

            scenario.moveToState(Lifecycle.State.STARTED)
            SettingsStore(context).search = SearchSettings(searchBarPosition = SearchBarPosition.TOP)
            scenario.moveToState(Lifecycle.State.RESUMED)

            val top = compose.onNodeWithTag(SEARCH_FIELD_TAG).getBoundsInRoot().top
            assertTrue("検索窓が上に移る: 下のとき $bottom、上のとき $top", top < bottom)
        }
    }

    private fun nextStarted(scenario: ActivityScenario<SettingsActivity>): Intent? {
        var started: Intent? = null
        scenario.onActivity { started = shadowOf(it).nextStartedActivity }
        return started
    }
}
