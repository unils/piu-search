// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.IndexStatusFixtures.MIXED_APPS
import jp.piu.search.IndexStatusFixtures.MIXED_ISSUES
import jp.piu.search.IndexStatusFixtures.NOT_FOUND
import jp.piu.search.IndexStatusFixtures.PERSONAL
import jp.piu.search.IndexStatusFixtures.WORK
import jp.piu.search.IndexStatusFixtures.issue
import jp.piu.search.IndexStatusFixtures.pkg
import jp.piu.search.IndexStatusFixtures.snapshot
import jp.piu.search.IndexStatusFixtures.writer
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 「索引の状態」の中身（ui.md の「索引の状態」）を、索引の写しと書き手の状態から作る規則
class IndexStatusTest {
    @Test
    fun appsAreCountedByPackageAndSplitByProfile() {
        // 入口を 2 つ持つアプリも 1 個と数える
        val twoEntries =
            IndexSnapshot.restore(
                listOf(
                    pkg(PERSONAL, "com.example.two", "二つ", entries = listOf("A", "B")),
                    pkg(WORK, "com.example.two", "二つ", entries = listOf("A")),
                ),
                emptyMap(),
            )
        val status = IndexStatus.of(twoEntries, writer(), PERSONAL)
        assertEquals(listOf(ProfileRole.PERSONAL to 1, ProfileRole(1) to 1), status.apps)
        assertEquals(2, status.totalApps)
    }

    @Test
    fun personalIsAlwaysListedAndOtherProfilesAreNumberedBySerial() {
        val status =
            IndexStatus.of(
                snapshot(
                    listOf(Triple(30L, "a", "A"), Triple(20L, "b", "B")),
                    listings = mapOf(PERSONAL to ListingStatus.COMPLETE, 20L to ListingStatus.UNAVAILABLE),
                ),
                writer(),
                PERSONAL,
            )
        assertEquals(listOf(ProfileRole.PERSONAL to 0, ProfileRole(1) to 1, ProfileRole(2) to 1), status.apps)
        assertEquals(listOf("personal", "work", "work2"), status.apps.map { it.first.reportName })
        assertEquals(
            listOf(
                ProfileLine(ProfileRole.PERSONAL, ListingStatus.COMPLETE),
                ProfileLine(ProfileRole(1), ListingStatus.UNAVAILABLE),
            ),
            status.profiles,
        )
    }

    @Test
    fun beforeAnySyncOfThisProcessNothingIsClaimedEvenWithASavedSnapshot() {
        // 保存した写しの一覧の状態は前のプロセスの結果なので、同期が終わるまで結果としては出さない
        val saved = snapshot(MIXED_APPS, listings = mapOf(PERSONAL to ListingStatus.FAILED), archived = 1)
        val status = IndexStatus.of(saved, writer(synced = false), PERSONAL)
        assertNull(status.outcome)
        assertNull(status.archived)
        assertFalse(status.genresChecked)
        assertTrue(status.profiles.isEmpty())
        assertEquals(listOf(ProfileRole.PERSONAL to 4, ProfileRole(1) to 1), status.apps)
        val empty = IndexStatus.of(IndexSnapshot.EMPTY, writer(synced = false), PERSONAL)
        assertEquals(listOf(ProfileRole.PERSONAL to 0), empty.apps)
    }

    @Test
    fun outcomeFollowsTheLastSyncAndKeepsThePreviousResultOnFailure() {
        val previous = snapshot(MIXED_APPS, listings = mapOf(PERSONAL to ListingStatus.COMPLETE), archived = 3)
        val failed = IndexStatus.of(previous, writer(syncFailure = "java.lang.IllegalStateException", lastSavedAt = 1L), PERSONAL)
        assertEquals(LastSyncResult.FAILED, failed.outcome)
        assertEquals(3, failed.archived)
        assertEquals(1L, failed.lastSavedAt)
        val genre = IndexStatus.of(previous, writer(genreFailure = "java.lang.SecurityException"), PERSONAL)
        assertEquals(LastSyncResult.PARTIAL, genre.outcome)
        assertEquals("java.lang.SecurityException", genre.genreFailure)
        val profile = IndexStatus.of(snapshot(MIXED_APPS, listings = mapOf(WORK to ListingStatus.FAILED)), writer(), PERSONAL)
        assertEquals(LastSyncResult.PARTIAL, profile.outcome)
        assertEquals(LastSyncResult.ISSUES, IndexStatus.of(snapshot(MIXED_APPS, MIXED_ISSUES), writer(), PERSONAL).outcome)
        // 一時停止中は失敗ではない
        val paused = IndexStatus.of(snapshot(MIXED_APPS, listings = mapOf(WORK to ListingStatus.UNAVAILABLE)), writer(), PERSONAL)
        assertEquals(LastSyncResult.OK, paused.outcome)
    }

    @Test
    fun issuesAreOrderedByCategoryThenLabel() {
        val status = IndexStatus.of(snapshot(MIXED_APPS, MIXED_ISSUES.reversed()), writer(), PERSONAL)
        // 不具合の疑い → 一時的（読み直し待ち → 読み直し済み）→ 決定的
        assertEquals(
            listOf("com.example.work", "com.example.mail", "com.example.camera", "com.example.maps"),
            status.issues.map { it.issue.key.packageName },
        )
        assertEquals(listOf("仕事の予定", "メール", "カメラ", "地図"), status.issues.map { it.label })
        assertEquals(ProfileRole(1), status.issues.first().role)
        val sameCategory =
            IndexStatus.of(
                snapshot(
                    listOf(Triple(PERSONAL, "z", "あ"), Triple(PERSONAL, "a", "い")),
                    listOf(issue("a", Stage.LABEL, NOT_FOUND), issue("z", Stage.LABEL, NOT_FOUND)),
                ),
                writer(),
                PERSONAL,
            )
        assertEquals(listOf("あ", "い"), sameCategory.issues.map { it.label })
    }

    @Test
    fun categoriesAndTheRetryMarkAreCounted() {
        val tally = IndexStatus.of(snapshot(MIXED_APPS, MIXED_ISSUES), writer(), PERSONAL).counts
        assertEquals(IssueTally(bug = 1, transient = 2, retry = 1, deterministic = 1), tally)
        assertEquals(4, tally.total)
    }
}
