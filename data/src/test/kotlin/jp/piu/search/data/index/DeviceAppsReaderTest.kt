// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.reading.ReadingToken
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.state.Environment
import jp.piu.search.core.state.GenreResult
import jp.piu.search.core.state.IndexRules
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.MaterialRead
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.PackageObservation
import jp.piu.search.core.state.ProfileReads
import jp.piu.search.core.state.Stage
import jp.piu.search.data.FakeDevice
import jp.piu.search.data.InstalledApp
import jp.piu.search.data.LocalizedLabelReader
import jp.piu.search.data.MaterialCauses
import jp.piu.search.data.MaterialReadException
import jp.piu.search.data.MaterialStage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.cert.CertificateException

// 0.2.0 の読み手の結果を、同期の規則の入力の形に変えることを、0.2.0 のテストと同じ偽の端末（本物の readPackage と分類を通す）で確かめる
@RunWith(AndroidJUnit4::class)
class DeviceAppsReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private var existing: Set<Long>? = setOf(OWN, WORK)
    private val reader = DeviceAppsReader(device, { existing })

    @Test
    fun theListingIsGroupedByPackageAndRemovedProfilesComeFromTheExistenceQuery() {
        device.install(
            installed("io.example.maps", "地図"),
            InstalledApp(AppEntry(AppId("io.example.maps", "Nav", OWN), "ナビ"), packageUpdatedAt = 1_000),
            installed("io.example.old", "古い").copy(archived = true),
        )
        device.unavailable += WORK
        val observation = reader.observe(setOf(OWN, WORK, 99))
        val own = checkNotNull(observation.profiles[OWN])
        assertEquals(ListingStatus.COMPLETE, own.status)
        assertEquals(PackageObservation(setOf("io.example.maps.Main", "Nav"), false, "apk:1000", 1_000), own.packages["io.example.maps"])
        assertEquals(true, own.packages["io.example.old"]?.archived)
        assertEquals(ListingStatus.UNAVAILABLE, observation.profiles[WORK]?.status)
        assertEquals(setOf(99L), observation.removedProfiles)

        // 存在を確かめられない回は、どのプロファイルも消さない
        existing = null
        assertTrue(reader.observe(setOf(OWN, 99)).removedProfiles.isEmpty())
    }

    @Test
    fun whatIsKnownToBeAbsentIsAnEmptySuccessAndOtherFailuresFailThePackage() {
        val missingName = installed("io.example.name", "名前")
        val noSigners = installed("io.example.nosign", "署名なし")
        val io = installed("io.example.io", "入出力")
        val cert = installed("io.example.cert", "証明書")
        val entry = installed("io.example.entry", "入口")
        val archived = installed("io.example.archived", "アーカイブ").copy(archived = true)
        val fine = installed("io.example.fine", "普通", localized = listOf("Fine"), signers = listOf("CN=Fine"))
        device.install(missingName, noSigners, io, cert, entry, archived, fine)
        device.fail(missingName, MaterialStage.LABEL, Resources.NotFoundException())
        device.fail(noSigners, MaterialStage.SIGNER, MaterialReadException(MaterialCauses.NO_SIGNERS))
        device.fail(io, MaterialStage.LABEL, IOException())
        device.fail(cert, MaterialStage.SIGNER, CertificateException())
        device.fail(entry, MaterialStage.ENTRY, SecurityException())
        device.fail(archived, MaterialStage.SIGNER, CertificateException())
        val reads = read()

        assertTrue(reads.getValue("io.example.name") is MaterialRead.Read)
        assertEquals(emptyList<String>(), (reads.getValue("io.example.nosign") as MaterialRead.Read).signerDns)
        val failedIo = reads.getValue("io.example.io") as MaterialRead.Failed
        assertEquals(Stage.LABEL to IssueCategory.TRANSIENT, failedIo.stage to failedIo.failure.category)
        assertEquals(mapOf("io.example.io.Main" to "入出力"), failedIo.labels)
        val failedCert = reads.getValue("io.example.cert") as MaterialRead.Failed
        assertEquals(Stage.SIGNER to IssueCategory.DETERMINISTIC, failedCert.stage to failedCert.failure.category)
        val failedEntry = reads.getValue("io.example.entry") as MaterialRead.Failed
        assertEquals("java.lang.SecurityException", failedEntry.failure.cause)
        assertEquals(Stage.ENTRY, failedEntry.stage)
        // アーカイブ済みは補助の材料を読まないので、署名の失敗も起きない
        assertEquals(MaterialRead.Read::class, reads.getValue("io.example.archived")::class)
        val read = reads.getValue("io.example.fine") as MaterialRead.Read
        assertEquals(mapOf("io.example.fine.Main" to listOf("Fine")), read.localized)
        assertEquals(listOf("CN=Fine"), read.signerDns)
        assertEquals(read.before, read.after)
        assertEquals("apk:1000", read.before.apkKey)
    }

    // 一覧と詳細の食い違い（入口が詳細から消えた・一覧の表示名が取れない）は ENTRY の一時的な失敗。読む前後の鍵は、そのまま規則に渡す
    // （前後の鍵の比較による CHANGED_DURING_READ は core:state の IndexRulesTest が確かめる）
    @Test
    fun entriesMissingFromTheDetailsAndListingLabelFailuresFailTheEntryStageAndTheKeysAreObservedAroundTheRead() {
        val vanished = installed("io.example.gone", "消える")
        val label = installed("io.example.label", "名前なし")
        val moving = installed("io.example.moving", "動く")
        device.install(vanished, label, moving)
        device.vanished += vanished.entry.id
        device.listingLabelFailures[label.entry.id] = IllegalStateException()
        device.keysDuringRead = { key, phase ->
            if (key.packageName == "io.example.moving" && phase == 1) {
                jp.piu.search.data
                    .ApkKeys(2_000, "apk:2000")
            } else {
                null
            }
        }
        val reads = read()

        val gone = reads.getValue("io.example.gone") as MaterialRead.Failed
        assertEquals(Stage.ENTRY to MaterialCauses.VANISHED, gone.stage to gone.failure.cause)
        assertEquals(IssueCategory.TRANSIENT, gone.failure.category)
        val unnamed = reads.getValue("io.example.label") as MaterialRead.Failed
        assertEquals(Stage.ENTRY to "java.lang.IllegalStateException", unnamed.stage to unnamed.failure.cause)
        assertEquals(IssueCategory.SUSPECTED_BUG, unnamed.failure.category)
        assertNotNull(unnamed.failure.frames)
        val moved = reads.getValue("io.example.moving") as MaterialRead.Read
        assertEquals("apk:1000", moved.before.apkKey)
        assertEquals("apk:2000", moved.after.apkKey)
        assertEquals(2_000L, moved.after.updatedAt)
    }

    // 一覧の問い合わせの失敗と、詳細を読んだ後の確かめ直しの失敗は、プロファイルの単位の失敗（組の結果なし）
    @Test
    fun aFailedListingOrRecheckIsAFailureOfTheWholeProfile() {
        device.install(installed("io.example.maps", "地図"), installed("io.example.mail", "メール", profile = WORK))
        device.failedProfiles += WORK
        assertEquals(ListingStatus.FAILED, reader.observe(emptySet()).profiles[WORK]?.status)

        device.failedProfiles.clear()
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, reader.observe(emptySet()), ENV)
        device.failedDetailProfiles += WORK
        val result = reader.readMaterials(plan.profileReads.getValue(WORK))
        assertEquals(ListingStatus.FAILED, result.status)
        assertEquals("java.lang.IllegalStateException", result.failure)
        assertTrue(result.packages.isEmpty())
        assertEquals(ListingStatus.COMPLETE, reader.readMaterials(plan.profileReads.getValue(OWN)).status)
    }

    @Test
    fun aProfileThatBecomesUnreadableWhileReadingReturnsNoPackages() {
        device.install(installed("io.example.maps", "地図", profile = WORK))
        val observation = reader.observe(emptySet())
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, observation, ENV)
        device.unavailable += WORK
        val result = reader.readMaterials(plan.profileReads.getValue(WORK))
        assertEquals(ListingStatus.UNAVAILABLE, result.status)
        assertTrue(result.packages.isEmpty())
    }

    @Test
    fun genresAreLimitedToTheRequestAndAFailedQueryIsAFailure() {
        val maps = installed("io.example.maps", "地図", genres = listOf(GenreId("map")))
        device.install(maps)
        device.unavailable += WORK
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, reader.observe(emptySet()), ENV)
        val found = reader.genres(plan.genres) as GenreResult.Found
        assertEquals(mapOf(maps.entry.id to listOf(GenreId("map"))), found.byEntry.filterValues { it.isNotEmpty() })
        assertTrue(found.byEntry.keys.all { it in plan.genres.targets })

        device.genreFailure = IllegalStateException()
        assertEquals("java.lang.IllegalStateException", (reader.genres(plan.genres) as GenreResult.Failed).cause)
    }

    // UserManager で削除を確かめた後も LauncherApps の一覧が古いまま仕事用の入口を返す。写しから落とした組が、次の同期でも戻らず、材料も読まない
    @Test
    fun aProfileConfirmedAsRemovedIsNotRestoredByAStaleListing() =
        runTest {
            device.install(installed("io.example.transit", "乗換"), installed("io.example.transit", "乗換", profile = WORK))
            val keys = FakeKeys()
            val actor =
                IndexActor(
                    backgroundScope,
                    IndexDatabase.open(context, null, allowMainThread = true),
                    reader,
                    keys,
                    1,
                    StandardTestDispatcher(testScheduler),
                )
            actor.requestSync().await()
            assertEquals(
                setOf(OWN, WORK),
                actor.snapshot.value.packages.keys
                    .mapTo(HashSet()) { it.profileSerial },
            )

            existing = setOf(OWN)
            actor.requestSync().await()
            assertEquals(
                1,
                actor.status.value.lastSync
                    ?.removed,
            )
            repeat(2) {
                device.reads.clear()
                val built = keys.calls
                actor.requestSync().await()
                assertEquals(
                    setOf(OWN),
                    actor.snapshot.value.packages.keys
                        .mapTo(HashSet()) { it.profileSerial },
                )
                assertTrue(WORK !in actor.snapshot.value.listings)
                assertEquals(emptyList<Any>(), device.reads)
                assertEquals(built, keys.calls)
            }
        }

    @Test
    fun onApi30TheLocalizedNameOfAWorkOnlyAppIsAnEmptySuccessAndTheIndexKeepsReadingsAndThePublisher() =
        runTest {
            val maps = installed("io.example.maps", "地図", profile = WORK, signers = listOf("CN=Maps,O=Example Maps"))
            device.install(maps)
            device.labelReader = workOnlyLabels()
            val actor =
                IndexActor(
                    backgroundScope,
                    IndexDatabase.open(context, null, allowMainThread = true),
                    // 自分のプロファイルの PackageManager から見えないと確かめられた
                    DeviceAppsReader(device, { existing }, sdkInt = Build.VERSION_CODES.R) { false },
                    GeneratorKeyFactory(
                        {},
                        { SearchKeyGenerator(ReadingOf("地図" to "ちず"), LatinWordReader(BrandTable.parse(emptyList()))) },
                    ),
                    1,
                    StandardTestDispatcher(testScheduler),
                )
            actor.requestSync().await()
            val state = checkNotNull(actor.snapshot.value.packages[PackageKey(WORK, "io.example.maps")])
            assertNull(state.attempt.issue)
            val keys = state.apps().single().keys
            assertTrue(keys.any { it.kind == SearchKeyKind.READING && it.text.value == "ちず" })
            assertTrue(keys.any { it.kind == SearchKeyKind.PUBLISHER })

            // 次のときは LABEL の失敗のまま: API 31 以上（一覧の後に消えた）、自分の側から見える（別の理由を隠さない）、
            // 見えるかの問い合わせが失敗した（隠さない側に倒す）
            val failing =
                listOf(
                    DeviceAppsReader(device, { existing }, sdkInt = Build.VERSION_CODES.S) { false },
                    DeviceAppsReader(device, { existing }, sdkInt = Build.VERSION_CODES.R) { true },
                    DeviceAppsReader(device, { existing }, sdkInt = Build.VERSION_CODES.R) { null },
                )
            for (other in failing) {
                val plan = IndexRules.plan(IndexSnapshot.EMPTY, other.observe(emptySet()), ENV)
                val read =
                    other
                        .readMaterials(plan.profileReads.getValue(WORK))
                        .packages.values
                        .single() as MaterialRead.Failed
                assertEquals(Stage.LABEL to IssueCategory.TRANSIENT, read.stage to read.failure.category)
                assertEquals(PackageManager.NameNotFoundException::class.java.name, read.failure.cause)
            }
        }

    // API 30 以下の経路（自分のプロファイルの createPackageContext）を、仕事用にしかないアプリで再現する
    private fun workOnlyLabels() =
        LocalizedLabelReader(ApplicationProvider.getApplicationContext()) { activity, _ ->
            check(activity.user != Process.myUserHandle()) { "この試験は仕事用の組だけ" }
            throw PackageManager.NameNotFoundException(activity.id.packageName)
        }

    // 決めた語にだけ読みを付ける形態素解析器
    private class ReadingOf(
        vararg readings: Pair<String, String>,
    ) : ReadingAnalyzer {
        private val readings = readings.toMap()

        override fun analyze(text: String): List<ReadingToken> =
            if (text.isBlank()) emptyList() else listOf(ReadingToken(text, readings[text], 0))
    }

    private fun read(): Map<String, MaterialRead> {
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, reader.observe(emptySet()), ENV)
        val reads: List<ProfileReads> = plan.profileReads.values.map(reader::readMaterials)
        return reads.flatMap { it.packages.entries }.associate { (job, read) -> job.key.packageName to read }
    }

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
        val ENV = Environment("ja-JP", "test-build", 1, 7, "brand-1", 1, 1)
    }
}
