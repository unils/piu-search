// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP")
class SyncFailureScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun indexFailureIsShownAndDisappearsAfterRecovery() {
        var failed by mutableStateOf(true)
        val icons =
            object : AppIcons {
                override fun cached(app: AppId): ImageBitmap? = null

                override suspend fun load(app: AppId): ImageBitmap? = null
            }
        compose.setContent {
            PiuTheme {
                SearchScreen("", {}, SearchContent.Waiting, icons, {}, {}, autoFocus = false, syncFailed = failed)
            }
        }
        compose.onNodeWithText("索引を更新できなかった").assertIsDisplayed()
        compose.runOnIdle { failed = false }
        compose.onNodeWithText("索引を更新できなかった").assertDoesNotExist()
    }

    @Test
    fun visibleIconIsReloadedWhenItsRevisionChanges() {
        var iconRevision by mutableStateOf(0L)
        var loads = 0
        val bitmap =
            android.graphics.Bitmap
                .createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        val icons =
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
                    icons,
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
