// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.data.CountingAnalyzer
import jp.piu.search.data.FakeDevice
import jp.piu.search.data.IndexSync
import jp.piu.search.data.InstalledApp
import jp.piu.search.data.IssueCategory
import jp.piu.search.data.LocalDatabase
import jp.piu.search.data.LocalizedLabelReader
import jp.piu.search.data.MaterialCauses
import jp.piu.search.data.MaterialReadException
import jp.piu.search.data.MaterialStage
import jp.piu.search.data.ProfileReadStatus
import jp.piu.search.data.ProfileReconciler
import jp.piu.search.data.StoredIndex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.cert.CertificateException

// 比べる道具を、0.2.0 のテストと同じ偽の端末で確かめる。0.2.0 の同期と新しい同期を同じ端末の状態から動かし、
// 許す差の外の差がないこと、許す差を正しく分けること、許さない差を見逃さないことを見る
@RunWith(AndroidJUnit4::class)
class IndexComparisonTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val analyzer = CountingAnalyzer()
    private val databases = ArrayList<LocalDatabase>()

    @After
    fun close() {
        databases.forEach(LocalDatabase::close)
    }

    @Test
    fun bothSyncsAgreeExceptForTheAllowedDifferences() =
        runTest {
            val missingName = installed("io.example.name", "名前", localized = listOf("Name"))
            val noSigners = installed("io.example.nosign", "署名なし")
            val io = installed("io.example.io", "入出力", signers = listOf("CN=Io"))
            val cert = installed("io.example.cert", "証明書", localized = listOf("Cert"))
            val archived = installed("io.example.archived", "アーカイブ").copy(archived = true)
            device.install(
                missingName,
                noSigners,
                io,
                cert,
                archived,
                installed(
                    "io.example.maps",
                    "地図",
                    localized = listOf("Maps"),
                    signers = listOf("CN=Maps"),
                    genres = listOf(GenreId("map")),
                ),
                installed("io.example.work", "仕事", profile = WORK),
            )
            device.fail(missingName, MaterialStage.LABEL, Resources.NotFoundException())
            device.fail(noSigners, MaterialStage.SIGNER, MaterialReadException(MaterialCauses.NO_SIGNERS))
            device.fail(io, MaterialStage.LABEL, IOException())
            device.fail(cert, MaterialStage.SIGNER, CertificateException())

            val report = compare(oldSync(), newSync())
            assertEquals(report.render(), emptyList<IndexDiff>(), report.disallowed)
            assertEquals(7, report.pairs)
            // 0.2.0 が欠けとして書いた「ないことが決まっているもの」
            assertEquals(
                setOf("io.example.name", "io.example.nosign"),
                report.diffs
                    .filter { it.allowance == Allowance.SUCCESS_EMPTY }
                    .map { it.key.packageName }
                    .toSet(),
            )
            // 本当の失敗の組は、0.2.0 が読めた分を採り、新しい方は代わりのキーにする
            assertEquals(
                setOf("io.example.io", "io.example.cert"),
                report.diffs
                    .filter { it.allowance == Allowance.REAL_FAILURE }
                    .map { it.key.packageName }
                    .toSet(),
            )
        }

    @Test
    fun aDifferenceOutsideTheAllowedOnesIsReported() =
        runTest {
            device.install(installed("io.example.maps", "地図", genres = listOf(GenreId("map"))), installed(MAIL, "メール"))
            val (old, profiles) = oldSync()
            val new = newSync()
            assertEquals(emptyList<IndexDiff>(), compare(old to profiles, new).disallowed)

            val renamed =
                old.copy(
                    apps =
                        old.apps.map {
                            if (it.app.id.packageName ==
                                MAIL
                            ) {
                                it.copy(app = it.app.copy(label = "別名"))
                            } else {
                                it
                            }
                        },
                )
            val dropped = old.copy(apps = old.apps.filter { it.app.id.packageName != "io.example.maps" })
            assertEquals(listOf("label"), compare(renamed to profiles, new).disallowed.map { it.what })
            assertEquals(listOf("pair"), compare(dropped to profiles, new).disallowed.map { it.what })
        }

    @Test
    fun aProfileThatFailedOnBothSidesMakesTheComparisonInconclusive() =
        runTest {
            device.install(installed("io.example.maps", "地図"), installed("io.example.work", "仕事", profile = WORK))
            device.failedProfiles += WORK
            val report = compare(oldSync(), newSync())
            // 仕事用の組は両方から抜けるので差に出ない。差が 0 件でも、比較が成り立ったとは言わない
            assertEquals(emptyList<IndexDiff>(), report.diffs)
            assertEquals(listOf("new:$WORK", "old:$WORK"), report.failedProfiles)
            assertFalse(report.comparable)
            assertTrue(report.render().startsWith("NOT COMPARABLE"))
        }

    @Test
    fun onApi30TheLocalizedNameOfAWorkOnlyAppIsTheOnlyDifference() =
        runTest {
            device.install(installed("io.example.maps", "地図", profile = WORK, signers = listOf("CN=Maps,O=Example Maps")))
            // API 30 以下の経路: 自分のプロファイルにないパッケージの資源は開けない
            device.labelReader =
                LocalizedLabelReader(context) { activity, _ -> throw PackageManager.NameNotFoundException(activity.id.packageName) }
            val old = oldSync()
            // 0.2.0 は LABEL の一時的な欠けを書くが、読めた表示名と署名からキーを作る（部分の採用）
            assertEquals(listOf(MaterialStage.LABEL to IssueCategory.TRANSIENT), old.first.issues.map { it.stage to it.category })
            assertTrue(
                old.first.apps
                    .single()
                    .app.keys
                    .any { it.kind == SearchKeyKind.PUBLISHER },
            )
            val report = compare(old, newSync(Build.VERSION_CODES.R), Build.VERSION_CODES.R)
            assertEquals(report.render(), emptyList<IndexDiff>(), report.disallowed)
            assertEquals(listOf(Allowance.SUCCESS_EMPTY), report.diffs.map { it.allowance })
        }

    private fun compare(
        old: Pair<StoredIndex, Map<Long, ProfileReadStatus>>,
        new: IndexSnapshot,
        sdkInt: Int = Build.VERSION_CODES.S,
    ) = IndexComparison.compare(old.first, old.second, new, sdkInt, OWN)

    private fun oldSync(): Pair<StoredIndex, Map<Long, ProfileReadStatus>> {
        val database = LocalDatabase.open(context, null, allowMainThread = true).also(databases::add)
        val report =
            IndexSync(
                database.index,
                device,
                { generator() },
                analyzer,
                launcherApps = null,
                profiles = ProfileReconciler({ database.profiles }, device::existingProfiles),
                appVersionCode = 1,
            ).sync()
        return database.index.load() to report.profileResults
    }

    private suspend fun TestScope.newSync(sdkInt: Int = Build.VERSION_CODES.S): IndexSnapshot {
        val keys = GeneratorKeyFactory({}, ::generator)
        val actor =
            IndexActor(
                backgroundScope,
                IndexDatabase.open(context, null, allowMainThread = true),
                DeviceAppsReader(device, device::existingProfiles, sdkInt),
                keys,
                1,
                StandardTestDispatcher(testScheduler),
            )
        actor.requestSync().await()
        return actor.snapshot.value
    }

    private fun generator() = SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.parse(emptyList())))

    private fun installed(
        packageName: String,
        label: String,
        profile: Long = OWN,
        localized: List<String> = emptyList(),
        signers: List<String> = emptyList(),
        genres: List<GenreId> = emptyList(),
    ) = InstalledApp(
        AppEntry(
            AppId(packageName, "$packageName.Main", profile),
            label,
            localizedLabels = localized,
            genres = genres,
            signerNames = signers,
        ),
        packageUpdatedAt = 1_000,
    )

    private companion object {
        const val MAIL = "io.example.mail"
    }
}
