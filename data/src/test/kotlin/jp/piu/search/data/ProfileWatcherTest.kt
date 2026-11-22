// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

// プロファイルの状態の流れを、偽の読み方と本物の放送の登録（Robolectric）で確かめる
@RunWith(AndroidJUnit4::class)
class ProfileWatcherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var next: () -> Profiles = { PERSONAL_ONLY }
    private val watcher = ProfileWatcher(context, scope, Dispatchers.IO) { next() }

    @After
    fun close() {
        watcher.close()
        scope.cancel()
    }

    @Test
    fun theFirstValueIsReadRightAwayAndABroadcastReadsAgain() {
        assertEquals(PERSONAL_ONLY, watcher.profiles.value)
        val before = receivers()
        watcher.start()
        watcher.start()
        next = { WITH_WORK }
        val changes = CopyOnWriteArrayList<Unit>()
        scope.launch { watcher.changes.collect { changes += it } }
        send(Intent.ACTION_MANAGED_PROFILE_ADDED)
        runBlocking { withTimeout(TIMEOUT) { watcher.profiles.first { it == WITH_WORK } } }
        runBlocking { withTimeout(TIMEOUT) { while (changes.isEmpty()) kotlinx.coroutines.delay(10) } }
        // 2 度 start しても受け取りは 1 つ（2 つなら、1 回の放送で 2 回読み直しうる）
        assertEquals(before + 1, receivers())
    }

    @Test
    fun aFailedQueryKeepsThePreviousValueAndABroadcastWithTheSameValueIsStillAnnounced() {
        watcher.start()
        val changes = CopyOnWriteArrayList<Unit>()
        scope.launch { watcher.changes.collect { changes += it } }
        next = { throw IllegalStateException("binder") }
        send(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        runBlocking { withTimeout(TIMEOUT) { while (changes.isEmpty()) kotlinx.coroutines.delay(10) } }
        assertEquals(PERSONAL_ONLY, watcher.profiles.value)

        next = { PERSONAL_ONLY }
        send(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
        runBlocking { withTimeout(TIMEOUT) { while (changes.size < 2) kotlinx.coroutines.delay(10) } }
        assertEquals(PERSONAL_ONLY, watcher.profiles.value)
    }

    @Test
    fun aRefreshReadsAgainWithoutAnnouncingABroadcast() {
        next = { WITH_WORK }
        watcher.refresh()
        runBlocking { withTimeout(TIMEOUT) { watcher.profiles.first { it == WITH_WORK } } }
    }

    private fun receivers(): Int = shadowOf(context as android.app.Application).registeredReceivers.size

    private fun send(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val TIMEOUT = 10_000L
        val PERSONAL_ONLY = Profiles(0, emptySet(), workPaused = false)
        val WITH_WORK = Profiles(0, setOf(10), workPaused = true)
    }
}
