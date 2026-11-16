// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import jp.piu.search.IndexStatusFixtures.MIXED_INDEX
import jp.piu.search.IndexStatusFixtures.MIXED_ISSUES
import jp.piu.search.IndexStatusFixtures.PERSONAL
import jp.piu.search.IndexStatusFixtures.WORK
import jp.piu.search.IndexStatusFixtures.report
import jp.piu.search.data.IndexSyncStatus
import jp.piu.search.data.IssueCounts
import jp.piu.search.data.PackageKey
import jp.piu.search.data.ProfileReadStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// 「索引の状態」の画面（ui.md の「索引の状態」）と報告用の文の窓のスクリーンショット。正解の画像は src/test/screenshots にあり、check で比べる。
// 縦に送る画面なので、全体が入る高さで撮る
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ja-rJP-w360dp-h1400dp-xhdpi")
class IndexStatusScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun noProblems() {
        capture("index_status_ok", OK)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h1400dp-xhdpi")
    fun noProblemsInEnglish() {
        capture("index_status_ok_en", OK)
    }

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h2000dp-xhdpi")
    fun withIssues() {
        capture("index_status_issues", ISSUES)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h2000dp-xhdpi")
    fun withIssuesInEnglish() {
        capture("index_status_issues_en", ISSUES)
    }

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h3600dp-xhdpi")
    fun withIssuesAndFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        capture("index_status_issues_font_200", ISSUES)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h3600dp-xhdpi")
    fun withIssuesInEnglishAndFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        capture("index_status_issues_en_font_200", ISSUES)
    }

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h640dp-xhdpi")
    fun reportDialog() {
        captureDialog("index_report")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun reportDialogInEnglish() {
        captureDialog("index_report_en")
    }

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h640dp-xhdpi")
    fun reportDialogWithFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        captureDialog("index_report_font_200")
    }

    private fun capture(
        name: String,
        status: IndexStatus,
    ) {
        compose.setContent { PiuTheme { IndexStatusScreen(status, SAVED_AT, null, IndexStatusActions()) } }
        captureDrawnImage(compose.onRoot(), name)
    }

    private fun captureDialog(name: String) {
        val text =
            IndexReport.text(
                ISSUES,
                DeviceInfo(
                    "0.2.0",
                    5,
                    "15",
                    35,
                    "Maker",
                    "Phone 1",
                    "maker/phone/phone:15/AP1A.240905.001/12345678:user/release-keys",
                    "ja-JP,en-US",
                ),
                mapOf(PackageKey(WORK, "com.example.work") to PackageFacts(system = false, apex = false, splits = 2, key = true)),
            )
        compose.setContent { PiuTheme { IndexStatusScreen(ISSUES, SAVED_AT, text, IndexStatusActions()) } }
        captureDrawnImage(compose.onNode(isDialog()), name)
    }

    private fun captureDrawnImage(
        node: SemanticsNodeInteraction,
        name: String,
    ) {
        // SDK 37 の PixelCopy は古いフレームを返すことがあるため、同じ View を Canvas に描いて撮る（SettingsScreenshotTest と同じ）。
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

    private companion object {
        const val SAVED_AT = "2026/11/15 10:23"

        // 2026-11-15T10:23:45Z。報告用の文には日だけが入る
        const val SAVED_AT_MILLIS = 1_794_738_225_000L

        val OK: IndexStatus =
            IndexStatus.of(
                MIXED_INDEX,
                emptyList(),
                SAVED_AT_MILLIS,
                IndexSyncStatus(lastResult = report(PERSONAL to ProfileReadStatus.COMPLETE, WORK to ProfileReadStatus.COMPLETE)),
                PERSONAL,
            )

        val ISSUES: IndexStatus =
            IndexStatus.of(
                MIXED_INDEX,
                MIXED_ISSUES,
                SAVED_AT_MILLIS,
                IndexSyncStatus(
                    lastResult =
                        report(PERSONAL to ProfileReadStatus.COMPLETE, WORK to ProfileReadStatus.UNAVAILABLE, archived = 1)
                            .copy(issueCounts = IssueCounts(transient = 2, retryPending = 1, deterministic = 1, suspectedBug = 1)),
                ),
                PERSONAL,
            )
    }
}
