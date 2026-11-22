// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

// 本物の画面の作り直し。検索語だけを保存し、構成の変更では同じ ViewModel を使う（ViewModel の中は SearchViewModelTest）
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 37], application = TestApplication::class)
class SavedSearchStateTest {
    @Test
    fun processRecreationRestoresOnlyTheQuery() {
        val saved = Bundle()
        val original = Robolectric.buildActivity(SearchActivity::class.java).setup()
        val before = viewModel(original.get())
        before.onQueryChange("おかね")
        before.onQueryChange("paypay")
        before.showAllApps(true)
        original
            .saveInstanceState(saved)
            .pause()
            .stop()
            .destroy()

        Robolectric.buildActivity(SearchActivity::class.java).create(saved).start().resume().visible().use { recreated ->
            val after = viewModel(recreated.get())
            assertNotSame(before, after)
            assertEquals("paypay", after.uiState.value.query)
            assertEquals(false, after.uiState.value.showingAllApps)
        }
    }

    @Test
    fun configurationChangeKeepsTheSameViewModelAndAllApps() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            val before = viewModel(scenario.get())
            before.onQueryChange("さがす")
            before.showAllApps(true)
            scenario.recreate()
            val after = viewModel(scenario.get())
            assertSame(before, after)
            assertEquals("さがす", after.uiState.value.query)
            assertEquals(true, after.uiState.value.showingAllApps)
        }
    }

    private fun viewModel(activity: SearchActivity): SearchViewModel = ViewModelProvider(activity)[SearchViewModel::class.java]
}
