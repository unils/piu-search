// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    @Test
    fun clearHistoryDialog() {
        captureHistoryDialog("settings_clear_history_dialog")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun clearHistoryDialogInEnglish() {
        captureHistoryDialog("settings_clear_history_dialog_en")
    }

    private fun captureHistoryDialog(name: String) {
        val actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {})
        compose.setContent { PiuTheme { SettingsScreen(SearchSettings(), "0.0.1 (1)", "https://github.com/unils/piu-search", actions) } }
        compose.onNodeWithTag(SETTINGS_CLEAR_HISTORY_TAG).performScrollTo().performClick()
        captureDrawnImage(compose.onNode(isDialog()), name)
    }

    private fun captureDrawnImage(
        node: SemanticsNodeInteraction,
        name: String,
    ) {
        // SDK 37 の PixelCopy は古いフレームを返すことがあるため、同じ View を Canvas に描いて撮る。
        // https://github.com/robolectric/robolectric/issues/11392
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bitmap.density = view.resources.displayMetrics.densityDpi
            view.draw(Canvas(bitmap))
            bitmap.captureRoboImage("src/test/screenshots/$name.png")
            bitmap.recycle()
        }
    }

    private fun capture(
        name: String,
        settings: SearchSettings,
    ) {
        val actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {})
        compose.setContent { PiuTheme { SettingsScreen(settings, "0.0.1 (1)", "https://github.com/unils/piu-search", actions) } }
        captureDrawnImage(compose.onRoot(), name)
    }
}
