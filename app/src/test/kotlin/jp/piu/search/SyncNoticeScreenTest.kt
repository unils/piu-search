// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// 検索画面の索引の知らせ（ui.md の「索引の知らせ」）。どの知らせを出すかは IndexNoticeTest、ここは画面に出る形と操作
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP")
class SyncNoticeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val icons =
        object : AppIcons {
            override fun cached(app: AppId): ImageBitmap? = null

            override suspend fun load(app: AppId): ImageBitmap? = null
        }

    @Test
    fun eachNoticeShowsItsTextAndDisappearsWhenCleared() {
        var notice by mutableStateOf<IndexNotice?>(IndexNotice.SYNC_FAILED)
        compose.setContent {
            PiuTheme { SearchScreen("", {}, SearchContent.Waiting, icons, {}, {}, autoFocus = false, indexNotice = notice) }
        }
        val texts =
            mapOf(
                IndexNotice.SYNC_FAILED to "アプリの一覧を更新できませんでした。前の一覧で検索しています",
                IndexNotice.NO_INDEX to "アプリの一覧を読めませんでした",
                IndexNotice.PERSONAL_FAILED to "個人用のアプリの一覧を読めませんでした。前の一覧で検索しています",
                IndexNotice.WORK_FAILED to "仕事用のアプリの一覧を読めませんでした。前の一覧で検索しています",
            )
        for ((shown, text) in texts) {
            compose.runOnIdle { notice = shown }
            compose.onNodeWithText(text).assertIsDisplayed()
            // 1 行に出すのは 1 つだけ
            texts.values.filter { it != text }.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        }
        compose.runOnIdle { notice = null }
        compose.onNodeWithTag(INDEX_NOTICE_TAG).assertDoesNotExist()
    }

    @Test
    fun wholeRowIsAButtonThatOpensTheIndexStatus() {
        var opened = 0
        compose.setContent {
            PiuTheme {
                SearchScreen(
                    "",
                    {},
                    SearchContent.Waiting,
                    icons,
                    {},
                    {},
                    autoFocus = false,
                    indexNotice = IndexNotice.WORK_FAILED,
                    onIndexNotice = { opened++ },
                )
            }
        }
        val row = compose.onNodeWithTag(INDEX_NOTICE_TAG, useUnmergedTree = false)
        // TalkBack ではボタンとして、文と「詳細」を 1 つに読む
        row
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("文と「詳細」を持つ") {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { text -> text.text } ==
                        listOf("仕事用のアプリの一覧を読めませんでした。前の一覧で検索しています", "詳細")
                },
            )
        // 「詳細」だけでなく、文を押しても開く
        compose.onNodeWithText("仕事用のアプリの一覧を読めませんでした。前の一覧で検索しています").performClick()
        compose.onNodeWithText("詳細").performClick()
        compose.runOnIdle { assertEquals(2, opened) }
    }

    @Test
    fun visibleIconIsReloadedWhenItsRevisionChanges() {
        var iconRevision by mutableStateOf(0L)
        var loads = 0
        val bitmap =
            android.graphics.Bitmap
                .createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        val reloading =
            object : AppIcons {
                override val revision: Long get() = iconRevision

                override fun cached(app: AppId): ImageBitmap = bitmap

                override suspend fun load(app: AppId): ImageBitmap {
                    loads++
                    return bitmap
                }
            }
        compose.setContent {
            PiuTheme {
                SearchScreen(
                    "名前",
                    {},
                    SearchContent.Results(listOf(ResultRow(AppId("io.example.app", "Main", 0), "名前"))),
                    reloading,
                    {},
                    {},
                    autoFocus = false,
                )
            }
        }
        compose.waitUntil { loads == 1 }
        compose.runOnIdle { iconRevision++ }
        compose.waitForIdle()
        compose.runOnIdle { org.junit.Assert.assertTrue(loads >= 2) }
    }
}
