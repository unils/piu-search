// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// テスト用のアプリ（test-apps/）が、計器テストの前に端末へ入ったことを確かめる。
// 索引の計器テスト（M2-02 以降）が、入れ忘れで紛らわしく落ちないようにするための前提の検査。
// アプリのマニフェストの <queries>（MAIN と LAUNCHER）で見える範囲だけを見る
@RunWith(AndroidJUnit4::class)
class TestAppsInstalledInstrumentedTest {
    @Test
    fun launcherActivitiesOfTestAppsAreVisible() {
        val prefix = "jp.piu.search.testapp."
        val launchers =
            queryLaunchers()
                .map { it.activityInfo }
                .filter { it.packageName.startsWith(prefix) }
                .groupBy({ it.packageName.removePrefix(prefix) }, { it.name.substringAfterLast('.') })
                .mapValues { (_, names) -> names.sorted() }

        // mail の SecondTestActivity は mailto: だけを受け、LAUNCHER ではない。nolauncher は LAUNCHER を持たない
        val expected =
            mapOf(
                "game" to listOf("TestActivity"),
                "mail" to listOf("TestActivity"),
                "maps" to listOf("TestActivity"),
                "transit" to listOf("TestActivity"),
                "twolaunchers" to listOf("SecondTestActivity", "TestActivity"),
                "yahoo" to listOf("TestActivity"),
            )
        assertEquals(expected, launchers.toSortedMap())
    }

    private fun queryLaunchers(): List<ResolveInfo> {
        val packageManager = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    }
}
