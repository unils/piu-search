// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.LocalDatabase
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

// Room の書き込みを SQLite のトリガーで失敗させ、成功の表示と行の削除を先に出さないことを確かめる。
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP")
class LearnedListFailureTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = context.appContainer

    @Before
    fun prepareLearning() {
        container.learning.record(LearningDecision(PAIR, emptyList()), NOW) { "アプリ" }
        container.learning.awaitIdle()
        sql("CREATE TRIGGER r1_reject_delete BEFORE DELETE ON learned BEGIN SELECT RAISE(ABORT, '保存できない'); END")
        sql("CREATE TRIGGER r1_reject_insert BEFORE INSERT ON learned BEGIN SELECT RAISE(ABORT, '保存できない'); END")
    }

    @After
    fun removeFailureTriggers() {
        sql("DROP TRIGGER IF EXISTS r1_reject_delete")
        sql("DROP TRIGGER IF EXISTS r1_reject_insert")
    }

    @Test
    fun failedDeleteShowsAMessageAndKeepsTheRow() {
        ActivityScenario.launch(LearnedListActivity::class.java).use {
            container.learning.awaitIdle()
            val before = container.learning.memory
            compose.onNodeWithContentDescription("「さがす」と io.example.app の組を消す").performClick()
            container.learning.awaitIdle()
            compose.onNodeWithText("変更を保存できませんでした。もう一度お試しください").assertExists()
            compose.onNodeWithContentDescription("「さがす」と io.example.app の組を消す").assertExists()
            assertSame(before, container.learning.memory)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun failedClearShowsAnEnglishMessageAndKeepsTheRow() {
        ActivityScenario.launch(LearnedListActivity::class.java).use {
            container.learning.awaitIdle()
            val before = container.learning.memory
            compose.onNodeWithTag(LEARNED_CLEAR_TAG).performClick()
            compose.onNodeWithTag(LEARNED_CLEAR_CONFIRM_TAG).performClick()
            container.learning.awaitIdle()
            compose.onNodeWithText("Could not save the change. Please try again.").assertExists()
            assertSame(before, container.learning.memory)
        }
    }

    @Test
    fun failedImportReportsSavingFailureInsteadOfImportedCount() {
        val uri = Uri.parse("content://io.example.files/learning.json")
        val file =
            LearningBackup.encode(
                LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(PAIR)), NOW),
                BackupProfiles(0, emptySet()),
                NOW,
            )
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(file.toByteArray()))
        ActivityScenario.launch(LearnedListActivity::class.java).use { scenario ->
            container.learning.awaitIdle()
            val before = container.learning.memory
            compose.onNodeWithTag(LEARNED_IMPORT_TAG).performClick()
            scenario.onActivity { activity ->
                val request = shadowOf(activity).nextStartedActivityForResult
                shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            }
            container.learning.awaitIdle()
            compose.onNodeWithText("読み込んだ学習を保存できませんでした。もう一度お試しください").assertExists()
            assertSame(before, container.learning.memory)
        }
    }

    private fun sql(statement: String) {
        context.openOrCreateDatabase(LocalDatabase.FILE_NAME, Context.MODE_PRIVATE, null).use { it.execSQL(statement) }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        val PAIR = LearnedPair(NormalizedText("さがす"), AppId("io.example.app", "Main", 0))
    }
}
