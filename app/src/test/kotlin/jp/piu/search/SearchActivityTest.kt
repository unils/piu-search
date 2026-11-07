// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<SearchActivity>()

    @Test
    fun searchFieldIsShownAndAcceptsText() {
        val field = compose.onNodeWithTag(SEARCH_FIELD_TAG)
        field.assertIsDisplayed()
        field.performTextInput("のりかえ")
        field.assertTextContains("のりかえ")
    }
}
