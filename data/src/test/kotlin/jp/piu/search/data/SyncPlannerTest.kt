// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.DeadObjectException
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.cert.CertificateException

// 差分の同期の規則（architecture.md の「何を読み直すか」「読み直しの規則」）を、Room と端末なしで確かめる。
// 読み取りは本物の readPackage で組み立て、分類は MaterialCauses を通す。保存と知らせの消費は IndexSyncTest が統合で確かめる。
// LaunchableActivity が ApplicationInfo を持つので Robolectric で動かす
@RunWith(AndroidJUnit4::class)
class SyncPlannerTest {
    @Test
    fun scopeFollowsTheSavedState() {
        assertEquals(SyncMode.FULL_REBUILD, SyncPlanner.scope(null, NOW).mode)
        assertEquals(SyncMode.FULL_REBUILD, SyncPlanner.scope(SAVED.copy(locales = "en-US"), NOW).mode)
        assertEquals(SyncMode.FULL_REBUILD, SyncPlanner.scope(SAVED.copy(buildFingerprint = "old"), NOW).mode)
        assertEquals(SyncScope(SyncMode.CHANGES, 7), SyncPlanner.scope(SAVED, NOW))
        assertEquals(SyncScope(SyncMode.FULL_REVIEW, 0, bootChanged = true), SyncPlanner.scope(SAVED.copy(bootCount = 1), NOW))
        assertEquals(SyncScope(SyncMode.FULL_REVIEW, 0), SyncPlanner.scope(SAVED.copy(changeSequence = null), NOW))
        // 回数が取れないときは「変わった」と言えない
        assertEquals(SyncScope(SyncMode.FULL_REVIEW, 0), SyncPlanner.scope(SAVED, NOW.copy(bootCount = null)))
        assertEquals(SyncScope(SyncMode.FULL_REVIEW, 0), SyncPlanner.scope(SAVED.copy(bootCount = null), NOW))
        // このアプリの版。0.1.x の索引（null）も違うとみなす
        assertTrue(SyncPlanner.scope(SAVED.copy(appVersionCode = null), NOW).versionChanged)
        assertTrue(SyncPlanner.scope(SAVED.copy(appVersionCode = 1), NOW).versionChanged)
        assertFalse(SyncPlanner.scope(SAVED, NOW).versionChanged)
    }

    // 鍵の比べ方の 4 通り
    @Test
    fun keysAreComparedInFourWays() {
        assertFalse(SyncPlanner.changed(1L, 1L))
        assertTrue(SyncPlanner.changed(1L, 2L))
        assertTrue(SyncPlanner.changed(1L, null))
        assertTrue(SyncPlanner.changed(null, 1L))
        assertFalse(SyncPlanner.changed<Long>(null, null))
        // 0 は APEX の中の APK の本当の時刻で、ほかの値と同じく比べる
        assertTrue(SyncPlanner.changed(0L, 1L))
        assertFalse(SyncPlanner.changed(0L, 0L))
    }

    @Test
    fun reviewComparesTimesAndKeylessPackagesAreNotReadAgain() {
        val stored = listOf(stored(MAPS), stored(MAIL), stored(CLOCK, time = null), stored(NOTES, time = 0))
        val now = listOf(stamp(MAPS), stamp(MAIL, time = 2_000), stamp(CLOCK, time = null), stamp(NOTES, time = 0))
        assertEquals(setOf(key(MAIL)), targets(REVIEW, stored, now).reads.keys)
        // 変更の番号が使えない端末（毎回が見直し）でも、両方とも鍵のない組は読まない
        assertEquals(
            setOf(key(MAIL)),
            targets(
                REVIEW,
                stored,
                now.map {
                    if (it.id.packageName ==
                        CLOCK
                    ) {
                        it.copy(apkFingerprint = null)
                    } else {
                        it
                    }
                },
            ).reads.keys,
        )
    }

    @Test
    fun sequenceSelectsTheChangedPersonalPackageAndWorkIsComparedByFingerprint() {
        val stored = listOf(stored(MAPS), stored(MAIL), stored(MAPS, WORK), stored(MAIL, WORK))
        val now = listOf(stamp(MAPS), stamp(MAIL), stamp(MAPS, WORK), stamp(MAIL, WORK, fingerprint = "apk:2"))
        assertEquals(setOf(key(MAPS), key(MAIL, WORK)), targets(CHANGES, stored, now, changed = setOf(MAPS)).reads.keys)
    }

    @Test
    fun keylessWorkPackageIsReadOnlyWhenTheBootCountChanged() {
        val stored = listOf(stored(MAPS, WORK, fingerprint = null, time = null))
        val now = listOf(stamp(MAPS, WORK, fingerprint = null, time = null))
        assertEquals(emptySet<PackageKey>(), targets(REVIEW, stored, now).reads.keys)
        assertEquals(setOf(key(MAPS, WORK)), targets(REVIEW.copy(bootChanged = true), stored, now).reads.keys)
        // 鍵のある組は、再起動だけでは読まない
        assertEquals(
            emptySet<PackageKey>(),
            targets(REVIEW.copy(bootChanged = true), listOf(stored(MAPS, WORK)), listOf(stamp(MAPS, WORK))).reads.keys,
        )
    }

    @Test
    fun entrySetArchiveStateRebuildMarkAndCallbackAreNormalConditions() {
        val stored =
            listOf(stored(MAPS), stored(MAIL), stored(CLOCK, rebuildPending = true), stored(NOTES), stored(GAME, archived = true))
        val now =
            listOf(
                stamp(MAPS),
                stamp(MAPS, activity = "Second"),
                stamp(MAIL, archived = true),
                stamp(CLOCK),
                stamp(NOTES),
                stamp(GAME),
            )
        val targets = targets(CHANGES, stored, now, forced = setOf(key(NOTES)))
        assertEquals(setOf(key(MAPS), key(MAIL), key(CLOCK), key(NOTES), key(GAME)), targets.reads.keys)
        assertTrue(targets.reads.values.none { it })
    }

    @Test
    fun issueRowsAloneAreNotReadButRetryOnceAndVersionChangeAre() {
        val stored = listOf(stored(MAPS), stored(MAIL), stored(CLOCK))
        val now = listOf(stamp(MAPS), stamp(MAIL), stamp(CLOCK))
        val issues = listOf(issue(MAPS, IOException::class.java.name, retryOnce = true), issue(MAIL, CertificateException::class.java.name))
        val targets = targets(CHANGES, stored, now, issues = issues)
        assertEquals(mapOf(key(MAPS) to true), targets.reads)
        val upgraded = targets(CHANGES.copy(versionChanged = true), stored, now, issues = issues)
        // 版の変更は通常の条件なので、retry_once の組も理由は「それだけ」ではない
        assertEquals(mapOf(key(MAPS) to false, key(MAIL) to false), upgraded.reads)
    }

    @Test
    fun retryOnceOfTheWorkPackageDoesNotReadThePersonalPackageOfTheSameName() {
        val stored = listOf(stored(MAPS), stored(MAPS, WORK))
        val now = listOf(stamp(MAPS), stamp(MAPS, WORK))
        val targets =
            targets(CHANGES, stored, now, issues = listOf(issue(MAPS, IOException::class.java.name, retryOnce = true, profile = WORK)))
        assertEquals(setOf(key(MAPS, WORK)), targets.readTargets.keys)
    }

    // 鍵が変わって読み、決定的・不具合の疑いの欠けになった組は、鍵が同じ 3 回目に読まない。保存する鍵は今回の値
    @Test
    fun failedPackageConvergesAfterTheKeyIsSaved() {
        val now = listOf(stamp(MAPS, fingerprint = "apk:2", time = 2_000))
        val first = targets(REVIEW, listOf(stored(MAPS)), now)
        assertEquals(setOf(key(MAPS)), first.reads.keys)
        val read = read(now, signerFailure = CertificateException())
        val plan = plan(REVIEW, first, listOf(stored(MAPS)), emptyList(), read)
        val pkg = plan.packages.single()
        assertEquals(ApkKeys(2_000, "apk:2"), pkg.keys)
        val signer = pkg.issues.getValue(MaterialStage.SIGNER) as IssueDecision.Put
        assertEquals(IssueCategory.DETERMINISTIC, signer.issue.category)
        assertFalse(signer.issue.retryOnce)
        // 保存した後の 3 回目: 同じ一覧では読まない
        val saved = listOf(stored(MAPS, fingerprint = "apk:2", time = 2_000))
        assertEquals(emptySet<PackageKey>(), targets(REVIEW, saved, now, issues = listOf(signer.issue)).reads.keys)
    }

    @Test
    fun transientFailureSetsRetryOnceOnlyWhenReadForAnotherReason() {
        val now = listOf(stamp(MAPS))
        val failure = RuntimeException(DeadObjectException())
        val normal =
            plan(
                CHANGES,
                targets(CHANGES, listOf(stored(MAPS)), now, forced = setOf(key(MAPS))),
                listOf(stored(MAPS)),
                emptyList(),
                read(now, labelFailure = failure),
            )
        val first =
            (
                normal.packages
                    .single()
                    .issues
                    .getValue(MaterialStage.LABEL) as IssueDecision.Put
            ).issue
        assertEquals("android.os.DeadObjectException", first.cause)
        assertEquals(IssueCategory.TRANSIENT, first.category)
        assertTrue(first.retryOnce)
        val retried = targets(CHANGES, listOf(stored(MAPS)), now, issues = listOf(first))
        assertEquals(mapOf(key(MAPS) to true), retried.reads)
        val second =
            (
                plan(
                    CHANGES,
                    retried,
                    listOf(stored(MAPS)),
                    listOf(first),
                    read(now, labelFailure = failure),
                ).packages.single().issues.getValue(MaterialStage.LABEL) as IssueDecision.Put
            ).issue
        assertFalse(second.retryOnce)
        // 続けて欠けている間は初めの日を保つ
        assertEquals(first.firstFailedOn, second.firstFailedOn)
        assertEquals(emptySet<PackageKey>(), targets(CHANGES, listOf(stored(MAPS)), now, issues = listOf(second)).reads.keys)
        // 読めたら行を消す
        val recovered = plan(CHANGES, retried, listOf(stored(MAPS)), listOf(first), read(now))
        assertEquals(IssueDecision.Delete, recovered.packages.single().issues[MaterialStage.LABEL])
    }

    // 一覧・読む前・読んだ後の 3 つを別々の値にする。保存する鍵は一覧の値で、前後の値でも別の時点の組み合わせでもない
    @Test
    fun keysMovingDuringTheReadAreAChangedDuringReadAndTheListedKeysAreSaved() {
        val now = listOf(stamp(MAPS, fingerprint = "apk:list", time = 1_000))
        val targets = targets(REVIEW, emptyList(), now)
        val read = read(now, before = ApkKeys(1_500, "apk:before"), after = ApkKeys(2_000, "apk:after"))
        val pkg = plan(REVIEW, targets, emptyList(), emptyList(), read).packages.single()
        assertEquals(MaterialCauses.CHANGED_DURING_READ, pkg.entryFailure?.cause)
        assertEquals(ApkKeys(1_000, "apk:list"), pkg.keys)
        assertEquals(IssueCategory.TRANSIENT, (pkg.issues.getValue(MaterialStage.ENTRY) as IssueDecision.Put).issue.category)
        // ENTRY が欠けた回の補助の材料は使わない
        assertEquals(IssueDecision.Keep, pkg.issues[MaterialStage.LABEL])
        assertEquals(IssueDecision.Keep, pkg.issues[MaterialStage.SIGNER])
    }

    // 時刻を引かなかった一覧では、鍵を確定する前に指紋が替わりうる。一覧の入口の指紋と確定した鍵が違えば CHANGED_DURING_READ
    // （表示名は一覧の時点の APK のもの）。読む前と読んだ後が確定した鍵と同じでも見逃さない
    @Test
    fun fingerprintMovingBeforeTheKeysAreFixedIsAChangedDuringRead() {
        val now = listOf(stamp(MAPS, fingerprint = "apk:list", time = null))
        val fixed = ApkKeys(2_000, "apk:fixed")
        val read = read(now, before = fixed, after = fixed, listedKeys = fixed)
        val pkg = plan(CHANGES, targets(CHANGES, emptyList(), now), emptyList(), emptyList(), read).packages.single()
        assertEquals(MaterialCauses.CHANGED_DURING_READ, pkg.entryFailure?.cause)
        assertEquals(fixed, pkg.keys)
        val same = read(now, listedKeys = ApkKeys(2_000, "apk:list"))
        assertNull(plan(CHANGES, targets(CHANGES, emptyList(), now), emptyList(), emptyList(), same).packages.single().entryFailure)
    }

    @Test
    fun withoutListedKeysTheReadIsNotCompared() {
        val now = listOf(stamp(MAPS, fingerprint = null, time = null))
        val read = read(now, before = ApkKeys(1, "apk:x"), after = ApkKeys(2, "apk:y"))
        val pkg = plan(REVIEW, targets(REVIEW, emptyList(), now), emptyList(), emptyList(), read).packages.single()
        assertNull(pkg.entryFailure)
        assertEquals(ApkKeys(null, null), pkg.keys)
    }

    // 入口が増えた組と減った組で ENTRY が欠けても、索引の入口の集合は一覧と同じ（共通は旧索引の行、新しい入口は代わりの行、消えた入口はなし）
    @Test
    fun entryFailureKeepsCommonEntriesAddsNewOnesAndDropsVanishedOnes() {
        val old = listOf(stored(MAPS), stored(MAPS, activity = "Gone"))
        val now = listOf(stamp(MAPS), stamp(MAPS, activity = "New"))
        val targets = targets(CHANGES, old, now)
        val pkg =
            plan(
                CHANGES,
                targets,
                old,
                emptyList(),
                read(now, entryFailure = PackageManager.NameNotFoundException()),
            ).packages.single()
        assertEquals(listOf(id(MAPS)), pkg.kept.map { it.app.id })
        assertEquals(listOf(id(MAPS, activity = "New")), pkg.build.map { it.id })
        assertTrue(pkg.substitute)
        assertEquals(PackageManager.NameNotFoundException::class.java.name, pkg.entryFailure?.cause)
        // 3 回目: 保存した後の索引（一覧と同じ入口の集合）では読まない
        val saved = listOf(stored(MAPS), stored(MAPS, activity = "New"))
        assertEquals(emptySet<PackageKey>(), targets(CHANGES, saved, now).reads.keys)
    }

    @Test
    fun vanishedEntryAndListingLabelFailureAreEntryFailures() {
        val now = listOf(stamp(MAPS), stamp(MAPS, activity = "Second"))
        val vanished = read(now, vanish = setOf(id(MAPS, activity = "Second")))
        assertEquals(
            MaterialCauses.VANISHED,
            plan(REVIEW, targets(REVIEW, emptyList(), now), emptyList(), emptyList(), vanished)
                .packages
                .single()
                .entryFailure
                ?.cause,
        )
        val label = read(now, labelOf = { if (it.activityName.endsWith("Second")) throw Resources.NotFoundException() else it.packageName })
        val pkg = plan(REVIEW, targets(REVIEW, emptyList(), now), emptyList(), emptyList(), label).packages.single()
        assertEquals(Resources.NotFoundException::class.java.name, pkg.entryFailure?.cause)
        // 表示名を取れなかった新しい入口はパッケージ名で入る
        assertEquals(listOf(MAPS, MAPS), pkg.build.map { it.label })
    }

    @Test
    fun archivedPackageDoesNotReadAuxiliaryMaterialAndDeletesTheirRows() {
        val now = listOf(stamp(MAPS, archived = true, fingerprint = null, time = null))
        val issues =
            listOf(
                issue(MAPS, CertificateException::class.java.name, stage = MaterialStage.SIGNER),
                issue(MAPS, Resources.NotFoundException::class.java.name, stage = MaterialStage.LABEL),
            )
        val read = read(now, signerFailure = CertificateException())
        assertFalse(read.auxiliaryRead)
        val pkg =
            plan(
                CHANGES,
                targets(CHANGES, listOf(stored(MAPS)), now, issues = issues),
                listOf(stored(MAPS)),
                issues,
                read,
            ).packages.single()
        assertEquals(IssueDecision.Delete, pkg.issues[MaterialStage.LABEL])
        assertEquals(IssueDecision.Delete, pkg.issues[MaterialStage.SIGNER])
        assertEquals(IssueDecision.Delete, pkg.issues[MaterialStage.ENTRY])
        assertTrue(pkg.read.archived)
    }

    @Test
    fun unreadableProfilesAreMarkedForFullRebuildAndVersionChangeButNotOtherwise() {
        val stored = listOf(stored(MAPS), stored(MAPS, WORK), stored(MAIL, WORK))
        val profiles = mapOf(PERSONAL to complete(stamp(MAPS)), WORK to ProfileRead<ActivityStamp>(ProfileReadStatus.UNAVAILABLE))
        val issues = listOf(issue(MAIL, IOException::class.java.name, profile = WORK))

        fun pending(scope: SyncScope) =
            SyncPlanner
                .plan(
                    scope,
                    NOW,
                    SyncPlanner.targets(scope, stored, issues, {
                        true
                    }, profiles, emptySet(), emptySet()),
                    stored,
                    issues,
                    { true },
                    7,
                    emptyMap(),
                    emptyMap(),
                    TODAY,
                ).pending
        assertEquals(emptySet<PackageKey>(), pending(CHANGES))
        assertEquals(setOf(key(MAIL, WORK)), pending(CHANGES.copy(versionChanged = true)))
        assertEquals(setOf(key(MAPS, WORK), key(MAIL, WORK)), pending(FULL))
    }

    @Test
    fun packageMissingFromACompleteListIsRemovedWithoutReadingAndFailedProfilesAreKept() {
        val stored = listOf(stored(MAPS), stored(MAIL), stored(MAPS, WORK))
        val profiles =
            mapOf(
                PERSONAL to complete(stamp(MAPS)),
                WORK to ProfileRead<ActivityStamp>(ProfileReadStatus.FAILED, failure = "android.os.DeadObjectException"),
            )
        val targets = SyncPlanner.targets(CHANGES, stored, emptyList(), { true }, profiles, emptySet(), emptySet())
        assertEquals(setOf(key(MAIL)), targets.removed)
        assertEquals(emptySet<PackageKey>(), targets.reads.keys)
        val plan = SyncPlanner.plan(CHANGES, NOW, targets, stored, emptyList(), { true }, 7, emptyMap(), emptyMap(), TODAY)
        assertEquals(setOf(key(MAIL)), plan.removed)
        assertEquals(mapOf(PERSONAL to ProfileReadStatus.COMPLETE, WORK to ProfileReadStatus.FAILED), plan.profileResults)
        assertEquals(mapOf(WORK to "android.os.DeadObjectException"), plan.profileFailures)
        assertEquals(setOf(PERSONAL), plan.completed)
    }

    @Test
    fun detailsThatBecameUnavailableAreNotApplied() {
        val now = listOf(stamp(MAPS))
        val targets = targets(CHANGES, emptyList(), now)
        val plan =
            SyncPlanner.plan(
                CHANGES,
                NOW,
                targets,
                emptyList(),
                emptyList(),
                { true },
                7,
                emptyMap(),
                mapOf(PERSONAL to ProfileRead(ProfileReadStatus.UNAVAILABLE)),
                TODAY,
            )
        assertEquals(emptyList<PackagePlan>(), plan.packages)
        assertEquals(emptySet<Long>(), plan.completed)
        assertEquals(ProfileReadStatus.UNAVAILABLE, plan.profileResults[PERSONAL])
    }

    // 区分は保存せず理由から引く。分類の表で理由の区分を替えれば、同じ行の扱いが替わる（ここでは表の違う行どうしで確かめる）
    @Test
    fun categoryComesFromTheStoredCause() {
        val transient = issue(MAPS, IOException::class.java.name)
        val deterministic = transient.copy(cause = CertificateException::class.java.name)
        val bug = transient.copy(cause = "jp.piu.search.data.Renamed")
        assertEquals(IssueCategory.TRANSIENT, transient.category)
        assertEquals(IssueCategory.DETERMINISTIC, deterministic.category)
        assertEquals(IssueCategory.SUSPECTED_BUG, bug.category)
        assertEquals(IssueCategory.SUSPECTED_BUG, transient.copy(stage = MaterialStage.KEYS).category)
    }

    private fun targets(
        scope: SyncScope,
        stored: List<StoredApp>,
        now: List<ActivityStamp>,
        changed: Set<String> = emptySet(),
        forced: Set<PackageKey> = emptySet(),
        issues: List<MaterialIssue> = emptyList(),
    ): SyncTargets =
        SyncPlanner.targets(
            scope,
            stored,
            issues,
            { true },
            now.groupBy { it.id.profileSerial }.mapValues { ProfileRead(ProfileReadStatus.COMPLETE, it.value) },
            changed,
            forced,
        )

    private fun plan(
        scope: SyncScope,
        targets: SyncTargets,
        stored: List<StoredApp>,
        issues: List<MaterialIssue>,
        read: PackageRead,
    ): SyncPlan =
        SyncPlanner.plan(
            scope,
            NOW,
            targets,
            stored,
            issues,
            { true },
            7,
            emptyMap(),
            mapOf(read.key.profileSerial to ProfileRead(ProfileReadStatus.COMPLETE, listOf(read))),
            TODAY,
        )

    private fun read(
        listed: List<ActivityStamp>,
        before: ApkKeys? = null,
        after: ApkKeys? = null,
        entryFailure: Exception? = null,
        labelFailure: Exception? = null,
        signerFailure: Exception? = null,
        vanish: Set<AppId> = emptySet(),
        labelOf: (AppId) -> String = { it.packageName },
        listedKeys: ApkKeys? = null,
    ): PackageRead {
        val first = listed.first()
        val keys = listedKeys ?: ApkKeys(first.packageUpdatedAt, first.apkFingerprint)
        var phase = 0
        return readPackage(
            key = PackageKey(first.id.profileSerial, first.id.packageName),
            listed = listed,
            listedKeys = keys,
            labelOf = labelOf,
            observeKeys = { (if (phase++ == 0) before else after) ?: keys },
            details = {
                entryFailure?.let { throw it }
                listed.filter { it.id !in vanish }.map {
                    LaunchableActivity(
                        it.id,
                        it.id.packageName,
                        Process.myUserHandle(),
                        ApplicationInfo(),
                        0,
                    )
                }
            },
            readLabels = { LabelRead(emptyList(), labelFailure) },
            readSigners = {
                signerFailure?.let { throw it }
                listOf("O=Example")
            },
        )
    }

    private companion object {
        const val MAPS = "io.example.maps"
        const val MAIL = "io.example.mail"
        const val CLOCK = "io.example.clock"
        const val NOTES = "io.example.notes"
        const val GAME = "io.example.game"
        const val TODAY = "2026-11-15"

        val NOW = DeviceState(bootCount = 2, locales = "ja-JP", buildFingerprint = "build", appVersionCode = 5)
        val SAVED = SyncState(changeSequence = 7, bootCount = 2, locales = "ja-JP", buildFingerprint = "build", appVersionCode = 5)
        val CHANGES = SyncScope(SyncMode.CHANGES, 7)
        val REVIEW = SyncScope(SyncMode.FULL_REVIEW, 0)
        val FULL = SyncScope(SyncMode.FULL_REBUILD, 0)

        fun id(
            packageName: String,
            profile: Long = PERSONAL,
            activity: String = "Main",
        ) = AppId(packageName, "$packageName.$activity", profile)

        fun key(
            packageName: String,
            profile: Long = PERSONAL,
        ) = PackageKey(profile, packageName)

        fun stamp(
            packageName: String,
            profile: Long = PERSONAL,
            time: Long? = 1_000,
            fingerprint: String? = "apk:1",
            activity: String = "Main",
            archived: Boolean = false,
        ) = ActivityStamp(
            id(packageName, profile, activity),
            time,
            fingerprint,
            isCurrentProfile = profile == PERSONAL,
            isArchived = archived,
        )

        fun stored(
            packageName: String,
            profile: Long = PERSONAL,
            fingerprint: String? = "apk:1",
            time: Long? = 1_000,
            activity: String = "Main",
            archived: Boolean = false,
            rebuildPending: Boolean = false,
        ) = StoredApp(IndexedApp(id(packageName, profile, activity), packageName, emptyList()), time, fingerprint, archived, rebuildPending)

        fun issue(
            packageName: String,
            cause: String,
            retryOnce: Boolean = false,
            profile: Long = PERSONAL,
            stage: MaterialStage = MaterialStage.LABEL,
        ) = MaterialIssue(key(packageName, profile), stage, cause, null, null, 5, retryOnce, TODAY, TODAY)

        fun <T> complete(vararg items: T) = ProfileRead(ProfileReadStatus.COMPLETE, items.toList())
    }
}
