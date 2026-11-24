// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

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
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchQuery
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.Stage
import jp.piu.search.data.CountingAnalyzer
import jp.piu.search.data.FakeDevice
import jp.piu.search.data.InstalledApp
import jp.piu.search.data.LABEL_RES
import jp.piu.search.data.LaunchableActivity
import jp.piu.search.data.LocalizedLabelReader
import jp.piu.search.data.MaterialCauses
import jp.piu.search.data.SignerReader
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.util.Base64
import jp.piu.search.core.state.PackageKey as StateKey
import jp.piu.search.data.PackageKey as DeviceKey

// 本物の読み手（LocalizedLabelReader・SignerReader）の結果が、索引の読み手・同期の規則・保存・再試行まで正しくつながること（testing.md の「分類」）。
// 偽装するのは読み手の入力（言語ごとの資源と、PackageManager の PackageInfo・証明書）だけにする。
// FakeDevice の既定の経路は読み手の結果を直に返すので、署名が空でも正常に終わり、言語の一部の欠けも偽の端末が決めてしまう
@RunWith(AndroidJUnit4::class)
class RealReadersSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<IndexDatabase>()

    /** パッケージと言語（`ja`・`en` など）ごとの表示名の資源。ない組は `Resources.NotFoundException` になる。 */
    private val labelTexts = HashMap<Pair<String, String>, String>()

    private val device =
        FakeDevice().apply {
            labelReader = LocalizedLabelReader(context) { activity, configuration -> resourcesOf(activity, configuration) }
            signerReader = SignerReader(context.packageManager)
        }

    @After
    fun close() {
        opened.forEach(IndexDatabase::close)
        context.deleteDatabase(FILE)
    }

    @Test
    fun realReaderResultsAreSavedAndRetriedByTheirCategories() =
        runTest {
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

            val actor = actor()
            actor.requestSync().await()
            // その言語に名前の資源がない・署名がないは、ないことが決まっているので成功した空。欠けにしない
            val first = issues(actor)
            assertEquals(
                mapOf(
                    StateKey(OWN, GAME) to (Stage.SIGNER to "android.content.pm.PackageManager\$NameNotFoundException"),
                    StateKey(WORK, MAIL) to (Stage.SIGNER to MaterialCauses.ARCHIVE_UNREADABLE),
                ),
                first.mapValues { (_, issue) -> issue.stage to issue.failure.cause },
            )
            assertEquals(IssueCategory.TRANSIENT, first.getValue(StateKey(OWN, GAME)).failure.category)
            assertEquals(IssueCategory.DETERMINISTIC, first.getValue(StateKey(WORK, MAIL)).failure.category)
            assertEquals(setOf(StateKey(OWN, GAME)), retrying(actor))
            // 読めた言語の名前・読めた署名は索引に入る。失敗した組は一覧の表示名だけで引ける
            assertEquals(listOf("テスト地図"), actor.found("地図テスト"))
            assertEquals(listOf("テストメモ"), actor.found("Test Notes"))
            assertEquals(listOf("テスト地図"), actor.found("signer"))
            assertEquals(emptyList<String>(), actor.found("Test Map"))
            assertEquals(emptyList<String>(), actor.found("Test Game"))
            assertEquals(listOf("テストゲーム"), actor.found("テストゲーム"))

            // 一時的な欠けだけを 1 回読み直し、また欠ければ以後は読まない
            assertEquals(listOf(DeviceKey(OWN, GAME)), readsOfNextSync(actor))
            assertEquals(emptySet<StateKey>(), retrying(actor))
            assertEquals(emptyList<DeviceKey>(), readsOfNextSync(actor))

            // 開き直しても同じ欠けを読み戻し、版が変わると欠けのある組をすべて 1 回読み直す
            actor.job.cancelAndJoin()
            opened.forEach(IndexDatabase::close)
            opened.clear()
            val upgraded = actor(appVersion = 2)
            assertEquals(setOf(DeviceKey(OWN, GAME), DeviceKey(WORK, MAIL)), readsOfNextSync(upgraded).toSet())
            assertEquals(first.keys, issues(upgraded).keys)
            assertEquals(first.mapValues { it.value.firstFailedOn }, issues(upgraded).mapValues { it.value.firstFailedOn })
            // 版の変更は通常のきっかけなので、一時的な欠けにはまた 1 回の再試行が付く
            assertEquals(listOf(DeviceKey(OWN, GAME)), readsOfNextSync(upgraded))
            assertEquals(emptyList<DeviceKey>(), readsOfNextSync(upgraded))

            // APK が替わって英語の資源と署名が入ると、引けるようになる
            labelTexts[MAPS to "en"] = "Test Map"
            installPackage(NOTES, signed = true)
            device.install(app(MAPS, "テスト地図", time = 2_000), app(NOTES, "テストメモ", time = 2_000))
            upgraded.requestSync().await()
            assertEquals(setOf(StateKey(OWN, GAME), StateKey(WORK, MAIL)), issues(upgraded).keys)
            assertEquals(listOf("テスト地図"), upgraded.found("Test Map"))
            assertTrue(upgraded.found("signer").containsAll(listOf("テスト地図", "テストメモ")))
        }

    private fun TestScope.actor(appVersion: Int = 1): IndexActor =
        IndexActor(
            backgroundScope,
            IndexDatabase.open(context, FILE, allowMainThread = true).also(opened::add),
            DeviceAppsReader(device, { setOf(OWN, WORK) }),
            GeneratorKeyFactory({}, { SearchKeyGenerator(CountingAnalyzer(), LatinWordReader(BrandTable.parse(emptyList()))) }),
            appVersion,
            StandardTestDispatcher(testScheduler),
        )

    private fun issues(actor: IndexActor) =
        actor.snapshot.value.packages.values
            .mapNotNull { state -> state.attempt.issue?.let { state.key to it } }
            .toMap()

    private fun retrying(actor: IndexActor): Set<StateKey> =
        actor.snapshot.value.packages.values
            .filter { it.attempt.retry }
            .mapTo(HashSet()) { it.key }

    private suspend fun readsOfNextSync(actor: IndexActor): List<DeviceKey> {
        device.reads.clear()
        actor.requestSync().await()
        return device.reads.toList()
    }

    private fun IndexActor.found(query: String): List<String> =
        SearchEngine(snapshot.value.toSearchIndex()).search(SearchQuery.of(query)).map { it.label }

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
        const val FILE = "real-readers-sync-test.db"
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
            profile: Long = OWN,
            time: Long = 1_000,
        ) = InstalledApp(AppEntry(AppId(packageName, "$packageName.Main", profile), label), packageUpdatedAt = time)
    }
}
