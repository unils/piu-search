// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// AOSP の問い合わせの絞り込みは Robolectric にないため、後から入れたアプリでも本物の OS で確かめる。
@RunWith(AndroidJUnit4::class)
class GenreResolverInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = AppSource(context)

    @Test
    fun bothBrowsersKeepTheirGenreWhenTheDefaultChanges() {
        // get-role-holders は API 31 からある。古い端末では設定を変えず、両方の宣言が読めることを確かめる
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            assertBrowserGenres()
            return
        }
        val original = roleCommand("get-role-holders").split(';').filter { it.isNotBlank() }
        try {
            for (packageName in BROWSERS) {
                assertEquals("", roleCommand("add-role-holder", packageName))
                assertEquals(packageName, roleCommand("get-role-holders"))
                assertBrowserGenres()
            }
        } finally {
            assertEquals("", roleCommand("clear-role-holders"))
            original.forEach { assertEquals("", roleCommand("add-role-holder", it)) }
            assertEquals(original, roleCommand("get-role-holders").split(';').filter { it.isNotBlank() })
        }
    }

    @Test
    fun installedCameraGetsTheCameraGenre() {
        val entries = currentEntries(setOf(CAMERA))
        assertEquals(1, entries.size)
        assertEquals(0, entries.single().applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM)
        assertEquals(listOf(Genres.CAMERA), GenreResolver(context.packageManager).resolve(entries)[entries.single().id])
        val stamps = source.stamps(false).filter { it.id == entries.single().id }
        assertEquals(listOf(Genres.CAMERA), GenreResolver(context.packageManager).resolveStamps(stamps)[entries.single().id])
    }

    private fun assertBrowserGenres() {
        val entries = currentEntries(BROWSERS)
        assertEquals(BROWSERS, entries.map { it.id.packageName }.toSet())
        val resolver = GenreResolver(context.packageManager)
        val genres = resolver.resolve(entries)
        entries.forEach { assertTrue(genres[it.id].orEmpty().contains(Genres.BROWSER)) }
        val ids = entries.map { it.id }.toSet()
        val stampGenres = resolver.resolveStamps(source.stamps(false).filter { it.id in ids })
        assertEquals(genres, stampGenres)
    }

    private fun currentEntries(packages: Set<String>): List<LaunchableActivity> {
        val serial = source.profiles().single { it.isCurrent }.serial
        return source.activities(packages).filter { it.id.profileSerial == serial }
    }

    private fun roleCommand(
        operation: String,
        packageName: String? = null,
    ): String {
        // 呼び出し元のプロファイルだけを変え、仕事用や別の利用者の設定を触らない
        // getIdentifier は非公開なので、既存の仕事用の計器テストと同じく文字列から読む
        val userId =
            Regex("""\{(\d+)\}""")
                .find(
                    android.os.Process
                        .myUserHandle()
                        .toString(),
                )!!
                .groupValues[1]
                .toInt()
        val command = "cmd role $operation --user $userId android.app.role.BROWSER" + packageName?.let { " $it" }.orEmpty()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor
            .AutoCloseInputStream(
                automation.executeShellCommand(command),
            ).bufferedReader()
            .use { it.readText().trim() }
    }

    private companion object {
        val BROWSERS = setOf("jp.piu.search.testapp.browser", "jp.piu.search.testapp.browsersecondary")
        const val CAMERA = "jp.piu.search.testapp.camera"
    }
}
