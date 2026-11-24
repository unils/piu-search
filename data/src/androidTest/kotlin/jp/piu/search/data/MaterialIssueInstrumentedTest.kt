// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchQuery
import jp.piu.search.core.state.IndexSnapshot
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 本物の OS の値が、索引の読み手と同期の規則でどう扱われるかを、分類ごとのテスト用のアプリで確かめる（testing.md の「分類」）。
// Robolectric の偽の資源は、本物と同じ条件で Resources.NotFoundException を投げないため、ここで確かめる。
// 各アプリは :test-apps:installTestApps が入れる（split は base と split の組で入る）
@RunWith(AndroidJUnit4::class)
class MaterialIssueInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var writer: IndexActor? = null

    @After
    fun close() {
        runBlocking { writer?.job?.cancelAndJoin() }
        context.deleteDatabase(FILE)
    }

    @Test
    fun eachClassifiedAppFallsWhereDesigned() {
        val actor =
            IndexActor
                .open(
                    context,
                    FILE,
                    CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    1,
                    DeviceAppsReader(context),
                    GeneratorKeyFactory(analyzer),
                    Dispatchers.IO,
                ) { IndexDatabase.open(context, FILE) }
                .also { writer = it }
        runBlocking { actor.requestSync().await() }
        Log.i(TAG, "最初の同期: ${actor.status.value}")
        val snapshot = actor.snapshot.value
        val testApps = snapshot.packages.filterKeys { it.profileSerial == mySerial && it.packageName.startsWith(PREFIX) }
        Log.i(TAG, "テスト用のアプリの欠け: ${testApps.mapValues { it.value.attempt.issue }.filterValues { it != null }}")

        // 表示名が values-ja/ にだけある: 英語の名前の資源がないのは、ないことが決まっているので成功した空（欠けにしない）。
        // 日本語の名前（表示名か別の言語の名前）で引ける
        val jaOnly = testApps.getValue(PackageKey(mySerial, JA_ONLY))
        assertNull("values-ja だけの名前は欠けなし: ${jaOnly.attempt.issue}", jaOnly.attempt.issue)
        assertNotNull("検索に使う値がある", jaOnly.current)
        Log.i(TAG, "values-ja だけのアプリの表示名: ${labelsOf(snapshot, JA_ONLY)}")
        assertTrue("日本語の名前で引ける", JA_ONLY in found(snapshot, "日本語だけテスト"))

        // 表示名をマニフェストに直に書いた: 別の言語の名前は正常な不在で、欠けにならない
        assertNull(testApps.getValue(PackageKey(mySerial, INLINE)).attempt.issue)
        assertTrue("直書きの名前で引ける", INLINE in found(snapshot, "直書きテスト"))

        // split を持つ: 欠けにならず、APK の鍵に split の APK が入る（仕事用は APK の鍵で変化を見るので、split を替えれば鍵が変わる）
        val split = testApps.getValue(PackageKey(mySerial, SPLIT))
        assertNull(split.attempt.issue)
        val info =
            context
                .getSystemService(
                    LauncherApps::class.java,
                ).getActivityList(SPLIT, Process.myUserHandle())
                .single()
                .applicationInfo
        assertEquals("split の APK が 1 つ入っている", 1, info.splitSourceDirs?.size ?: 0)
        val apkKey = split.observed.apkKey
        assertNotNull("split のあるアプリにも APK の鍵がある", apkKey)
        assertTrue("APK の鍵に split の APK が入る: $apkKey", info.splitSourceDirs!!.single() in apkKey!!)

        // 同じ状態の 2 回目は何も読み直さず、テスト用のアプリの状態もそのまま
        runBlocking { actor.requestSync().await() }
        Log.i(TAG, "2 回目: ${actor.status.value}")
        assertEquals(
            0,
            actor.status.value.lastSync
                ?.materialsRead,
        )
        assertEquals(
            testApps,
            actor.snapshot.value.packages
                .filterKeys { it in testApps.keys },
        )
    }

    private fun found(
        snapshot: IndexSnapshot,
        raw: String,
    ): List<String> =
        SearchEngine(snapshot.toSearchIndex())
            .search(SearchQuery.of(raw).withReading(analyzer))
            .filter { it.app.profileSerial == mySerial }
            .map { it.app.packageName }

    private fun labelsOf(
        snapshot: IndexSnapshot,
        packageName: String,
    ): List<String?> {
        val index = snapshot.toSearchIndex()
        return context
            .getSystemService(LauncherApps::class.java)
            .getActivityList(packageName, Process.myUserHandle())
            .map { index.labelOf(AppId(packageName, it.componentName.className, mySerial)) }
    }

    private companion object {
        const val TAG = "MaterialIssueTest"
        const val FILE = "material-issue-instrumented.db"
        const val PREFIX = "jp.piu.search.testapp."
        const val JA_ONLY = "jp.piu.search.testapp.labeljaonly"
        const val INLINE = "jp.piu.search.testapp.labelinline"
        const val SPLIT = "jp.piu.search.testapp.split"

        val analyzer = KuromojiReadingAnalyzer()

        val mySerial: Long by lazy {
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(UserManager::class.java)
                .getSerialNumberForUser(Process.myUserHandle())
        }
    }
}
