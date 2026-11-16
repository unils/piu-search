// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.data.IndexSyncStatus
import jp.piu.search.data.IssueCounts
import jp.piu.search.data.ProfileReadStatus
import jp.piu.search.data.ProfileReadStatus.COMPLETE
import jp.piu.search.data.ProfileReadStatus.FAILED
import jp.piu.search.data.ProfileReadStatus.UNAVAILABLE
import jp.piu.search.data.SyncFailure
import jp.piu.search.data.SyncMode
import jp.piu.search.data.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 検索画面の索引の知らせ（ui.md の「索引の知らせ」、architecture.md の「知らせ」）を出す・消す条件と、出す文の選び方
class IndexNoticeTest {
    @Test
    fun wholeSyncFailureShowsTheWholeListTextWithOrWithoutAnIndex() {
        val failed = IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE), failure = SyncFailure.SYNC)
        assertEquals(IndexNotice.SYNC_FAILED, notice(failed))
        assertEquals(IndexNotice.NO_INDEX, notice(failed, hasIndex = false))
        // 前の結果がなくても（最初の同期が失敗しても）出す
        assertEquals(IndexNotice.NO_INDEX, notice(IndexSyncStatus(failure = SyncFailure.SYNC), hasIndex = false))
    }

    @Test
    fun nextFinishedSyncClearsIt() {
        assertNull(notice(IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE, WORK to COMPLETE))))
        assertNull(notice(IndexSyncStatus()))
    }

    @Test
    fun personalFailureIsNamedOnlyWhenThereIsAWorkProfile() {
        assertEquals(IndexNotice.PERSONAL_FAILED, notice(profileFailure(PERSONAL to FAILED, WORK to COMPLETE)))
        assertEquals(IndexNotice.PERSONAL_FAILED, notice(profileFailure(PERSONAL to FAILED, WORK to UNAVAILABLE)))
        // 「個人用」は仕事用があるときだけの言葉
        assertEquals(IndexNotice.SYNC_FAILED, notice(profileFailure(PERSONAL to FAILED)))
    }

    @Test
    fun workFailureIsShownUnlessWorkAppsAreHidden() {
        val status = profileFailure(PERSONAL to COMPLETE, WORK to FAILED)
        assertEquals(IndexNotice.WORK_FAILED, notice(status, workMode = WorkProfileMode.SWITCH))
        assertEquals(IndexNotice.WORK_FAILED, notice(status, workMode = WorkProfileMode.MIXED))
        assertNull(notice(status, workMode = WorkProfileMode.HIDDEN))
    }

    @Test
    fun bothProfilesFailingShowsTheWholeListText() {
        val both = profileFailure(PERSONAL to FAILED, WORK to FAILED)
        assertEquals(IndexNotice.SYNC_FAILED, notice(both))
        assertEquals(IndexNotice.SYNC_FAILED, notice(both, workMode = WorkProfileMode.HIDDEN))
        assertEquals(IndexNotice.NO_INDEX, notice(both, hasIndex = false))
    }

    @Test
    fun wholeSyncFailureWinsOverAProfileFailure() {
        val status = IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE, WORK to FAILED), failure = SyncFailure.SYNC)
        assertEquals(IndexNotice.SYNC_FAILED, notice(status))
    }

    @Test
    fun pausedWorkMissingInformationAndGenreFailureAreNotShown() {
        assertNull(notice(IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE, WORK to UNAVAILABLE))))
        val issues = report(PERSONAL to COMPLETE).copy(issueCounts = IssueCounts(deterministic = 3, suspectedBug = 1))
        assertNull(notice(IndexSyncStatus(lastResult = issues)))
        val genres = report(PERSONAL to COMPLETE).copy(genreFailure = "java.lang.SecurityException")
        assertNull(notice(IndexSyncStatus(lastResult = genres)))
    }

    @Test
    fun aSecondWorkProfileCountsAsWork() {
        assertEquals(IndexNotice.WORK_FAILED, notice(profileFailure(PERSONAL to COMPLETE, WORK to COMPLETE, WORK2 to FAILED)))
    }

    private fun notice(
        status: IndexSyncStatus,
        hasIndex: Boolean = true,
        workMode: WorkProfileMode = WorkProfileMode.SWITCH,
    ): IndexNotice? = IndexNotice.of(status, hasIndex, PERSONAL, workMode)

    private fun profileFailure(vararg results: Pair<Long, ProfileReadStatus>) =
        IndexSyncStatus(lastResult = report(*results), failure = SyncFailure.PROFILE)

    private companion object {
        const val PERSONAL = 0L
        const val WORK = 10L
        const val WORK2 = 11L

        fun report(vararg results: Pair<Long, ProfileReadStatus>): SyncReport =
            SyncReport(
                mode = SyncMode.CHANGES,
                rebuiltApps = 0,
                removedPackages = 0,
                removedProfiles = 0,
                analyzerLoadMillis = 0,
                totalMillis = 0,
                indexSize = 1,
                profileResults = results.toMap(),
            )
    }
}
