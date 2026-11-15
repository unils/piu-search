// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchEngine
import jp.piu.search.core.search.SearchIndexVersion
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.util.Base64

// 本物の読み手（LocalizedLabelReader・SignerReader）の結果が、同期・保存・再試行まで正しくつながること（testing.md の「分類」）。
// 偽装するのは読み手の入力（言語ごとの資源と、PackageManager の PackageInfo・証明書）だけにする。
// FakeDevice の既定の経路は読み手の結果を直に返すので、署名が空でも正常に終わり、言語の一部の欠けも偽の端末が決めてしまう
@RunWith(AndroidJUnit4::class)
class MaterialReaderSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<LocalDatabase>()
    private val analyzer = CountingAnalyzer()

    /** パッケージと言語（`ja`・`en` など）ごとの表示名の資源。ない組は `Resources.NotFoundException` になる。 */
    private val labelTexts = HashMap<Pair<String, String>, String>()

    private val device =
        FakeDevice().apply {
            labelReader = LocalizedLabelReader(context) { activity, configuration -> resourcesOf(activity, configuration) }
            signerReader = SignerReader(context.packageManager)
        }

    @After
    fun closeDatabases() {
        opened.forEach(LocalDatabase::close)
        context.deleteDatabase(FILE)
    }

    private fun newSync(appVersionCode: Int = 1): IndexSync {
        val database = LocalDatabase.open(context, FILE, SearchIndexVersion.CURRENT, allowMainThread = true).also(opened::add)
        val generator = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList())))
        return IndexSync(
            database.index,
            device,
            { generator },
            analyzer,
            launcherApps = null,
            profiles = ProfileReconciler({ database.profiles }, { device.existingProfiles() }),
            profileSerialOf = { PERSONAL },
            appVersionCode = appVersionCode,
        )
    }

    private fun issues(): Map<Pair<String, MaterialStage>, MaterialIssue> =
        opened
            .last()
            .index
            .load()
            .issues
            .associateBy { it.key.packageName to it.stage }

    private fun IndexSync.found(query: String): List<String> = SearchEngine(index).search(SearchQuery.of(query)).map { it.label }

    @Test
    fun realReaderResultsAreSavedAndRetriedByTheirCategories() {
        // 表示名が日本語の資源にだけある（英語と端末の言語の資源がない）。署名は読める
        labelTexts[MAPS to "ja"] = "地図テスト"
        installPackage(MAPS, signed = true)
        // 署名が空（NO_SIGNERS）。別の言語の名前は読める
        labelTexts[NOTES to "ja"] = "メモ帳テスト"
        labelTexts[NOTES to "en"] = "Test Notes"
        installPackage(NOTES, signed = false)
        // PackageManager から引けない（一覧の後に消えた。NameNotFoundException で一時的）
        labelTexts[GAME to "ja"] = "ゲームテスト"
        labelTexts[GAME to "en"] = "Test Game"
        // 仕事用は APK のファイルから読む。パスがなければ ARCHIVE_UNREADABLE
        labelTexts[MAIL to "ja"] = "メールテスト"
        labelTexts[MAIL to "en"] = "Test Mail"
        val apps = listOf(app(MAPS, "テスト地図"), app(NOTES, "テストメモ"), app(GAME, "テストゲーム"), app(MAIL, "テストメール", WORK))
        device.install(*apps.toTypedArray())

        val sync = newSync()
        sync.sync()
        val first = issues()
        assertEquals(
            mapOf(
                (MAPS to MaterialStage.LABEL) to "android.content.res.Resources\$NotFoundException",
                (NOTES to MaterialStage.SIGNER) to MaterialCauses.NO_SIGNERS,
                (GAME to MaterialStage.SIGNER) to "android.content.pm.PackageManager\$NameNotFoundException",
                (MAIL to MaterialStage.SIGNER) to MaterialCauses.ARCHIVE_UNREADABLE,
            ),
            first.mapValues { it.value.cause },
        )
        assertEquals(listOf(GAME to MaterialStage.SIGNER), first.filterValues { it.retryOnce }.keys.toList())
        assertEquals(IssueCategory.DETERMINISTIC, first.getValue(MAPS to MaterialStage.LABEL).category)
        assertEquals(IssueCategory.DETERMINISTIC, first.getValue(NOTES to MaterialStage.SIGNER).category)
        // 読めた言語の名前・読めた署名は索引に入る。欠けた材料のほかは引ける
        assertEquals(listOf("テスト地図"), sync.found("地図テスト"))
        assertEquals(listOf("テストメモ"), sync.found("Test Notes"))
        assertEquals(listOf("テスト地図"), sync.found("signer"))
        assertEquals(emptyList<String>(), sync.found("Test Map"))

        // 一時的な欠けだけを 1 回読み直し、また欠ければ以後は読まない
        device.reads.clear()
        sync.sync()
        assertEquals(listOf(PackageKey(PERSONAL, GAME)), device.reads)
        assertFalse(issues().getValue(GAME to MaterialStage.SIGNER).retryOnce)
        device.reads.clear()
        sync.sync()
        assertEquals(emptyList<PackageKey>(), device.reads)

        // 開き直しても同じ行を読み戻し、版が変わると欠けのある組をすべて 1 回読み直す
        closeAndReopen()
        val upgraded = newSync(appVersionCode = 2)
        device.reads.clear()
        upgraded.sync()
        assertEquals(apps.map { it.key }.toSet(), device.reads.toSet())
        assertEquals(first.keys, issues().keys)
        assertEquals(first.mapValues { it.value.firstFailedOn }, issues().mapValues { it.value.firstFailedOn })
        // 版の変更は通常の条件なので、一時的な欠けにはまた 1 回の再試行が付く
        device.reads.clear()
        upgraded.sync()
        assertEquals(listOf(PackageKey(PERSONAL, GAME)), device.reads)
        device.reads.clear()
        upgraded.sync()
        assertEquals(emptyList<PackageKey>(), device.reads)

        // APK が替わって英語の資源と署名が入ると、行が消える
        labelTexts[MAPS to "en"] = "Test Map"
        installPackage(NOTES, signed = true)
        device.install(app(MAPS, "テスト地図", time = 2_000), app(NOTES, "テストメモ", time = 2_000))
        upgraded.sync()
        assertEquals(setOf(GAME to MaterialStage.SIGNER, MAIL to MaterialStage.SIGNER), issues().keys)
        assertEquals(listOf("テスト地図"), upgraded.found("Test Map"))
        assertTrue(upgraded.found("signer").containsAll(listOf("テスト地図", "テストメモ")))
    }

    private fun closeAndReopen() {
        opened.forEach(LocalDatabase::close)
        opened.clear()
    }

    private fun resourcesOf(
        activity: LaunchableActivity,
        configuration: Configuration,
    ): Resources {
        assertEquals(LABEL_RES, activity.applicationInfo.labelRes)
        return LabelResources(context.resources, labelTexts[activity.id.packageName to configuration.locales[0].language])
    }

    private fun installPackage(
        packageName: String,
        signed: Boolean,
    ) {
        val info =
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo =
                    android.content.pm
                        .ApplicationInfo()
                        .apply { this.packageName = packageName }
                // Robolectric の SigningInfo は署名を自分では持たないので、影（shadow）に入れる
                if (signed) {
                    signingInfo =
                        SigningInfo().also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(Signature(CERTIFICATE))) }
                }
            }
        shadowOf(context.packageManager).installPackage(info)
    }

    /** 表示名の資源 [text] だけを持つ資源。null なら、その言語に資源がない（本物の OS と同じく `Resources.NotFoundException`）。 */
    @Suppress("DEPRECATION") // 偽の資源を作るため。資源の表は元の資源のものを使い、表示名の引き方だけを替える
    private class LabelResources(
        base: Resources,
        private val text: String?,
    ) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getText(id: Int): CharSequence = text ?: throw NotFoundException("String resource ID #0x${Integer.toHexString(id)}")
    }

    private companion object {
        const val FILE = "material-reader-sync-test.db"
        const val MAPS = "io.example.maps"
        const val NOTES = "io.example.notes"
        const val GAME = "io.example.game"
        const val MAIL = "io.example.mail"

        // O=Piu Test Signer の自己署名の証明書（DER）。DN を読むためだけに使う
        val CERTIFICATE: ByteArray =
            Base64.getDecoder().decode(
                "MIIBizCCATGgAwIBAgIUAx1pGZHCRVt3xtnW2MzMwyCp6ZwwCgYIKoZIzj0EAwIwGjEYMBYGA1UECgwPUGl1IFRlc3QgU2lnbmVyMCAXDTI2MTEx" +
                    "NTIzMjgzNFoYDzIxMjYxMDIyMjMyODM0WjAaMRgwFgYDVQQKDA9QaXUgVGVzdCBTaWduZXIwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATVPzsX" +
                    "5NuZdYc5taNL5wtZh1lJ4BL+I5btxyu0BOE3jGyvsdweUv+KfzdacVCCF28qUhGl+dZfJu4EihBVRCFRo1MwUTAdBgNVHQ4EFgQUSEo3BoZoP8x6" +
                    "xtkmMrTD18QvPfswHwYDVR0jBBgwFoAUSEo3BoZoP8x6xtkmMrTD18QvPfswDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiEAjwyN" +
                    "nDhxnRp7A2bVdm2cT1+LtvublTe2VLvqZ4cJs9QCIBeMTIaGEtQeNA8J8/YK6ii0qEZ+UzYivKorjnDGMluH",
            )

        fun app(
            packageName: String,
            label: String,
            profile: Long = PERSONAL,
            time: Long = 1_000,
        ) = InstalledApp(AppEntry(AppId(packageName, "$packageName.Main", profile), label), packageUpdatedAt = time)
    }
}
