// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.IndexStatusFixtures.writer
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.ListingStatus.COMPLETE
import jp.piu.search.core.state.ListingStatus.FAILED
import jp.piu.search.core.state.ListingStatus.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import jp.piu.search.data.index.IndexStatus as IndexWriterStatus

// 検索画面の索引の知らせ（ui.md の「索引の知らせ」、architecture.md の「知らせ」）を出す・消す条件と、出す文の選び方
class IndexNoticeTest {
    private val failedSync = writer(syncFailure = "java.lang.IllegalStateException")

    @Test
    fun wholeSyncFailureShowsTheWholeListTextWithOrWithoutAnIndex() {
        assertEquals(IndexNotice.SYNC_FAILED, notice(failedSync, PERSONAL to COMPLETE))
        assertEquals(IndexNotice.NO_INDEX, notice(failedSync, PERSONAL to COMPLETE, hasIndex = false))
        // 前の結果がなくても（最初の同期が失敗しても）出す
        assertEquals(IndexNotice.NO_INDEX, notice(failedSync, hasIndex = false))
    }

    @Test
    fun nextFinishedSyncClearsIt() {
        // 書き手は次に終わった同期で同期全体の失敗を下ろす
        assertNull(notice(writer(), PERSONAL to COMPLETE, WORK to COMPLETE))
        assertNull(notice(IndexWriterStatus()))
    }

    @Test
    fun profileFailuresOfAnEarlierProcessAreNotShownBeforeTheFirstSync() {
        // 写しの一覧の状態は保存されている。このプロセスで同期が終わるまでは、前のプロセスの失敗を出さない
        assertNull(notice(writer(synced = false), PERSONAL to COMPLETE, WORK to FAILED))
        assertEquals(IndexNotice.WORK_FAILED, notice(writer(), PERSONAL to COMPLETE, WORK to FAILED))
    }

    @Test
    fun personalFailureIsNamedOnlyWhenThereIsAWorkProfile() {
        assertEquals(IndexNotice.PERSONAL_FAILED, notice(writer(), PERSONAL to FAILED, WORK to COMPLETE))
        assertEquals(IndexNotice.PERSONAL_FAILED, notice(writer(), PERSONAL to FAILED, WORK to UNAVAILABLE))
        // 「個人用」は仕事用があるときだけの言葉
        assertEquals(IndexNotice.SYNC_FAILED, notice(writer(), PERSONAL to FAILED))
    }

    @Test
    fun workFailureIsShownUnlessWorkAppsAreHidden() {
        val listings = arrayOf(PERSONAL to COMPLETE, WORK to FAILED)
        assertEquals(IndexNotice.WORK_FAILED, notice(writer(), *listings, workMode = WorkProfileMode.SWITCH))
        assertEquals(IndexNotice.WORK_FAILED, notice(writer(), *listings, workMode = WorkProfileMode.MIXED))
        assertNull(notice(writer(), *listings, workMode = WorkProfileMode.HIDDEN))
    }

    @Test
    fun bothProfilesFailingShowsTheWholeListText() {
        val both = arrayOf(PERSONAL to FAILED, WORK to FAILED)
        assertEquals(IndexNotice.SYNC_FAILED, notice(writer(), *both))
        assertEquals(IndexNotice.SYNC_FAILED, notice(writer(), *both, workMode = WorkProfileMode.HIDDEN))
        assertEquals(IndexNotice.NO_INDEX, notice(writer(), *both, hasIndex = false))
    }

    @Test
    fun wholeSyncFailureWinsOverAProfileFailure() {
        assertEquals(IndexNotice.SYNC_FAILED, notice(failedSync, PERSONAL to COMPLETE, WORK to FAILED))
    }

    @Test
    fun pausedWorkAndGenreFailureAreNotShown() {
        assertNull(notice(writer(), PERSONAL to COMPLETE, WORK to UNAVAILABLE))
        assertNull(notice(writer(genreFailure = "java.lang.SecurityException"), PERSONAL to COMPLETE))
    }

    @Test
    fun aSecondWorkProfileCountsAsWork() {
        assertEquals(IndexNotice.WORK_FAILED, notice(writer(), PERSONAL to COMPLETE, WORK to COMPLETE, WORK2 to FAILED))
    }

    private fun notice(
        status: IndexWriterStatus,
        vararg listings: Pair<Long, ListingStatus>,
        hasIndex: Boolean = true,
        workMode: WorkProfileMode = WorkProfileMode.SWITCH,
    ): IndexNotice? = IndexNotice.of(status, listings.toMap(), hasIndex, PERSONAL, workMode)

    private companion object {
        const val PERSONAL = 0L
        const val WORK = 10L
        const val WORK2 = 11L
    }
}
