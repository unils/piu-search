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
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 本物の OS の値が、どの段のどの区分に落ちるかを、分類ごとのテスト用のアプリで確かめる（testing.md の「分類」）。
// Robolectric の偽の資源は、本物と同じ条件で Resources.NotFoundException を投げないため、ここで確かめる。
// 各アプリは :test-apps:installTestApps が入れる（split は base と split の組で入る）
@RunWith(AndroidJUnit4::class)
class MaterialIssueInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: LocalDatabase? = null

    @After
    fun close() {
        database?.close()
    }

    @Test
    fun eachClassifiedAppFallsWhereDesigned() {
        val db = LocalDatabase.open(context, null).also { database = it }
        val sync =
            IndexSync(
                db.index,
                AndroidDeviceApps(context),
                { SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.loadDefault())) },
                analyzer,
                null,
            )
        val first = sync.sync()
        Log.i(TAG, "最初の同期: $first")
        val issues = sync.materialIssues.filter { it.key.profileSerial == mySerial && it.key.packageName.startsWith(PREFIX) }
        Log.i(TAG, "テスト用のアプリの欠け: $issues")

        // 表示名が values-ja/ にだけある: 英語の名前が読めず、決定的な欠け（LABEL）。日本語の名前と表示名では引ける
        val jaOnly = issues.filter { it.key.packageName == JA_ONLY }
        assertEquals("values-ja だけの名前は LABEL の欠けが 1 行: $jaOnly", listOf(MaterialStage.LABEL), jaOnly.map { it.stage })
        assertEquals(NOT_FOUND, jaOnly.single().cause)
        assertEquals(IssueCategory.DETERMINISTIC, jaOnly.single().category)
        assertEquals("決定的なので読み直しを待たない", false, jaOnly.single().retryOnce)
        Log.i(TAG, "values-ja だけのアプリの表示名: ${labelsOf(sync, JA_ONLY)}")
        assertTrue("日本語の名前で引ける", JA_ONLY in found(sync, "日本語だけテスト"))

        // 表示名をマニフェストに直に書いた: 別の言語の名前は正常な不在で、欠けにならない
        assertTrue("直書きの名前は欠けなし: $issues", issues.none { it.key.packageName == INLINE })
        assertTrue("直書きの名前で引ける", INLINE in found(sync, "直書きテスト"))

        // split を持つ: 欠けにならず、指紋に split の APK が入る（仕事用は指紋で変化を見るので、split を替えれば指紋が変わる）
        assertTrue("split のあるアプリは欠けなし: $issues", issues.none { it.key.packageName == SPLIT })
        val info =
            context
                .getSystemService(
                    LauncherApps::class.java,
                ).getActivityList(SPLIT, Process.myUserHandle())
                .single()
                .applicationInfo
        assertEquals("split の APK が 1 つ入っている", 1, info.splitSourceDirs?.size ?: 0)
        val stamp =
            AppSource(context)
                .stampProfiles(withUpdateTime = true)
                .getValue(mySerial)
                .items
                .single { it.id.packageName == SPLIT }
        val fingerprint = stamp.apkFingerprint
        assertNotNull("split のあるアプリにも指紋がある", fingerprint)
        assertTrue("指紋に split の APK が入る: $fingerprint", info.splitSourceDirs!!.single() in fingerprint!!)

        // 同じ状態の 2 回目は何も読み直さず、欠けの行もそのまま
        val second = sync.sync()
        Log.i(TAG, "2 回目: $second")
        assertEquals(0, second.readPackages)
        assertEquals(jaOnly, sync.materialIssues.filter { it.key.profileSerial == mySerial && it.key.packageName == JA_ONLY })
    }

    private fun found(
        sync: IndexSync,
        raw: String,
    ): List<String> =
        SearchEngine(sync.index)
            .search(SearchQuery.of(raw).withReading(analyzer))
            .filter { it.app.profileSerial == mySerial }
            .map { it.app.packageName }

    private fun labelsOf(
        sync: IndexSync,
        packageName: String,
    ): List<String?> =
        context
            .getSystemService(LauncherApps::class.java)
            .getActivityList(packageName, Process.myUserHandle())
            .map {
                sync.index.labelOf(
                    jp.piu.search.core.search
                        .AppId(packageName, it.componentName.className, mySerial),
                )
            }

    private companion object {
        const val TAG = "MaterialIssueTest"
        const val PREFIX = "jp.piu.search.testapp."
        const val JA_ONLY = "jp.piu.search.testapp.labeljaonly"
        const val INLINE = "jp.piu.search.testapp.labelinline"
        const val SPLIT = "jp.piu.search.testapp.split"
        const val NOT_FOUND = "android.content.res.Resources\$NotFoundException"

        val analyzer = KuromojiReadingAnalyzer()

        val mySerial: Long by lazy {
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(UserManager::class.java)
                .getSerialNumberForUser(Process.myUserHandle())
        }
    }
}
