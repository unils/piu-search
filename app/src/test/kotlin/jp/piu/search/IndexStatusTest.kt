// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.IndexStatusFixtures.MIXED_INDEX
import jp.piu.search.IndexStatusFixtures.MIXED_ISSUES
import jp.piu.search.IndexStatusFixtures.NOT_FOUND
import jp.piu.search.IndexStatusFixtures.PERSONAL
import jp.piu.search.IndexStatusFixtures.WORK
import jp.piu.search.IndexStatusFixtures.index
import jp.piu.search.IndexStatusFixtures.issue
import jp.piu.search.IndexStatusFixtures.report
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.data.IndexSyncStatus
import jp.piu.search.data.MaterialStage
import jp.piu.search.data.ProfileReadStatus
import jp.piu.search.data.SyncFailure
import jp.piu.search.data.SyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 「索引の状態」の中身（ui.md の「索引の状態」）を、IndexSync の公開の値から作る規則
class IndexStatusTest {
    @Test
    fun appsAreCountedByPackageAndSplitByProfile() {
        // 入口を 2 つ持つアプリも 1 個と数える
        val twoEntries =
            SearchIndex(
                listOf(
                    IndexedApp(AppId("com.example.two", "A", PERSONAL), "二つ A", emptyList()),
                    IndexedApp(AppId("com.example.two", "B", PERSONAL), "二つ B", emptyList()),
                    IndexedApp(AppId("com.example.two", "A", WORK), "二つ A", emptyList()),
                ),
            )
        val status = IndexStatus.of(twoEntries, emptyList(), null, IndexSyncStatus(), PERSONAL)
        assertEquals(listOf(ProfileRole.PERSONAL to 1, ProfileRole(1) to 1), status.apps)
        assertEquals(2, status.totalApps)
    }

    @Test
    fun personalIsAlwaysListedAndOtherProfilesAreNumberedBySerial() {
        val status =
            IndexStatus.of(
                index(Triple(30L, "a", "A"), Triple(20L, "b", "B")),
                emptyList(),
                null,
                IndexSyncStatus(lastResult = report(PERSONAL to ProfileReadStatus.COMPLETE, 20L to ProfileReadStatus.UNAVAILABLE)),
                PERSONAL,
            )
        assertEquals(listOf(ProfileRole.PERSONAL to 0, ProfileRole(1) to 1, ProfileRole(2) to 1), status.apps)
        assertEquals(listOf("personal", "work", "work2"), status.apps.map { it.first.reportName })
        assertEquals(
            listOf(
                ProfileLine(ProfileRole.PERSONAL, ProfileReadStatus.COMPLETE),
                ProfileLine(ProfileRole(1), ProfileReadStatus.UNAVAILABLE),
            ),
            status.profiles,
        )
    }

    @Test
    fun beforeAnySyncNothingIsClaimed() {
        val status = IndexStatus.of(SearchIndex.EMPTY, emptyList(), null, IndexSyncStatus(), PERSONAL)
        assertNull(status.outcome)
        assertNull(status.archived)
        assertNull(status.mode)
        assertFalse(status.genresChecked)
        assertTrue(status.profiles.isEmpty())
        assertEquals(listOf(ProfileRole.PERSONAL to 0), status.apps)
    }

    @Test
    fun outcomeFollowsTheLastSyncAndKeepsThePreviousResultOnFailure() {
        val previous = report(PERSONAL to ProfileReadStatus.COMPLETE, archived = 3)
        val failed =
            IndexStatus.of(
                MIXED_INDEX,
                emptyList(),
                1L,
                IndexSyncStatus(lastResult = previous, failure = SyncFailure.SYNC),
                PERSONAL,
            )
        assertEquals(SyncOutcome.FAILED, failed.outcome)
        assertEquals(3, failed.archived)
        assertEquals(1L, failed.lastSavedAt)
        val genre =
            IndexStatus.of(
                MIXED_INDEX,
                emptyList(),
                1L,
                IndexSyncStatus(lastResult = report(genreFailure = "java.lang.SecurityException")),
                PERSONAL,
            )
        assertEquals(SyncOutcome.PARTIAL, genre.outcome)
        assertEquals("java.lang.SecurityException", genre.genreFailure)
    }

    @Test
    fun issuesAreOrderedByCategoryThenLabel() {
        val status = IndexStatus.of(MIXED_INDEX, MIXED_ISSUES.reversed(), null, IndexSyncStatus(), PERSONAL)
        // 不具合の疑い → 一時的（読み直し待ち → 読み直し済み）→ 決定的
        assertEquals(
            listOf("com.example.work", "com.example.mail", "com.example.camera", "com.example.maps"),
            status.issues.map { it.issue.key.packageName },
        )
        assertEquals(listOf("仕事の予定", "メール", "カメラ", "地図"), status.issues.map { it.label })
        assertEquals(ProfileRole(1), status.issues.first().role)
        val sameCategory =
            IndexStatus.of(
                index(Triple(PERSONAL, "z", "あ"), Triple(PERSONAL, "a", "い")),
                listOf(issue("a", MaterialStage.LABEL, NOT_FOUND), issue("z", MaterialStage.LABEL, NOT_FOUND)),
                null,
                IndexSyncStatus(),
                PERSONAL,
            )
        assertEquals(listOf("あ", "い"), sameCategory.issues.map { it.label })
    }

    @Test
    fun categoriesComeFromTheCauseAndTheRetryMark() {
        val tally = IndexStatus.of(MIXED_INDEX, MIXED_ISSUES, null, IndexSyncStatus(), PERSONAL).counts
        assertEquals(IssueTally(bug = 1, transient = 2, retry = 1, deterministic = 1), tally)
        assertEquals(4, tally.total)
        // 理由が同じでも、KEYS の段は不具合の疑い
        val keys = IssueTally.of(listOf(issue("a", MaterialStage.KEYS, "java.io.IOException")))
        assertEquals(1, keys.bug)
    }

    @Test
    fun anIssueOfAnAppMissingFromTheIndexUsesThePackageName() {
        val status =
            IndexStatus.of(
                SearchIndex.EMPTY,
                listOf(issue("com.example.gone", MaterialStage.LABEL, NOT_FOUND)),
                null,
                IndexSyncStatus(),
                PERSONAL,
            )
        assertEquals("com.example.gone", status.issues.single().label)
    }
}
