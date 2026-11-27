// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.piu.search.core.reading.NoReadingAnalyzer
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.state.PackageKey
import jp.piu.search.data.index.DeviceAppsReader
import jp.piu.search.data.index.GeneratorKeyFactory
import jp.piu.search.data.index.IndexActor
import jp.piu.search.data.index.IndexDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// アーカイブ済みのアプリ（API 35 以上）が、入口の一覧の ApplicationInfo で isArchived になることを本物の端末で確かめる
// （architecture.md の「アーカイブ済みのアプリ」）。`pm archive` はインストーラのないアプリを拒む（「No installer found」）ので、
// 先にインストーラを shell にする。API 37 のエミュレータで 2026-11-15 に、`pm set-installer` の後の `pm archive` と、
// `pm install` での戻しが通ることを確かめた
@RunWith(AndroidJUnit4::class)
class ArchivedAppInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var archived = false

    @Before
    fun keepApk() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val path = shell("pm path $GAME").trim().removePrefix("package:")
        assertTrue("game が入っている: $path", path.endsWith(".apk"))
        shell("cp $path $ORIGINAL_APK")
    }

    @After
    fun restore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (archived) shell("pm install -r $ORIGINAL_APK")
        shell("rm -f $ORIGINAL_APK")
        assertTrue("戻した", shell("pm list packages $GAME").contains(GAME))
    }

    @Test
    fun archivedAppStaysInTheListWithIsArchived() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.w(TAG, "検査を飛ばした: API 35 より前にはアーカイブがない")
            return
        }
        assertFalse(entry().applicationInfo.isArchived)

        shell("pm set-installer $GAME com.android.shell")
        val result = shell("pm archive $GAME")
        archived = result.contains("Success")
        assertTrue("pm archive: $result", archived)

        val info = entry()
        Log.i(TAG, "アーカイブ済み: isArchived=${info.applicationInfo.isArchived} sourceDir=${info.applicationInfo.sourceDir}")
        assertTrue(info.applicationInfo.isArchived)
        // 読み手（AppSource）を通しても、一覧の段でアーカイブ済みと分かる。APK がないので APK の鍵はない（失敗ではない）
        val stamp =
            AppSource(context)
                .stampProfiles(withUpdateTime = true)
                .values
                .flatMap { it.items }
                .single { it.id.packageName == GAME }
        assertTrue(stamp.isArchived)
        assertEquals(null, stamp.apkFingerprint)
        // 索引の書き手を通すと: 補助の材料を読まずに空で成功するので欠けにならず、表示名で引ける
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer =
            IndexActor.open(
                context,
                FILE,
                scope,
                1,
                DeviceAppsReader(context),
                GeneratorKeyFactory({}, { SearchKeyGenerator(NoReadingAnalyzer, LatinWordReader(BrandTable.parse(emptyList()))) }),
                Dispatchers.IO,
            ) { IndexDatabase.open(context, FILE) }
        try {
            runBlocking { writer.requestSync().await() }
            Log.i(TAG, "アーカイブ済みを含む同期: ${writer.status.value}")
            val state = checkNotNull(writer.snapshot.value.packages[PackageKey(stamp.id.profileSerial, GAME)])
            assertTrue(state.observed.archived)
            assertNull(state.attempt.issue)
            assertTrue(
                writer.snapshot.value
                    .toSearchIndex()
                    .labelOf(stamp.id) != null,
            )
        } finally {
            runBlocking { writer.job.cancelAndJoin() }
            context.deleteDatabase(FILE)
        }
    }

    private fun entry() =
        context
            .getSystemService(LauncherApps::class.java)
            .getActivityList(GAME, Process.myUserHandle())
            .single()

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private companion object {
        const val TAG = "ArchivedAppTest"
        const val GAME = "jp.piu.search.testapp.game"
        const val ORIGINAL_APK = "/data/local/tmp/piu-archive-game.apk"
        const val FILE = "archived-app-instrumented.db"
    }
}
