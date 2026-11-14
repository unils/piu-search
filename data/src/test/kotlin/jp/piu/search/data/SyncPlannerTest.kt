// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 差分の同期の規則を、Room と端末なしで確かめる。保存と知らせの消費は IndexSyncTest が統合で確かめる
class SyncPlannerTest {
    @Test
    fun scopeFollowsTheSavedState() {
        assertEquals(SyncScope(SyncMode.FULL_REBUILD, 0), SyncPlanner.scope(null, NOW))
        assertEquals(SyncScope(SyncMode.FULL_REBUILD, 0), SyncPlanner.scope(SAVED.copy(locales = "en-US"), NOW))
        assertEquals(SyncScope(SyncMode.FULL_REBUILD, 0), SyncPlanner.scope(SAVED.copy(buildFingerprint = "old"), NOW))
        assertEquals(SyncScope(SyncMode.CHANGES, 7), SyncPlanner.scope(SAVED, NOW))
        assertEquals(SyncScope(SyncMode.FULL_REVIEW, 0), SyncPlanner.scope(SAVED.copy(bootCount = 1), NOW))
        assertEquals(SyncScope(SyncMode.FULL_REVIEW, 0), SyncPlanner.scope(SAVED.copy(changeSequence = null), NOW))
        assertEquals(SyncScope(SyncMode.FULL_REVIEW, 0), SyncPlanner.scope(SAVED, NOW.copy(bootCount = null)))
    }

    @Test
    fun unchangedDeviceReadsNothingAndSavesTheNewSequence() {
        val plan = planOf(CHANGES, listOf(stored(MAPS)), mapOf(PERSONAL to complete(stamp(MAPS))), sequence = 9)

        assertTrue(plan.rebuild.isEmpty())
        assertTrue(plan.removedPackages.isEmpty())
        assertTrue(plan.failedPackages.isEmpty())
        assertTrue(plan.changedPackages.isEmpty())
        assertEquals(setOf(PERSONAL), plan.readable)
        assertEquals(SyncState(9, 2, "ja-JP", "build"), plan.state)
    }

    @Test
    fun sequenceSelectsOnlyTheChangedPackage() {
        val targets =
            targets(
                CHANGES,
                listOf(stored(MAPS), stored(MAIL)),
                mapOf(PERSONAL to complete(stamp(MAPS), stamp(MAIL))),
                changed = setOf(MAIL),
            )

        assertEquals(setOf(PackageKey(PERSONAL, MAIL)), targets.dirty)
    }

    @Test
    fun localeOrBuildChangeRebuildsEveryReadablePackage() {
        val scope = SyncPlanner.scope(SAVED.copy(locales = "en-US"), NOW)
        val targets = targets(scope, listOf(stored(MAPS), stored(MAIL)), mapOf(PERSONAL to complete(stamp(MAPS), stamp(MAIL))))

        assertEquals(setOf(PackageKey(PERSONAL, MAPS), PackageKey(PERSONAL, MAIL)), targets.dirty)
    }

    @Test
    fun reviewWithoutASequenceComparesUpdateTimes() {
        val targets =
            targets(
                REVIEW,
                listOf(stored(MAPS), stored(MAIL), stored(CLOCK)),
                mapOf(PERSONAL to complete(stamp(MAPS), stamp(MAIL, time = 2_000), stamp(CLOCK, time = 0))),
            )

        // 時刻 0 は取得できなかった時刻なので、同じとみなさない
        assertEquals(setOf(PackageKey(PERSONAL, MAIL), PackageKey(PERSONAL, CLOCK)), targets.dirty)
    }

    @Test
    fun storedEntryWithoutAFingerprintIsReadAgain() {
        val targets = targets(CHANGES, listOf(stored(MAPS, fingerprint = null)), mapOf(PERSONAL to complete(stamp(MAPS))))

        assertEquals(setOf(PackageKey(PERSONAL, MAPS)), targets.dirty)
    }

    @Test
    fun workProfileIsComparedByApkFingerprint() {
        val targets =
            targets(
                CHANGES,
                listOf(stored(MAPS, WORK), stored(MAIL, WORK)),
                mapOf(WORK to complete(stamp(MAPS, WORK), stamp(MAIL, WORK, fingerprint = "apk:new"))),
            )

        assertEquals(setOf(PackageKey(WORK, MAIL)), targets.dirty)
    }

    @Test
    fun notifiedOrAddedOrChangedEntriesAreRead() {
        val added = stamp(CLOCK)
        val secondEntry = stamp(MAIL).copy(id = AppId(MAIL, "$MAIL.Compose", PERSONAL))
        val targets =
            targets(
                CHANGES,
                listOf(stored(MAPS), stored(MAIL), stored(NOTES)),
                mapOf(PERSONAL to complete(stamp(MAPS), stamp(MAIL), secondEntry, added)),
                forced = setOf(PackageKey(PERSONAL, MAPS)),
            )

        // 番号に出なくても、知らせ・入口の増減・一覧からの消失で読む
        assertEquals(
            setOf(PackageKey(PERSONAL, MAPS), PackageKey(PERSONAL, MAIL), PackageKey(PERSONAL, CLOCK), PackageKey(PERSONAL, NOTES)),
            targets.dirty,
        )
    }

    @Test
    fun pausedProfileKeepsItsIndexDuringAFullRebuild() {
        val scope = SyncPlanner.scope(SAVED.copy(locales = "en-US"), NOW)
        val profiles = mapOf(PERSONAL to complete(stamp(MAPS)), WORK to ProfileRead(ProfileReadStatus.UNAVAILABLE))
        val targets = targets(scope, listOf(stored(MAPS), stored(MAPS, WORK)), profiles)
        val plan = plan(scope, targets, mapOf(PERSONAL to complete(installed(MAPS)), WORK to ProfileRead(ProfileReadStatus.UNAVAILABLE)))

        assertEquals(setOf(PackageKey(PERSONAL, MAPS)), targets.dirty)
        assertEquals(setOf(PERSONAL), plan.readable)
        assertTrue(plan.removedPackages.isEmpty())
        assertTrue(plan.failedPackages.isEmpty())
        // 一時停止は失敗に数えない
        assertEquals(0, plan.failedProfiles)
        // 前の言語を残すと、再開までの同期がすべて全件の作り直しになる
        assertEquals("ja-JP", plan.state.locales)
    }

    @Test
    fun failedProfileIsCountedAndNotRemoved() {
        val profiles = mapOf(PERSONAL to complete(stamp(MAPS)), WORK to ProfileRead(ProfileReadStatus.FAILED))
        val targets = targets(CHANGES, listOf(stored(MAPS), stored(MAIL, WORK)), profiles)
        val plan = plan(CHANGES, targets, emptyMap())

        assertTrue(targets.dirty.isEmpty())
        assertTrue(plan.removedPackages.isEmpty())
        assertEquals(1, plan.failedProfiles)
    }

    @Test
    fun removedProfileIsNeitherReadNorAskedForGenres() {
        val profiles = mapOf(PERSONAL to complete(stamp(MAPS)), WORK to complete(stamp(MAIL, WORK)))
        val targets = SyncPlanner.targets(CHANGES, listOf(stored(MAPS)), { it != WORK }, profiles, emptySet(), emptySet())

        assertTrue(targets.dirty.isEmpty())
        assertEquals(listOf(stamp(MAPS)), targets.stamps)
    }

    @Test
    fun packageMissingFromTheListIsRemovedOnlyWhenTheDetailsWereRead() {
        val targets = targets(CHANGES, listOf(stored(MAPS), stored(MAIL)), mapOf(PERSONAL to complete(stamp(MAPS))))

        assertEquals(setOf(PackageKey(PERSONAL, MAIL)), plan(CHANGES, targets, mapOf(PERSONAL to complete())).removedPackages)
        val unreadable = plan(CHANGES, targets, mapOf(PERSONAL to ProfileRead(ProfileReadStatus.UNAVAILABLE)))
        assertTrue(unreadable.removedPackages.isEmpty())
        assertEquals(setOf(PackageKey(PERSONAL, MAIL)), unreadable.failedPackages)
        assertTrue(unreadable.readable.isEmpty())
    }

    @Test
    fun entryListedButMissingFromTheDetailsIsAFailure() {
        val targets =
            targets(CHANGES, listOf(stored(MAPS)), mapOf(PERSONAL to complete(stamp(MAPS))), forced = setOf(PackageKey(PERSONAL, MAPS)))
        val plan = plan(CHANGES, targets, mapOf(PERSONAL to complete()))

        assertEquals(setOf(PackageKey(PERSONAL, MAPS)), plan.failedPackages)
        assertTrue(plan.removedPackages.isEmpty())
        assertTrue(plan.rebuild.isEmpty())
    }

    @Test
    fun incompleteOrChangedDetailsAreRebuiltButMarkedFailed() {
        val targets =
            targets(
                CHANGES,
                listOf(stored(MAPS, WORK), stored(MAIL, WORK), stored(NOTES, WORK)),
                mapOf(
                    WORK to
                        complete(
                            stamp(MAPS, WORK, fingerprint = "apk:2"),
                            stamp(MAIL, WORK, fingerprint = "apk:2"),
                            stamp(NOTES, WORK, fingerprint = "apk:2"),
                        ),
                ),
            )
        val details =
            complete(
                // 一覧の後に APK が替わった
                installed(MAPS, WORK, fingerprint = "apk:3"),
                installed(MAIL, WORK, fingerprint = "apk:2").copy(complete = false),
                installed(NOTES, WORK, fingerprint = "apk:2"),
            ).copy(failedPackages = setOf(CLOCK))
        val plan = plan(CHANGES, targets, mapOf(WORK to details))

        assertEquals(setOf(PackageKey(WORK, MAPS), PackageKey(WORK, MAIL), PackageKey(WORK, CLOCK)), plan.failedPackages)
        assertEquals(setOf(MAPS, MAIL, NOTES), plan.rebuild.mapTo(HashSet()) { it.entry.id.packageName })
        assertFalse(plan.rebuild.single { it.entry.id.packageName == MAPS }.complete)
        assertEquals(setOf(MAPS, MAIL, NOTES), plan.changedPackages)
    }

    @Test
    fun failedDetailQueryIsCountedWithTheListFailures() {
        val profiles = mapOf(PERSONAL to complete(stamp(MAPS)), WORK to ProfileRead(ProfileReadStatus.FAILED))
        val targets = targets(CHANGES, emptyList(), profiles)
        val plan = plan(CHANGES, targets, mapOf(PERSONAL to ProfileRead(ProfileReadStatus.FAILED)))

        assertEquals(2, plan.failedProfiles)
        assertEquals(setOf(PackageKey(PERSONAL, MAPS)), plan.failedPackages)
    }

    private fun planOf(
        scope: SyncScope,
        stored: List<StoredApp>,
        profiles: Map<Long, ProfileRead<ActivityStamp>>,
        sequence: Int = 7,
    ): SyncPlan {
        val targets = targets(scope, stored, profiles)
        return plan(scope, targets, emptyMap(), sequence)
    }

    private fun targets(
        scope: SyncScope,
        stored: List<StoredApp>,
        profiles: Map<Long, ProfileRead<ActivityStamp>>,
        changed: Set<String> = emptySet(),
        forced: Set<PackageKey> = emptySet(),
    ): SyncTargets = SyncPlanner.targets(scope, stored, { true }, profiles, changed, forced)

    private fun plan(
        scope: SyncScope,
        targets: SyncTargets,
        candidates: Map<Long, ProfileRead<InstalledApp>>,
        sequence: Int = 7,
    ): SyncPlan = SyncPlanner.plan(scope, NOW, targets, { true }, sequence, emptyMap(), candidates)

    private companion object {
        const val MAPS = "io.example.maps"
        const val MAIL = "io.example.mail"
        const val CLOCK = "io.example.clock"
        const val NOTES = "io.example.notes"

        val NOW = DeviceState(bootCount = 2, locales = "ja-JP", buildFingerprint = "build")
        val SAVED = SyncState(changeSequence = 7, bootCount = 2, locales = "ja-JP", buildFingerprint = "build")
        val CHANGES = SyncScope(SyncMode.CHANGES, 7)
        val REVIEW = SyncScope(SyncMode.FULL_REVIEW, 0)

        fun id(
            packageName: String,
            profile: Long,
        ) = AppId(packageName, "$packageName.Main", profile)

        fun stamp(
            packageName: String,
            profile: Long = PERSONAL,
            time: Long = 1_000,
            fingerprint: String = "apk:1",
        ) = ActivityStamp(id(packageName, profile), time, fingerprint, isCurrentProfile = profile == PERSONAL)

        fun stored(
            packageName: String,
            profile: Long = PERSONAL,
            fingerprint: String? = "apk:1",
        ) = StoredApp(IndexedApp(id(packageName, profile), packageName, emptyList()), 1_000, fingerprint)

        fun installed(
            packageName: String,
            profile: Long = PERSONAL,
            fingerprint: String = "apk:1",
        ) = InstalledApp(AppEntry(id(packageName, profile), packageName), 1_000, fingerprint)

        fun <T> complete(vararg items: T) = ProfileRead(ProfileReadStatus.COMPLETE, items.toList())
    }
}
