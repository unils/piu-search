// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// ライセンスの画面（M5-01）: APK に入るすべての依存と、NOTICE（Kuromoji と IPADIC の注意書き）の全文が出ること。
// 単体テストは debug の variant の assets（ビルドが入れたもの）を読む
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ja-rJP-w360dp-h640dp-xhdpi")
class LicensesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val licenses by lazy { OssLicenses.load(context.assets) }

    @Test
    fun bundledListIsTheLicenseeReportOfTheVariant() {
        // 画面に出す一覧は、Licensee が検査に使った一覧そのもの（単体テストの作業ディレクトリは app/）
        val report = File("build/reports/licensee/androidDebug/artifacts.json")
        assertTrue("Licensee の報告がある: $report", report.isFile)
        val bundled = context.assets.open(OssLicenses.ARTIFACTS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(OssLicenses.parseLibraries(report.readText()), OssLicenses.parseLibraries(bundled))
        val coordinates = licenses.libraries.map { "${it.groupId}:${it.artifactId}" }
        assertTrue("Kuromoji の辞書が入っている", "com.atilika.kuromoji:kuromoji-ipadic" in coordinates)
        assertTrue("AndroidX が入っている", "androidx.core:core" in coordinates)
    }

    @Test
    fun noticeIsTheRootNoticeWithTheIpadicTerms() {
        assertEquals(File("../NOTICE").readText(), licenses.notice)
        assertEquals(File("../LICENSE").readText(), licenses.appLicense)
        for (phrase in IPADIC_PHRASES) assertTrue("NOTICE に「$phrase」", phrase in licenses.notice)
    }

    @Test
    fun everyLicenseOfTheLibrariesHasItsFullText() {
        // 依存に全文を入れていないライセンスが入ったら、OssLicenses.LICENSE_TEXT_ASSETS に足す
        val used =
            licenses.libraries
                .flatMap { it.licenses }
                .map { it.identifier }
                .toSet()
        assertEquals(emptySet<String?>(), used - licenses.licenseTexts.keys)
        assertTrue(licenses.licenseTexts.getValue("Apache-2.0").contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
    }

    @Test
    fun screenShowsEveryLibraryAndTheWholeNotice() {
        compose.setContent { PiuTheme { LicensesScreen(licenses) } }
        val list = compose.onNodeWithTag(LICENSES_LIST_TAG)
        for (library in licenses.libraries) {
            list.performScrollToNode(hasText(library.coordinates))
        }
        // NOTICE は段落ごとに、欠けずに出る
        for (paragraph in reflowParagraphs(licenses.notice)) {
            list.performScrollToNode(hasText(paragraph))
        }
        for (phrase in IPADIC_PHRASES) list.performScrollToNode(hasText(phrase, substring = true))
    }

    @Test
    fun fullTextsOpenInPlaceAndBackReturnsToTheList() {
        compose.setContent { PiuTheme { LicensesScreen(licenses) } }
        compose.onNodeWithTag(APP_LICENSE_TAG).performClick()
        compose.onNodeWithTag(LICENSE_TEXT_TAG).performScrollToNode(hasText("Version 2.0, January 2004", substring = true))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag(LICENSES_LIST_TAG).assertExists()

        compose.onNodeWithTag(licenseTextTag("Apache-2.0")).performClick()
        compose.onNodeWithTag(LICENSE_TEXT_TAG).performScrollToNode(hasText("Apache License", substring = true))
    }

    @Test
    fun reflowJoinsWrappedLinesButKeepsListItems() {
        val text = "A long line\n  wrapped here.\n\n# Title\n- one\n- two\n\n\n  NO WARRANTY\n"
        assertEquals(listOf("A long line wrapped here.", "# Title\n- one\n- two", "NO WARRANTY"), reflowParagraphs(text))
    }

    @Test
    fun screenshot() {
        // 依存の版は Dependabot で変わるので、撮るのは決まった見本。本物の一覧は上のテストで確かめる
        val sample =
            licenses.copy(
                libraries =
                    listOf(
                        OssLibrary("androidx.activity", "activity-compose", "1.0.0", "Activity Compose", listOf(APACHE)),
                        OssLibrary("androidx.compose.material3", "material3", "1.0.0", "Compose Material3 Components", listOf(APACHE)),
                        OssLibrary("com.atilika.kuromoji", "kuromoji-ipadic", "0.9.0", "Kuromoji IPADIC", listOf(APACHE)),
                        OssLibrary("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.0", "Kotlin Stdlib", listOf(APACHE)),
                    ),
            )
        compose.setContent { PiuTheme { LicensesScreen(sample) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/licenses.png")
    }

    private companion object {
        val APACHE = LicenseRef("Apache-2.0", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0")

        val IPADIC_PHRASES =
            listOf(
                "mecab-ipadic-2.7.0-20070801",
                "Nara Institute of Science and Technology (NAIST)",
                "originate from ICOT Free Software",
                "NO WARRANTY",
            )
    }
}
