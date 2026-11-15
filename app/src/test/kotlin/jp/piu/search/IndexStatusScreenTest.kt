// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.IndexStatusFixtures.MIXED_INDEX
import jp.piu.search.IndexStatusFixtures.MIXED_ISSUES
import jp.piu.search.IndexStatusFixtures.PERSONAL
import jp.piu.search.IndexStatusFixtures.WORK
import jp.piu.search.IndexStatusFixtures.report
import jp.piu.search.data.IndexSyncStatus
import jp.piu.search.data.IssueCounts
import jp.piu.search.data.ProfileReadStatus.COMPLETE
import jp.piu.search.data.ProfileReadStatus.FAILED
import jp.piu.search.data.ProfileReadStatus.UNAVAILABLE
import jp.piu.search.data.SyncFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

// 「索引の状態」の画面（ui.md の「索引の状態」）。中身の作り方は IndexStatusTest、文の書式は IndexReportTest
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP")
class IndexStatusScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eachLastSyncIsDescribedAsDesigned() {
        val cases =
            listOf(
                IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE), failure = SyncFailure.SYNC) to "最後の更新: 更新できなかった",
                IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE, WORK to FAILED), failure = SyncFailure.PROFILE) to
                    "最後の更新: 一部を更新できなかった",
                IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE, genreFailure = "java.lang.SecurityException")) to
                    "最後の更新: 一部を更新できなかった",
                IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE).copy(issueCounts = IssueCounts(deterministic = 1))) to
                    "最後の更新: 更新できた・一部の情報を読めていない",
                IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE, WORK to UNAVAILABLE)) to "最後の更新: 更新できた",
                IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE)) to "最後の更新: 更新できた",
                IndexSyncStatus() to "最後の更新: まだ更新していない",
            )
        var status by mutableStateOf(IndexStatus())
        compose.setContent { PiuTheme { IndexStatusScreen(status, "2026/11/15 10:23", null, IndexStatusActions()) } }
        for ((sync, text) in cases) {
            compose.runOnIdle { status = IndexStatus.of(MIXED_INDEX, emptyList(), 1L, sync, PERSONAL) }
            compose.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun profilesAndGenresAreListed() {
        val sync =
            IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE, WORK to UNAVAILABLE, genreFailure = "java.lang.SecurityException"))
        show(IndexStatus.of(MIXED_INDEX, emptyList(), 1L, sync, PERSONAL))
        compose.onNodeWithText("個人用: 4 個のアプリ").assertExists()
        compose.onNodeWithText("仕事用: 1 個のアプリ").assertExists()
        compose.onNodeWithText("個人用: 読めた").assertExists()
        compose.onNodeWithText("仕事用: 一時停止中").assertExists()
        compose.onNodeWithText("判定できなかった（前のジャンルで検索している）").assertExists()
        compose.onNodeWithText("うちアーカイブ済み: なし").assertExists()
        compose.onNodeWithText("最後に更新した時刻: 2026/11/15 10:23").assertExists()
    }

    @Test
    fun zeroCountsAreStillShown() {
        show(
            IndexStatus.of(
                MIXED_INDEX,
                emptyList(),
                null,
                IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE, archived = 2)),
                PERSONAL,
            ),
            savedAt = null,
        )
        compose.onNodeWithText("Piu Search の不具合の疑い: なし").assertExists()
        compose.onNodeWithText("一時的に読めない: なし").assertExists()
        compose.onNodeWithText("読めないまま使っている: なし").assertExists()
        compose.onNodeWithText("判定できた").assertExists()
        // アーカイブ済みは件数だけ
        compose.onNodeWithText("うちアーカイブ済み 2 個").assertExists()
        compose.onNodeWithText("最後に更新した時刻: まだ更新していない").assertExists()
        compose.onAllNodesWithTag(INDEX_ISSUE_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun issueRowsAreOrderedAndWordedFromTheCauseAndRetryMark() {
        show(IndexStatus.of(MIXED_INDEX, MIXED_ISSUES, 1L, IndexSyncStatus(lastResult = report(PERSONAL to COMPLETE)), PERSONAL))
        compose.onNodeWithText("Piu Search の不具合の疑い: 1 件").assertExists()
        compose.onNodeWithText("一時的に読めない: 2 件（うち次の更新で読み直す 1 件）").assertExists()
        compose.onNodeWithText("読めないまま使っている: 1 件").assertExists()
        val rows = compose.onAllNodesWithTag(INDEX_ISSUE_ROW_TAG)
        rows.assertCountEquals(4)
        // 1 行は 1 つの項目として、名前・パッケージ名・中身・理由の順に読まれる
        assertEquals(
            listOf(
                "仕事の予定・仕事用",
                "com.example.work",
                "検索の語の作成・Piu Search の不具合の疑い。前の情報で検索している。新しく入れたアプリなら、名前の文字どおりでだけ出る",
                "理由: java.lang.IllegalArgumentException・初めて読めなかった日: 2026-11-14",
            ),
            texts(rows[0]),
        )
        assertEquals("提供元（署名）・一時的に読めない・次の更新で読み直す。提供元の名前では出ない", texts(rows[1])[2])
        assertEquals("アプリの情報・一時的に読めない・読み直しても読めなかった。前の情報で検索している。新しく入れたアプリなら、名前でだけ出る", texts(rows[2])[2])
        assertEquals("別の言語の名前・読めないまま使っている。その言語の名前では出ない", texts(rows[3])[2])
        assertEquals("地図", texts(rows[3])[0])
    }

    @Test
    fun reportDialogShowsExactlyWhatIsCopied() {
        var copied: String? = null
        var closed = false
        val report = "Piu Search index status\napp: 0.2.0 (5)"
        compose.setContent {
            PiuTheme {
                IndexStatusScreen(
                    IndexStatus(),
                    null,
                    report,
                    IndexStatusActions(onCopyReport = { copied = it }, onCloseReport = {
                        closed =
                            true
                    }),
                )
            }
        }
        compose
            .onNodeWithText(
                "アプリのパッケージ名と、端末の製造元・機種・OS の版が入る。仕事用のアプリのパッケージ名からは、勤め先が分かることがある。" +
                    "Piu Search はこれを送信しない。貼る前に確かめ、出したくない行は消してほしい",
            ).assertExists()
        assertEquals(listOf(report), texts(compose.onNodeWithTag(INDEX_REPORT_TEXT_TAG)))
        compose.onNodeWithTag(INDEX_REPORT_COPY_TAG).performClick()
        compose.runOnIdle { assertEquals(report, copied) }
        compose.onNodeWithTag(INDEX_REPORT_CLOSE_TAG).performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }

    @Test
    fun buttonsCallTheirActions() {
        var shown = 0
        var issues = 0
        compose.setContent {
            PiuTheme {
                IndexStatusScreen(
                    IndexStatus(),
                    null,
                    null,
                    IndexStatusActions(onShowReport = { shown++ }, onOpenIssues = { issues++ }),
                )
            }
        }
        compose.onNodeWithTag(INDEX_STATUS_REPORT_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(INDEX_STATUS_ISSUES_TAG).performScrollTo().performClick()
        compose.onNodeWithText("報告する前に、同じ報告がないか見てほしい").assertExists()
        compose.runOnIdle {
            assertEquals(1, shown)
            assertEquals(1, issues)
        }
    }

    private fun show(
        status: IndexStatus,
        savedAt: String? = "2026/11/15 10:23",
    ) {
        compose.setContent { PiuTheme { IndexStatusScreen(status, savedAt, null, IndexStatusActions()) } }
    }

    private fun texts(node: SemanticsNodeInteraction): List<String> =
        node
            .fetchSemanticsNode()
            .config
            .getOrElse(SemanticsProperties.Text) { emptyList() }
            .map { it.text }
}

// 「索引の状態」の Activity: 報告用の文をコピーしたときの知らせと、クリップボードの中身
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP")
class IndexStatusActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [28, 37])
    fun copyPutsTheShownTextOnTheClipboardAndToastsOnlyBeforeAndroid13() {
        ActivityScenario.launch(IndexStatusActivity::class.java).use {
            compose.onNodeWithTag(INDEX_STATUS_REPORT_TAG).performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MILLIS) { compose.onAllNodesWithTag(INDEX_REPORT_TEXT_TAG).fetchSemanticsNodes().isNotEmpty() }
            val shown =
                compose
                    .onNodeWithTag(INDEX_REPORT_TEXT_TAG)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text
            compose.onNodeWithTag(INDEX_REPORT_COPY_TAG).performClick()
            compose.waitForIdle()
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            assertEquals(shown, clip?.getItemAt(0)?.text?.toString())
            assertEquals("Piu Search index status", shown.lines().first())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                assertNull("OS が知らせるので自分では出さない", ShadowToast.getTextOfLatestToast())
            } else {
                assertEquals("コピーした", ShadowToast.getTextOfLatestToast())
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
