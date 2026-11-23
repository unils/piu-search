// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.records.RecordsActor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.shadows.ShadowToast

// 本物の画面・記録の書き手（piu.db）・OS への要求で、保存と起動の順序と、画面の寿命が変わっても古い完了で閉じないことを確かめる。
// 判断の細部（呼び直し・連打・失敗・一時停止）は SearchViewModelTest が偽の部品で確かめる
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 37], qualifiers = "ja-rJP", application = TestApplication::class, shadows = [OpenFlowTest.LauncherShadow::class])
class OpenFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = context.appContainer
    private var launches = 0
    private var duringLaunch: () -> Unit = {}

    @Before
    fun prepare() {
        shadowOf(context.getSystemService(UserManager::class.java)).setSerialNumberForUser(android.os.Process.myUserHandle(), 0)
        container.settings.dismissOnboarding()
        LauncherShadow.onLaunch = {
            launches++
            duringLaunch()
        }
        ShadowToast.reset()
    }

    @Test
    fun savesBeforeLaunchingAndOnlyThenShowsTheRescueToastAndCloses() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            duringLaunch = {
                // 学習と起動の記録は、OS に頼む前に同じトランザクションで書けている
                assertEquals(1, savedInt("launch_stat", "launch_count"))
                assertEquals(1, savedInt("learned", "rescued"))
                assertEquals(0, ShadowToast.shownToastCount())
            }
            TestHooks.pause {
                choose(scenario)
                assertEquals(0, launches)
                scenario.get().let { assertFalse(it.isFinishing) }
            }
            TestHooks.drain(container)
            assertEquals(1, launches)
            assertTrue(ShadowToast.getTextOfLatestToast().contains("「おかね」で アプリ を出すようにしました"))
            assertTrue(scenario.get().isFinishing)
            assertFalse(container.openRecordFailed.value)
        }
    }

    @Test
    fun aFailedSaveWritesNeitherRecordAndStillLaunchesWithoutALearnedToast() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            // 起動の記録の行を書くところで失敗させる。学習も同じトランザクションなので残らない（片方だけの保存がない）
            sql("CREATE TRIGGER reject_launch BEFORE INSERT ON launch_stat BEGIN SELECT RAISE(ABORT, '保存できない'); END")
            choose(scenario)
            TestHooks.drain(container)
            assertEquals(1, launches)
            assertEquals(0, ShadowToast.shownToastCount())
            assertTrue(container.openRecordFailed.value)
            assertEquals(0, count("learned"))
            assertTrue(scenario.get().isFinishing)
        }
    }

    @Test
    fun recreationWhileSavingDoesNotCloseTheRecreatedScreenOrLoseTheDecision() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            TestHooks.pause {
                choose(scenario)
                scenario.recreate()
            }
            TestHooks.drain(container)
            assertFalse(scenario.get().isFinishing)
            assertEquals(1, launches)
            assertTrue(
                container.records.state.value.records
                    ?.learned
                    ?.records
                    ?.getValue(PAIR)
                    ?.rescued == true,
            )
            // 戻った画面で別のアプリも選べる
            choose(scenario)
            TestHooks.drain(container)
            assertEquals(2, launches)
            assertTrue(scenario.get().isFinishing)
        }
    }

    @Test
    fun stoppingDuringTheOsLaunchKeepsTheSavedRescueAndClosesTheOriginalScreen() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            duringLaunch = { scenario.pause().stop() }
            choose(scenario)
            TestHooks.drain(container)
            assertEquals(1, launches)
            assertEquals(1, count("learned"))
            assertTrue(scenario.get().isFinishing)
        }
    }

    @Test
    fun anotherTapOnTheFinishingScreenCannotRecordOrLaunchAgain() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            choose(scenario)
            TestHooks.drain(container)
            assertTrue(scenario.get().isFinishing)
            choose(scenario)
            TestHooks.drain(container)
            assertEquals(1, launches)
            assertEquals(1, savedInt("launch_stat", "launch_count"))
        }
    }

    private fun ready(scenario: ActivityController<SearchActivity>) {
        TestHooks.drain(container)
        TestHooks.index.searchIndex.value = SearchIndex(listOf(IndexedApp(APP, "アプリ", emptyList())))
        val vm = viewModel(scenario.get())
        vm.onQueryChange("おかね")
        vm.showAllApps(true)
        // 派生の索引は裏のスレッドで作り、画面のスレッドで当てる。行が出るまで待つ
        val until = System.currentTimeMillis() + TIMEOUT_MILLIS
        while ((vm.uiState.value.content as? SearchContent.AllApps)?.rows?.any { it.app == APP } != true) {
            check(System.currentTimeMillis() < until) { "行が出ない" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(SPIN_MILLIS)
        }
        ShadowToast.reset()
    }

    private fun choose(scenario: ActivityController<SearchActivity>) {
        // 画面の行を押したときと同じ製品の入口（閉じかけ・壊れかけの画面の確認を含む）
        scenario.get().openApp(APP)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun viewModel(activity: SearchActivity): SearchViewModel =
        SearchActivity::class.java
            .getDeclaredField("viewModel")
            .apply { isAccessible = true }
            .get(activity) as SearchViewModel

    private fun sql(statement: String) {
        context.openOrCreateDatabase(RecordsActor.FILE_NAME, Context.MODE_PRIVATE, null).use { it.execSQL(statement) }
    }

    private fun count(table: String): Int =
        context.openOrCreateDatabase(RecordsActor.FILE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    private fun savedInt(
        table: String,
        column: String,
    ): Int =
        context.openOrCreateDatabase(RecordsActor.FILE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT $column FROM $table", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }

    @Implements(LauncherApps::class)
    class LauncherShadow : ShadowLauncherApps() {
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun startMainActivity(
            component: ComponentName,
            user: UserHandle,
            bounds: Rect?,
            options: Bundle?,
        ) {
            onLaunch()
        }

        companion object {
            var onLaunch: () -> Unit = {}
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val SPIN_MILLIS = 10L
        val APP = AppId("io.example.app", "Main", 0)
        val PAIR = LearnedPair(NormalizedText("おかね"), APP)
    }
}
