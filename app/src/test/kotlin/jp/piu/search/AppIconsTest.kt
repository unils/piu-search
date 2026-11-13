// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class AppIconsTest {
    @Test
    fun packageUpdateInvalidatesOnlyItsIcons() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val executor = Executors.newSingleThreadExecutor()
            var reads = 0
            val icons =
                LauncherAppIcons(context, ProfileUsers(context), executor) {
                    reads++
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            try {
                val first = icons.load(APP)
                val other = icons.load(OTHER)
                assertSame(first, icons.load(APP))
                icons.invalidate(setOf(APP.packageName))
                assertNotSame(first, icons.load(APP))
                assertSame(other, icons.cached(OTHER))
                assertEquals(3, reads)
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun invalidationDuringReadCannotPutTheOldIconBack() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val executor = Executors.newSingleThreadExecutor()
            var reads = 0
            lateinit var icons: LauncherAppIcons
            icons =
                LauncherAppIcons(context, ProfileUsers(context), executor) {
                    reads++
                    if (reads == 1) icons.invalidate(setOf(APP.packageName))
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            try {
                val fresh = icons.load(APP)
                assertEquals(2, reads)
                assertSame(fresh, icons.cached(APP))
            } finally {
                executor.shutdownNow()
            }
        }

    private companion object {
        val APP = AppId("io.example.changed", "Main", 0)
        val OTHER = AppId("io.example.other", "Main", 0)
    }
}
