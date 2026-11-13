// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.res.Resources
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// 本物の Android で検索の画面を開き、索引の同期が裏で済むと、テスト用のアプリ（test-apps/）が引けることを確かめる。
// Robolectric は描画と insets を模すだけなので、最小 SDK（28）と最新（37）の実物で開くことを見る
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchActivityInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<SearchActivity>()

    @Test
    fun searchActivityOpensWithSearchField() {
        compose.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
    }

    @Test
    fun testAppsAreFoundByReadingAndGenre() {
        val field = compose.onNodeWithTag(SEARCH_FIELD_TAG)
        // 開いてすぐ（索引を読む前でも）打てる
        field.performTextInput("のりかえ")
        compose.waitUntilAtLeastOneExists(hasText(label("乗換案内テスト", "Transit Test")), TIMEOUT_MILLIS)

        field.performTextClearance()
        field.performTextInput("ちず")
        compose.waitUntilAtLeastOneExists(hasText(label("テスト地図", "Test Map")), TIMEOUT_MILLIS)

        // 漢字の検索語は、読みを付けた結果が後から出る
        field.performTextClearance()
        field.performTextInput("乗り換え")
        compose.waitUntilAtLeastOneExists(hasText(label("乗換案内テスト", "Transit Test")), TIMEOUT_MILLIS)
    }

    /** 端末の言語での表示名。テスト用のアプリは日本語と英語の名前を持つ。 */
    private fun label(
        japanese: String,
        english: String,
    ): String =
        if (Resources
                .getSystem()
                .configuration.locales[0]
                .language == "ja"
        ) {
            japanese
        } else {
            english
        }

    private companion object {
        // 最初の同期は全件の作り直し（辞書の読み込みを含む）で、遅いエミュレータでは数秒かかる
        const val TIMEOUT_MILLIS = 60_000L
    }
}
