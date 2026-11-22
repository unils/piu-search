// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.IndexStatusFixtures.MIXED_APPS
import jp.piu.search.IndexStatusFixtures.MIXED_ISSUES
import jp.piu.search.IndexStatusFixtures.NOT_FOUND
import jp.piu.search.IndexStatusFixtures.PERSONAL
import jp.piu.search.IndexStatusFixtures.WORK
import jp.piu.search.IndexStatusFixtures.issue
import jp.piu.search.IndexStatusFixtures.snapshot
import jp.piu.search.IndexStatusFixtures.writer
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 報告用の文（ui.md の「報告用の文」、PRIVACY.md の「報告用の文」）の書式と、入れないもの
class IndexReportTest {
    private val device = DeviceInfo("0.2.0", 5, "15", 35, "Maker", "Phone 1", "maker/phone/phone:15/AP1/1:user/release-keys", "ja-JP,en-US")

    // 2026-11-15T10:23:45Z。文には日だけが入る
    private val savedAt = 1_794_738_225_000L

    @Test
    fun wholeReportHasTheFixedFormat() {
        val status =
            IndexStatus.of(
                snapshot(
                    MIXED_APPS,
                    MIXED_ISSUES,
                    mapOf(PERSONAL to ListingStatus.COMPLETE, WORK to ListingStatus.UNAVAILABLE),
                    archived = 3,
                ),
                writer(lastSavedAt = savedAt),
                PERSONAL,
            )
        val facts =
            mapOf(
                PackageKey(PERSONAL, "com.example.maps") to PackageFacts(system = false, apex = false, splits = 0, key = true),
                PackageKey(PERSONAL, "com.example.mail") to PackageFacts(system = true, apex = true, splits = 0, key = true),
                PackageKey(WORK, "com.example.work") to PackageFacts(system = false, apex = false, splits = 2, key = true),
            )
        assertEquals(
            """
            Piu Search index status
            app: 0.2.0 (5)
            android: 15 (API 35)
            device: Maker Phone 1
            build: maker/phone/phone:15/AP1/1:user/release-keys
            locales: ja-JP,en-US
            index: personal=4 work=1 archived=3
            last saved: 2026-11-15
            last sync: result=ISSUES
            profiles: personal=COMPLETE work=UNAVAILABLE
            genres: OK
            issues: transient=2 retry=1 deterministic=1 bug=1
            - BUG KEYS work com.example.work cause=java.lang.IllegalArgumentException system=N apex=N splits=2 key=Y input=label:len42:kanji,kana ver=5 since=2026-11-14 last=2026-11-15
                at a.b.c(SourceFile:12)
                at a.b.d(SourceFile:40)
            - TRANSIENT SIGNER personal com.example.mail cause=java.io.IOException retry=Y system=Y apex=Y splits=0 key=Y since=2026-11-14 last=2026-11-15
            - TRANSIENT ENTRY personal com.example.camera cause=VANISHED retry=N system=? apex=? splits=? key=? since=2026-11-14 last=2026-11-15
            - DETERMINISTIC LABEL personal com.example.maps cause=$NOT_FOUND system=N apex=N splits=0 key=Y since=2026-11-14 last=2026-11-15
            """.trimIndent(),
            IndexReport.text(status, device, facts),
        )
    }

    @Test
    fun labelsTimesAndMessagesAreLeftOut() {
        val status =
            IndexStatus.of(
                snapshot(MIXED_APPS, MIXED_ISSUES, mapOf(PERSONAL to ListingStatus.COMPLETE)),
                writer(lastSavedAt = savedAt),
                PERSONAL,
            )
        val text = IndexReport.text(status, device, emptyMap())
        for (label in listOf("地図", "メール", "カメラ", "時計", "仕事の予定")) assertFalse(label, label in text)
        assertFalse(
            "時刻を入れない",
            Regex("""\d{1,2}:\d{2}""").containsMatchIn(text.lines().filterNot { it.startsWith("    at ") }.joinToString("\n")),
        )
        assertFalse("時間帯を入れない", "+09" in text || "JST" in text)
        assertFalse("パスを入れない", "/data/" in text || "/apex/" in text)
    }

    @Test
    fun beforeAnySyncTheLinesSayNone() {
        val text = IndexReport.text(IndexStatus.of(IndexSnapshot.EMPTY, writer(synced = false), PERSONAL), device, emptyMap())
        assertTrue(text, "index: personal=0 archived=?" in text)
        assertTrue(text, "last saved: none" in text)
        assertTrue(text, "last sync: none" in text)
        assertTrue(text, "profiles: none" in text)
        assertTrue(text, "genres: none" in text)
        assertTrue(text, text.endsWith("issues: transient=0 retry=0 deterministic=0 bug=0"))
    }

    @Test
    fun failedSyncAndGenreFailureAreWritten() {
        val text =
            IndexReport.text(
                IndexStatus.of(
                    snapshot(MIXED_APPS, listings = mapOf(PERSONAL to ListingStatus.FAILED)),
                    writer(
                        syncFailure = "java.lang.IllegalStateException",
                        genreFailure = "java.lang.SecurityException",
                        lastSavedAt = savedAt,
                    ),
                    PERSONAL,
                ),
                device,
                emptyMap(),
            )
        assertTrue(text, "last sync: result=FAILED" in text)
        assertTrue(text, "profiles: personal=FAILED" in text)
        assertTrue(text, "genres: FAILED cause=java.lang.SecurityException" in text)
        val first =
            IndexReport.text(
                IndexStatus.of(IndexSnapshot.EMPTY, writer(syncFailure = "java.lang.IllegalStateException"), PERSONAL),
                device,
                emptyMap(),
            )
        assertTrue(first, "last sync: result=FAILED" in first)
    }

    @Test
    fun framesAndVersionAreWrittenOnlyForSuspectedBugs() {
        // 不具合の疑いでない行は、フレームと input を持っていても書かない
        val issues =
            listOf(
                issue("a", Stage.LABEL, NOT_FOUND, frames = "x.y(SourceFile:1)", inputSummary = "label:len1:kana"),
                issue(
                    "b",
                    Stage.ENTRY,
                    "java.lang.IllegalStateException",
                    frames = "p.q(SourceFile:9)",
                    inputSummary = "label:len1:kana",
                    versionCode = 4,
                ),
            )
        val text = IndexReport.text(IndexStatus.of(snapshot(issues = issues), writer(), PERSONAL), device, emptyMap())
        val lines = text.lines()
        val bug = lines.single { it.startsWith("- BUG ENTRY") }
        assertTrue(bug, " ver=4 " in bug)
        assertFalse("input は KEYS の行だけ: $bug", "input=" in bug)
        assertEquals("    at p.q(SourceFile:9)", lines[lines.indexOf(bug) + 1])
        val degraded = lines.single { it.startsWith("- DETERMINISTIC LABEL") }
        assertFalse(degraded, "ver=" in degraded || "input=" in degraded)
        assertFalse(text, "x.y(SourceFile:1)" in text)
    }

    @Test
    fun moreThan200IssuesAreCut() {
        val issues = (1..205).map { issue("com.example.p%03d".format(it), Stage.LABEL, NOT_FOUND) }
        val text = IndexReport.text(IndexStatus.of(snapshot(issues = issues), writer(), PERSONAL), device, emptyMap())
        assertEquals(200, text.lines().count { it.startsWith("- ") })
        assertTrue(text, text.endsWith("... 5 more"))
        assertTrue(text, "issues: transient=0 retry=0 deterministic=205 bug=0" in text)
    }

    @Test
    fun datesAreInUtc() {
        // 日本時間では 11 月 16 日の朝 8 時だが、UTC ではまだ 15 日
        assertEquals("2026-11-15", IndexReport.utcDate(1_794_783_600_000L))
    }
}
