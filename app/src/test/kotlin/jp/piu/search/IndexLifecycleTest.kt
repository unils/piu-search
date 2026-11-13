// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.data.IndexSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class IndexLifecycleTest {
    @Test
    fun notificationsWhileSearchIsClosedWaitForTheNextOpening() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sync = context.appContainer.indexSync
        context.appContainer.indexLifecycle.close()
        val invalidated = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val launcher = org.robolectric.Shadows.shadowOf(context.getSystemService(android.content.pm.LauncherApps::class.java))

        fun notifyChange() {
            launcher.notifyPackageAdded("io.example.changed")
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
        }
        var refreshes = 0
        val lifecycle = IndexLifecycle(sync, { invalidated += it }) { refreshes++ }
        val finished = CountDownLatch(1)
        val listener: (IndexSyncStatus) -> Unit = { if (!it.running && it.lastResult != null) finished.countDown() }
        sync.addStatusListener(listener)
        try {
            lifecycle.start()
            lifecycle.start()
            notifyChange()
            assertNull(sync.status.lastResult)
            assertEquals(listOf(setOf("io.example.changed")), invalidated)
            lifecycle.onSearchStarted {}
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertEquals(1, refreshes)
            lifecycle.onSearchStopped()
            val previous = sync.status.lastResult
            notifyChange()
            assertSame(previous, sync.status.lastResult)
            assertEquals(2, invalidated.size)
        } finally {
            lifecycle.close()
            sync.removeStatusListener(listener)
        }
    }
}
