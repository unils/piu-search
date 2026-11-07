// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// 設定画面のスクリーンショット（M5-01）。正解の画像は src/test/screenshots にあり、check で比べる
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ja-rJP-w360dp-h640dp-xhdpi")
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settings() {
        capture("settings", SearchSettings())
    }

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h1800dp-xhdpi")
    fun settingsWholePage() {
        // 縦に送る画面の全体。上に置く・理由を出さない・混ぜる、を選んだ状態
        capture("settings_whole", SearchSettings(SearchBarPosition.TOP, showReasons = false, WorkProfileMode.MIXED))
    }

    @Test
    @Config(qualifiers = "+night")
    fun settingsInDarkTheme() {
        capture("settings_dark", SearchSettings())
    }

    @Test
    fun settingsWithFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        capture("settings_font_200", SearchSettings())
    }

    @Test
    @Config(qualifiers = "ja-rJP-w640dp-h360dp-land-xhdpi")
    fun settingsInLandscapeWithFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        capture("settings_landscape_font_200", SearchSettings())
    }

    @Test
    @Config(qualifiers = "ja-rJP-w1280dp-h800dp-land-mdpi")
    fun settingsOnATabletInLandscape() {
        capture("settings_tablet_landscape", SearchSettings())
    }

    private fun capture(
        name: String,
        settings: SearchSettings,
    ) {
        val actions = SettingsActions({}, {}, {}, {}, {}, {}, {})
        compose.setContent { PiuTheme { SettingsScreen(settings, "0.0.1 (1)", "https://github.com/unils/piu-search", actions) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
