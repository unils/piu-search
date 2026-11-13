// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 学習の一覧の画面（M4-03）: 行の作り方・削除と全消去の操作・スクリーンショット
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ja-rJP-w360dp-h640dp-xhdpi")
class LearnedListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun entriesAreNewestFirstWithLabelsAndProfiles() {
        val list = entries()
        assertEquals(listOf("おかね", "きんたい", "ちず", "でんしゃ"), list.map { it.pair.query.value })
        val gone = list.single { it.pair.query.value == "でんしゃ" }
        // 索引にない（消えた）アプリはパッケージ名で出す
        assertEquals("io.example.gone" to false, gone.label to gone.installed)
        assertEquals(listOf(false, true, false, false), list.map { it.work })
        assertEquals("2026/10/29", list.first().lastUsed)
    }

    @Test
    fun itemsInARowAreSeparatedByTheMiddleDotInJapanese() {
        compose.setContent { PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}) } }
        compose.onNodeWithText("勤怠管理・仕事用").assertExists()
        compose.onNodeWithText("3 回・最後に使った日 2026/10/29・検索で出なかったので覚えた").assertExists()
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun itemsInARowAreSeparatedBySpacedDotsInEnglish() {
        // 英語の画面に日本語の中黒を出さない
        compose.setContent { PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}) } }
        compose.onNodeWithText("勤怠管理 · Work").assertExists()
        compose.onNodeWithText("Used 3 times · last 2026/10/29 · learned from search").assertExists()
    }

    @Test
    fun deleteRemovesOneEntryWithoutAsking() {
        val deleted = ArrayList<LearnedEntry>()
        compose.setContent { PiuTheme { LearnedListScreen(entries(), onDelete = { deleted += it }, {}, {}, {}) } }
        compose.onNodeWithContentDescription("「おかね」と PayPay の組を消す").performClick()
        assertEquals(listOf("おかね"), deleted.map { it.pair.query.value })
    }

    @Test
    fun clearAllAsksFirst() {
        var cleared = 0
        compose.setContent { PiuTheme { LearnedListScreen(entries(), {}, onClearAll = { cleared++ }, {}, {}) } }
        compose.onNodeWithTag(LEARNED_CLEAR_TAG).performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("やめる").performClick()
        assertEquals(0, cleared)

        compose.onNodeWithTag(LEARNED_CLEAR_TAG).performClick()
        compose.onNodeWithTag(LEARNED_CLEAR_CONFIRM_TAG).performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun emptyListCannotBeExportedOrCleared() {
        compose.setContent { PiuTheme { LearnedListScreen(emptyList(), {}, {}, {}, {}) } }
        compose.onNodeWithTag(LEARNED_EXPORT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(LEARNED_CLEAR_TAG).assertIsNotEnabled()
        compose.onRoot().captureRoboImage("src/test/screenshots/learned_list_empty.png")
    }

    @Test
    fun screenshotOfTheList() {
        compose.setContent { PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/learned_list.png")
    }

    @Test
    fun screenshotOfTheListWithFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/learned_list_font_200.png")
    }

    @Test
    @Config(qualifiers = "ja-rJP-w640dp-h360dp-land-xhdpi")
    fun screenshotOfTheListInLandscapeWithFontScale200() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/learned_list_landscape_font_200.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun screenshotOfTheListInDarkTheme() {
        compose.setContent { PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/learned_list_dark.png")
    }

    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun screenshotOfTheClearDialog() {
        compose.setContent { PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}) } }
        // 指で押すと押した跡（ripple）の模様が回すたびに変わるので、跡の出ない操作で開く
        compose.onNodeWithTag(LEARNED_CLEAR_TAG).performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
        // 確かめの窓は別の窓に出るので、画面全体を撮る
        captureScreenRoboImage("src/test/screenshots/learned_clear_dialog.png")
    }

    @Test
    fun screenshotOfTheImportResult() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}, message = "学習の書き出しのファイルではないか、壊れています。学習は変えていません") }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.onRoot().captureRoboImage("src/test/screenshots/learned_import_failed.png")
    }

    @Test
    fun screenshotOfAnImportWithSkippedRecords() {
        captureImportSummary(2, 3, "learned_import_skipped", "2 件を読み込み、今の学習と合わせました（読み込まなかった記録 3 件）")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun screenshotOfAnImportWithSkippedRecordsInEnglish() {
        captureImportSummary(1, 2, "learned_import_skipped_en", "Imported 1 pair and merged it with what was learned. Skipped: 2.")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun screenshotOfAnImportWithNoAcceptedRecordsInEnglish() {
        captureImportSummary(0, 2, "learned_import_all_skipped_en", "Imported 0 pairs and merged them with what was learned. Skipped: 2.")
    }

    private fun captureImportSummary(
        imported: Int,
        skipped: Int,
        name: String,
        expected: String,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val message = pluralStringResource(R.plurals.learned_imported, imported, imported, skipped)
            PiuTheme { LearnedListScreen(entries(), {}, {}, {}, {}, message = message) }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.onNodeWithText(expected).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun entries(): List<LearnedEntry> {
        val format = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).apply { timeZone = TimeZone.getTimeZone("Asia/Tokyo") }
        return learnedEntries(MEMORY, INDEX, OWN) { format.format(Date(it)) }
    }

    private companion object {
        // 2026-10-29 12:00 （日本時間）
        const val NOW = 1_793_242_800_000L
        const val DAY = 86_400_000L
        const val OWN = 0L
        const val WORK = 10L
        const val SETTLE_MILLIS = 1_000L
        val PAYPAY = AppId("jp.ne.paypay.android.app", "Main", OWN)
        val TRANSIT = AppId("jp.co.jorudan.nrkj", "Main", OWN)
        val KINTAI = AppId("jp.example.kintai", "Main", WORK)
        val MAPS = AppId("com.google.android.apps.maps", "Main", OWN)

        val INDEX =
            SearchIndex(
                listOf(
                    IndexedApp(PAYPAY, "PayPay", listOf(SearchKey(NormalizedText("paypay"), SearchKeyKind.NAME))),
                    IndexedApp(TRANSIT, "乗換案内", listOf(SearchKey(NormalizedText("乗換案内"), SearchKeyKind.NAME))),
                    IndexedApp(KINTAI, "勤怠管理", listOf(SearchKey(NormalizedText("勤怠管理"), SearchKeyKind.NAME))),
                    IndexedApp(MAPS, "Google マップ", listOf(SearchKey(NormalizedText("googleまっぷ"), SearchKeyKind.NAME))),
                ),
            )

        val MEMORY =
            LearnedMemory(
                listOf(
                    LearnedRecord(NormalizedText("おかね"), PAYPAY, 3, NOW, rescued = true),
                    LearnedRecord(NormalizedText("きんたい"), KINTAI, 12, NOW - DAY, rescued = false),
                    LearnedRecord(NormalizedText("ちず"), MAPS, 0, NOW - 3 * DAY, rescued = false, suppressed = true),
                    LearnedRecord(NormalizedText("でんしゃ"), AppId("io.example.gone", "Main", OWN), 1, NOW - 40 * DAY, rescued = true),
                ),
            )
    }
}
