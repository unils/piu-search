// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.state.Environment
import jp.piu.search.core.state.GenreResult
import jp.piu.search.core.state.IndexRules
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.MaterialRead
import jp.piu.search.core.state.PackageObservation
import jp.piu.search.core.state.ProfileReads
import jp.piu.search.core.state.Stage
import jp.piu.search.data.FakeDevice
import jp.piu.search.data.InstalledApp
import jp.piu.search.data.MaterialCauses
import jp.piu.search.data.MaterialReadException
import jp.piu.search.data.MaterialStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.cert.CertificateException

// 0.2.0 の読み手の結果を、同期の規則の入力の形に変えることを、0.2.0 のテストと同じ偽の端末（本物の readPackage と分類を通す）で確かめる
@RunWith(AndroidJUnit4::class)
class DeviceAppsReaderTest {
    private val device = FakeDevice()
    private var existing: Set<Long>? = setOf(OWN, WORK)
    private val reader = DeviceAppsReader(device) { existing }

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
