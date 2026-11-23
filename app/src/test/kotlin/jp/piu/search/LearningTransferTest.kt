// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.state.RecordsFailure
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.UserRecords
import jp.piu.search.core.text.NormalizedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

// 学習の書き出しと読み込みの手順。ファイルは列の外で読み書きし、記録の合わせは書き手に頼む（規則は RecordsRulesTest）
@RunWith(AndroidJUnit4::class)
class LearningTransferTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://io.example.files/secret-name.json")
    private val store = FakeStore()
    private val transfer = LearningTransfer(store, context.contentResolver, { PROFILES }, Dispatchers.Unconfined) { NOW }

    @Test
    fun exportWritesTheRecordsOfTheWriterAndCountsThem() {
        val out = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, out)
        store.records = UserRecords(LearnedMemory.EMPTY.recorded(LearningDecision(PAIR, emptyList()), NOW), LaunchHistory.EMPTY)
        assertEquals(BackupOutcome.Exported(1), runBlocking { transfer.export(uri) })
        val decoded = LearningBackup.decode(out.toString(Charsets.UTF_8.name()), PROFILES) as BackupReadResult.Success
        assertEquals(listOf(PAIR), decoded.records.map { it.pair })
    }

    @Test
    fun anUnreadableDatabaseOrAFailedWriteExportsNothing() {
        store.records = null
        assertEquals(BackupOutcome.Exported(null), runBlocking { transfer.export(uri) })
        store.records = UserRecords.EMPTY
        shadowOf(context.contentResolver).registerOutputStream(uri, FailingStream())
        ShadowLog.clear()
        assertEquals(BackupOutcome.Exported(null), runBlocking { transfer.export(uri) })
        assertLogsLeaveOutTheUri()
    }

    @Test
    fun brokenAndNewerFilesAreReportedWithoutAskingTheWriter() {
        read("{\"format\":\"other\"}")
        assertEquals(BackupOutcome.Imported(BackupReadResult.Failure(BackupFailure.INVALID)), runBlocking { transfer.import(uri) })
        read("{\"format\":\"jp.piu.search.learning\",\"version\":99,\"records\":[]}")
        assertEquals(BackupOutcome.Imported(BackupReadResult.Failure(BackupFailure.NEWER_VERSION)), runBlocking { transfer.import(uri) })
        assertTrue(store.imported.isEmpty())
    }

    @Test
    fun anUnopenableFileIsReportedAndLeavesTheUriOutOfTheLog() {
        ShadowLog.clear()
        // 何も登録していない URI は開けない
        val result = runBlocking { transfer.import(Uri.parse("content://io.example.files/missing-secret.json")) }
        assertEquals(BackupOutcome.Imported(BackupReadResult.Failure(BackupFailure.UNREADABLE)), result)
        assertLogsLeaveOutTheUri("missing-secret")
    }

    @Test
    fun skippedRowsOfTheFileAndOfRemovedProfilesAreAddedUp() {
        val work = LearnedRecord(NormalizedText("しごと"), AppId("io.example.work", "Main", 10), 1, NOW, rescued = true, suppressed = false)
        val memory =
            LearnedMemory(
                listOf(
                    LearnedMemory.EMPTY
                        .recorded(LearningDecision(PAIR, emptyList()), NOW)
                        .records
                        .getValue(PAIR),
                    work,
                ),
            )
        // 仕事用の行き先が決まらない 1 行はファイルの中で捨てる（PROFILES に仕事用がない）
        read(LearningBackup.encode(memory, BackupProfiles(0, setOf(10)), NOW))
        store.result = RecordsResult.Success(skipped = 0)
        val imported = runBlocking { transfer.import(uri) }
        assertEquals(1, imported.accepted)
        assertEquals(1, (imported.result as BackupReadResult.Success).skippedCount)

        // 書き手が、消えたプロファイルのために受けなかった 1 件
        read(LearningBackup.encode(memory, BackupProfiles(0, setOf(10)), NOW))
        store.result = RecordsResult.Success(skipped = 1)
        val removed = runBlocking { transfer.import(uri) }
        assertEquals(0, removed.accepted)
        assertEquals(2, (removed.result as BackupReadResult.Success).skippedCount)
    }

    @Test
    fun failuresOfTheWriterAreReportedAsDatabaseFailures() {
        val file = LearningBackup.encode(LearnedMemory.EMPTY.recorded(LearningDecision(PAIR, emptyList()), NOW), PROFILES, NOW)
        read(file)
        store.result = RecordsResult.Failure(RecordsFailure.NOT_LOADED)
        assertEquals(BackupOutcome.Imported(BackupReadResult.Failure(BackupFailure.DATABASE_READ)), runBlocking { transfer.import(uri) })
        read(file)
        store.result = RecordsResult.Failure(RecordsFailure.WRITE_FAILED)
        assertEquals(BackupOutcome.Imported(BackupReadResult.Failure(BackupFailure.DATABASE_WRITE)), runBlocking { transfer.import(uri) })
    }

    private fun read(text: String) {
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
    }

    private fun assertLogsLeaveOutTheUri(name: String = "secret-name") {
        val logs = ShadowLog.getLogs().joinToString("\n") { it.msg }
        assertTrue(logs, logs.isNotEmpty())
        assertFalse(logs, name in logs || "content://" in logs)
    }

    private class FailingStream : OutputStream() {
        override fun write(b: Int): Unit = throw IOException("secret-name を書けない")
    }

    private class FakeStore : LearningStore {
        var records: UserRecords? = UserRecords.EMPTY
        var result: RecordsResult = RecordsResult.Success()
        val imported = ArrayList<List<LearnedRecord>>()

        override suspend fun <T> export(write: suspend (UserRecords) -> T): T? = records?.let { write(it) }

        override suspend fun import(
            at: Long,
            records: List<LearnedRecord>,
        ): RecordsResult {
            imported += records
            return result
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        val PAIR = LearnedPair(NormalizedText("さがす"), AppId("io.example.app", "Main", 0))
        val PROFILES = BackupProfiles(own = 0, work = emptySet())
    }
}
