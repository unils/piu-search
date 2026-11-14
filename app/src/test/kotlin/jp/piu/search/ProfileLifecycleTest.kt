// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.DatabaseWriter
import jp.piu.search.data.ProfileReconciler
import jp.piu.search.data.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

// 画面を作らない起動と、画面を閉じている間の放送でも整理し、索引の作り直しは前面まで待つ。
@RunWith(AndroidJUnit4::class)
class ProfileLifecycleTest {
    @Test
    fun nextProcessStartupDeletesLearningWithoutOpeningSearch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(UserManager::class.java)
        shadowOf(manager).setSerialNumberForUser(Process.myUserHandle(), 0)
        val old = context.appContainer
        old.writer.run {}
        val pair = LearnedPair(NormalizedText("さがす"), AppId("io.example.app", "Main", 11))
        // 前のプロセスで保存された行を作り、次の部品にはメモリの写しを渡さない
        old.writer.run {
            old.database.learned.save(
                LearnedMemory.EMPTY,
                LearnedMemory.EMPTY.recorded(LearningDecision(pair, emptyList()), 1),
            )
            assertEquals(setOf(11L), old.database.profiles.serials())
        }
        old.profileLifecycle.close()
        old.database.close()
        old.writer.close()
        val restarted = AppContainer(context)
        try {
            restarted.profileLifecycle.start()
            restarted.writer.run {
                assertEquals(emptySet<Long>(), restarted.database.profiles.serials())
                assertEquals(0, restarted.indexSync.index.size)
            }
        } finally {
            restarted.profileLifecycle.close()
            restarted.writer.run { restarted.database.close() }
            restarted.writer.close()
        }
    }

    @Test
    fun startupAndBackgroundBroadcastsReconcileWithoutBuildingTheIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rows = mutableSetOf(0L, 11L, 12L)
        var existing = setOf(0L, 12L)
        val storage =
            object : ProfileStorage {
                override fun serials(): Set<Long> = rows.toSet()

                override fun delete(serials: Set<Long>) {
                    rows.removeAll(serials)
                }
            }
        DatabaseWriter().use { writer ->
            var syncs = 0
            val lifecycle = ProfileLifecycle(context, writer, ProfileReconciler({ storage }) { existing }) { syncs++ }
            try {
                lifecycle.start()
                writer.run {}
                assertEquals(setOf(0L, 12L), rows)
                assertEquals(0, syncs)
                existing = setOf(0)
                context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_REMOVED))
                shadowOf(Looper.getMainLooper()).idle()
                writer.run {}
                assertEquals(setOf(0L), rows)
                assertEquals(0, syncs)
                lifecycle.onSearchStarted()
                assertEquals(1, syncs)
                context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE))
                shadowOf(Looper.getMainLooper()).idle()
                writer.run {}
                assertEquals(2, syncs)
            } finally {
                lifecycle.close()
            }
        }
    }
}
