// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

// Callback の知らせを索引の書き手とアイコンに渡すこと。溜める・前面でだけ同期するのは IndexActorTest（data）が確かめる
@RunWith(AndroidJUnit4::class)
class PackageWatcherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    @Test
    fun aChangeIsPassedToTheIndexAndTheIconsOnceEvenIfStartedTwice() {
        // アプリの部品が持つ受け取りとは別に作る
        context.appContainer.packages.close()
        val changed = ArrayList<Pair<Collection<String>, android.os.UserHandle>>()
        val invalidated = ArrayList<Set<String>>()
        val watcher = PackageWatcher(launcherApps, { names, user -> changed += names to user }, { invalidated += it })
        try {
            watcher.start()
            watcher.start()
            shadowOf(launcherApps).notifyPackageAdded("io.example.changed")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(setOf("io.example.changed") to Process.myUserHandle()), changed)
            assertEquals(listOf(setOf("io.example.changed")), invalidated)

            watcher.close()
            shadowOf(launcherApps).notifyPackageAdded("io.example.after")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, changed.size)
        } finally {
            watcher.close()
        }
    }
}
