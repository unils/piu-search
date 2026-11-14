// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.LocalDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
import org.robolectric.shadows.ShadowUserManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// OS へ渡す瞬間と保存の待機を分け、画面の寿命が変わっても古い完了で閉じないことを確かめる。
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 37], qualifiers = "ja-rJP", shadows = [OpenFlowTest.LauncherShadow::class])
class OpenFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = context.appContainer
    private var launches = 0
    private var duringLaunch: () -> Unit = {}

    @Before
    fun prepare() {
        container.settings.dismissOnboarding()
        shadowOf(context.getSystemService(UserManager::class.java)).setSerialNumberForUser(android.os.Process.myUserHandle(), 0)
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
                assertEquals(1, savedInt("launch_stat", "launch_count"))
                assertEquals(1, savedInt("learned", "rescued"))
                assertEquals(
                    1,
                    container.launchHistory.history.records
                        .getValue(APP)
                        .count,
                )
                assertTrue(
                    container.learning.memory.records
                        .getValue(PAIR)
                        .rescued,
                )
                assertEquals(0, ShadowToast.shownToastCount())
            }
            withSavingPaused {
                choose(scenario)
                assertEquals(0, launches)
                assertEquals(0, ShadowToast.shownToastCount())
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
            drain()
            assertEquals(1, launches)
            assertTrue(ShadowToast.getTextOfLatestToast().contains("「おかね」で アプリ を出すようにしました"))
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun failedLearningSaveStillLaunchesWithoutALearnedToast() {
        assertFailedSave("learned")
    }

    @Test
    fun failedHistorySaveStillLaunchesWithoutALearnedToast() {
        assertFailedSave("launch_stat")
    }

    @Test
    fun newIntentWhileSavingKeepsTheNewSearchAndTheOriginalDecision() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            withSavingPaused {
                choose(scenario)
                scenario.onActivity {
                    newIntent(it)
                    controller(it).onQueryChange("あたらしい")
                    controller(it).onIndexChanged(SearchIndex(listOf(IndexedApp(APP, "変わった名前", emptyList()))))
                }
            }
            drain()
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertEquals("あたらしい", controller(it).query)
            }
            assertTrue(
                container.learning.memory.records
                    .getValue(PAIR)
                    .rescued,
            )
            assertEquals(
                "アプリ",
                container.learning
                    .pendingNotices
                    .single()
                    .label,
            )
        }
    }

    @Test
    fun recreationWhileSavingDoesNotCloseTheRecreatedScreenOrLoseTheDecision() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            withSavingPaused {
                choose(scenario)
                scenario.recreate()
            }
            drain()
            scenario.onActivity { assertFalse(it.isFinishing) }
            assertEquals(1, launches)
            assertTrue(
                container.learning.memory.records
                    .getValue(PAIR)
                    .rescued,
            )
        }
    }

    @Test
    fun successfulLaunchAfterRecreationAllowsAnotherChoiceOnReturn() {
        val other = AppId("io.example.other", "Main", 0)
        val index = SearchIndex(listOf(IndexedApp(APP, "アプリ", emptyList()), IndexedApp(other, "別のアプリ", emptyList())))
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario, index)
            duringLaunch = { scenario.pause().stop() }
            withSavingPaused {
                choose(scenario)
                scenario.recreate()
            }
            drain()
            assertEquals(1, launches)
            assertFalse(scenario.get().isFinishing)

            scenario.start().resume().visible()
            ready(scenario, index)
            duringLaunch = {}
            open(scenario.get(), controller(scenario.get()).openRequest(other))
            drain()

            assertEquals(2, launches)
            assertEquals(
                1,
                container.launchHistory.history.records
                    .getValue(other)
                    .count,
            )
            assertTrue(scenario.get().isFinishing)
        }
    }

    @Test
    fun failedLaunchAfterRecreationRestartsTheVisibleSessionAndAllowsRetry() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            duringLaunch = { throw android.content.ActivityNotFoundException() }
            withSavingPaused {
                choose(scenario)
                scenario.recreate()
            }
            drain()
            assertEquals(1, launches)
            assertFalse(scenario.get().isFinishing)
            assertTrue(tracker(controller(scenario.get())).size > 0)

            duringLaunch = {}
            choose(scenario)
            drain()
            assertEquals(2, launches)
            assertTrue(scenario.get().isFinishing)
        }
    }

    @Test
    fun anotherTapOnTheFinishingScreenCannotRecordOrLaunchAgain() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            choose(scenario)
            drain()
            assertTrue(scenario.get().isFinishing)
            choose(scenario)
            drain()
            assertEquals(1, launches)
            assertEquals(
                1,
                container.launchHistory.history.records
                    .getValue(APP)
                    .count,
            )
        }
    }

    @Test
    fun failedLaunchWhileStoppedDoesNotRestartInputOrReadingUntilReturning() {
        val query = "送金"
        val index = SearchIndex(listOf(IndexedApp(APP, "アプリ", listOf(SearchKey(NormalizedText(query), SearchKeyKind.NAME)))))
        val readingStarted = CountDownLatch(1)
        val releaseReading = CountDownLatch(1)
        try {
            Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
                ready(scenario, index)
                container.queryExecutor.execute {
                    readingStarted.countDown()
                    check(releaseReading.await(10, TimeUnit.SECONDS))
                }
                check(readingStarted.await(10, TimeUnit.SECONDS))
                container.analyzer.release()
                controller(scenario.get()).onQueryChange(query)
                assertEquals(APP, controller(scenario.get()).topResult)
                duringLaunch = { throw android.content.ActivityNotFoundException() }
                withSavingPaused {
                    open(scenario.get(), controller(scenario.get()).openRequest(APP))
                    scenario.pause().stop()
                    assertEquals(0, tracker(controller(scenario.get())).size)
                }
                drain()
                releaseReading.countDown()
                container.queryExecutor.submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(1, launches)
                assertFalse(scenario.get().isFinishing)
                assertEquals(0 to false, tracker(controller(scenario.get())).size to container.analyzer.isLoaded)

                scenario.start().resume().visible()
                shadowOf(Looper.getMainLooper()).idle()
                container.queryExecutor.submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(tracker(controller(scenario.get())).size > 0)
                assertTrue(container.analyzer.isLoaded)
            }
        } finally {
            releaseReading.countDown()
        }
    }

    @Test
    fun rotationKeepsTheQueryAndSessionAndRecordsTheEarlierFailedAttempt() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            val before = controller(scenario.get())
            before.showAllApps(false)
            before.onQueryChange("paypay")
            scenario.recreate()
            assertSame(before, controller(scenario.get()))
            assertEquals("paypay", controller(scenario.get()).query)
            open(scenario.get(), OpenRequest(APP, OpenRoute.SEARCH_RESULT, "paypay"))
            drain()
            assertTrue(
                container.learning.memory.records
                    .getValue(PAIR)
                    .rescued,
            )
        }
    }

    @Test
    fun stoppingWhileSavingDoesNotLoseTheDecision() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            withSavingPaused {
                choose(scenario)
                scenario.pause().stop()
            }
            drain()
            assertTrue(
                container.learning.memory.records
                    .getValue(PAIR)
                    .rescued,
            )
            assertEquals(1, launches)
        }
    }

    @Test
    fun newIntentDuringTheOsLaunchIsAlsoProtectedFromTheOldFinish() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                duringLaunch = {
                    newIntent(activity)
                    controller(activity).onQueryChange("べつ")
                }
            }
            choose(scenario)
            drain()
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertEquals("べつ", controller(it).query)
            }
        }
    }

    @Test
    fun recreationDuringTheOsLaunchKeepsTheRecreatedScreen() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            duringLaunch = { scenario.recreate() }
            choose(scenario)
            drain()
            assertEquals(1, launches)
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertEquals("おかね", controller(it).query)
            }
        }
    }

    @Test
    fun stoppingDuringTheOsLaunchKeepsTheSavedRescueAndClosesTheOriginalScreen() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            duringLaunch = { scenario.pause().stop() }
            choose(scenario)
            drain()
            assertEquals(1, launches)
            assertTrue(
                container.learning.memory.records
                    .getValue(PAIR)
                    .rescued,
            )
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun repeatedTapsWhileSavingOnlyRecordAndLaunchOnce() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            withSavingPaused {
                choose(scenario)
                choose(scenario)
            }
            drain()
            assertEquals(1, launches)
            assertEquals(
                1,
                container.launchHistory.history.records
                    .getValue(APP)
                    .count,
            )
            assertEquals(
                1,
                container.learning.memory.records
                    .getValue(PAIR)
                    .count,
            )
        }
    }

    @Test
    fun pausedWorkRequestDoesNotCountOrLearnEvenWhenOsReturnsNormally() {
        pauseWorkProfile()
        val work = APP.copy(profileSerial = 10)
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            choose(scenario, work)
            drain()
            assertEquals(1, launches)
            assertTrue(
                container.launchHistory.history.records
                    .isEmpty(),
            )
            assertTrue(
                container.learning.memory.records
                    .isEmpty(),
            )
            assertEquals(0, ShadowToast.shownToastCount())
        }
    }

    @Test
    fun anotherPausedProfileDoesNotExcludeThePersonalChoice() {
        pauseWorkProfile()
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            choose(scenario)
            drain()
            assertEquals(1, launches)
            assertEquals(
                1,
                container.launchHistory.history.records
                    .getValue(APP)
                    .count,
            )
            assertTrue(
                container.learning.memory.records
                    .getValue(PAIR)
                    .rescued,
            )
        }
    }

    @Test
    fun pausedWorkRequestDoesNotRefreshAnExistingLearnedKey() {
        pauseWorkProfile()
        val work = APP.copy(profileSerial = 10)
        val learned = PAIR.copy(app = work)
        container.learning.record(LearningDecision(null, listOf(learned)), System.currentTimeMillis() - 1_000) { "仕事用アプリ" }
        container.learning.awaitIdle()
        container.learning.pendingNotices.forEach(container.learning::consumeNotice)
        val before = container.learning.memory.records
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                open(activity, OpenRequest(work, OpenRoute.SEARCH_RESULT, "おか", learnedPair = learned))
            }
            drain()
            assertEquals(1, launches)
            assertEquals(before, container.learning.memory.records)
            assertTrue(
                container.launchHistory.history.records
                    .isEmpty(),
            )
            assertEquals(0, ShadowToast.shownToastCount())
        }
    }

    @Test
    fun activeWorkRequestRecordsTheChoiceBeforeLaunching() {
        addWorkProfile()
        val work = APP.copy(profileSerial = 10)
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            duringLaunch = {
                assertEquals(
                    1,
                    container.launchHistory.history.records
                        .getValue(work)
                        .count,
                )
                assertEquals(
                    1,
                    container.learning.memory.records
                        .getValue(PAIR.copy(app = work))
                        .count,
                )
            }
            choose(scenario, work, OpenRoute.SEARCH_RESULT)
            drain()
            assertEquals(1, launches)
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun failedOsLaunchKeepsSavedChoicesWithoutALearnedToastAndAllowsRetry() {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            duringLaunch = { throw android.content.ActivityNotFoundException() }
            choose(scenario)
            drain()
            scenario.onActivity { assertFalse(it.isFinishing) }
            assertEquals(
                1,
                container.launchHistory.history.records
                    .getValue(APP)
                    .count,
            )
            assertTrue(
                container.learning.memory.records
                    .getValue(PAIR)
                    .rescued,
            )
            assertEquals(context.getString(R.string.launch_failed, "アプリ"), ShadowToast.getTextOfLatestToast())
            duringLaunch = {}
            choose(scenario)
            drain()
            assertEquals(2, launches)
        }
    }

    private fun assertFailedSave(table: String) {
        Robolectric.buildActivity(SearchActivity::class.java).setup().use { scenario ->
            ready(scenario)
            sql("CREATE TRIGGER r2_reject_insert BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, '保存できない'); END")
            try {
                choose(scenario)
                drain()
                assertEquals(1, launches)
                assertEquals(0, ShadowToast.shownToastCount())
                scenario.onActivity { assertTrue(it.isFinishing) }
                if (table == "learned") {
                    assertTrue(
                        container.learning.memory.records
                            .isEmpty(),
                    )
                    assertEquals(
                        1,
                        container.launchHistory.history.records
                            .getValue(APP)
                            .count,
                    )
                } else {
                    assertTrue(
                        container.launchHistory.history.records
                            .isEmpty(),
                    )
                    assertTrue(
                        container.learning.memory.records
                            .getValue(PAIR)
                            .rescued,
                    )
                    assertEquals(
                        PAIR,
                        container.learning
                            .pendingNotices
                            .single()
                            .pair,
                    )
                }
            } finally {
                sql("DROP TRIGGER r2_reject_insert")
            }
        }
    }

    private fun ActivityController<SearchActivity>.onActivity(action: (SearchActivity) -> Unit) {
        action(get())
    }

    private fun pauseWorkProfile() {
        val work = addWorkProfile(paused = true)
        assertTrue(context.getSystemService(UserManager::class.java).isQuietModeEnabled(work))
    }

    private fun addWorkProfile(paused: Boolean = false): UserHandle {
        val users = context.getSystemService(UserManager::class.java)
        val work = UserHandle.getUserHandleForUid(1_000_000)
        // Robolectric 4.17 の再開・停止要求は API 29 以降だけなので、API 28 でも停止済みの条件を直接作る。
        val quietFlag = if (paused) Class.forName("android.content.pm.UserInfo").getField("FLAG_QUIET_MODE").getInt(null) else 0
        shadowOf(users).addProfile(0, 10, "仕事用", ShadowUserManager.FLAG_MANAGED_PROFILE or quietFlag)
        shadowOf(users).setSerialNumberForUser(work, 10)
        return work
    }

    private fun ready(
        scenario: ActivityController<SearchActivity>,
        index: SearchIndex = SearchIndex(listOf(IndexedApp(APP, "アプリ", emptyList()))),
    ) {
        container.learning.awaitIdle()
        shadowOf(Looper.getMainLooper()).idle()
        scenario.onActivity {
            controller(it).onIndexChanged(index)
        }
        shadowOf(Looper.getMainLooper()).idle()
        container.derivedIndexExecutor.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        scenario.onActivity {
            controller(it).onQueryChange("おかね")
            controller(it).showAllApps(true)
        }
        ShadowToast.reset()
    }

    private fun choose(
        scenario: ActivityController<SearchActivity>,
        app: AppId = APP,
        route: OpenRoute = OpenRoute.ALL_APPS,
    ) {
        scenario.onActivity { activity ->
            open(activity, OpenRequest(app, route, "おかね"))
        }
    }

    private fun open(
        activity: SearchActivity,
        request: OpenRequest,
    ) {
        SearchActivity::class.java
            .getDeclaredMethod(
                "open",
                OpenRequest::class.java,
            ).apply { isAccessible = true }
            .invoke(activity, request)
    }

    private fun newIntent(activity: SearchActivity) {
        SearchActivity::class.java
            .getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }
            .invoke(activity, Intent(context, SearchActivity::class.java).setAction(Intent.ACTION_ASSIST))
    }

    private fun controller(activity: SearchActivity): SearchController =
        SearchActivity::class.java
            .getDeclaredField("controller")
            .apply { isAccessible = true }
            .get(activity) as SearchController

    private fun tracker(controller: SearchController): SessionTracker =
        SearchController::class.java
            .getDeclaredField("tracker")
            .apply { isAccessible = true }
            .get(controller) as SessionTracker

    private fun withSavingPaused(action: () -> Unit) {
        val release = CountDownLatch(1)
        container.learning.runAfterPending { check(release.await(10, TimeUnit.SECONDS)) }
        try {
            action()
        } finally {
            release.countDown()
        }
    }

    private fun drain() {
        container.learning.awaitIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sql(statement: String) {
        context.openOrCreateDatabase(LocalDatabase.FILE_NAME, Context.MODE_PRIVATE, null).use { it.execSQL(statement) }
    }

    private fun savedInt(
        table: String,
        column: String,
    ): Int =
        context.openOrCreateDatabase(LocalDatabase.FILE_NAME, Context.MODE_PRIVATE, null).use { db ->
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
        val APP = AppId("io.example.app", "Main", 0)
        val PAIR = LearnedPair(NormalizedText("おかね"), APP)
    }
}
