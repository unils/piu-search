// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.LocalDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// 学習の写しとデータベースが揃うこと。救済の知らせと取り消し、印、忘却（M4-02）
@RunWith(AndroidJUnit4::class)
class LearningRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = LocalDatabase.open(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val repository = LearningRepository({ database.learned }, executor)

    @After
    fun close() {
        executor.shutdown()
        database.close()
        context.deleteDatabase(LocalDatabase.FILE_NAME)
    }

    @Test
    fun rescueIsRecordedAndNoticedOnceAndUndoRestoresTheEarlierRecord() {
        val heard = ArrayList<LearnedMemory>()
        repository.addListener { heard += it }
        // 前に選択で 1 回記録した組が、救済にもなった
        repository.record(LearningDecision(OKANE, emptyList()), NOW) { "乗換案内" }
        val notices = repository.record(LearningDecision(null, listOf(OKANE)), NOW + 1) { "乗換案内" }
        repository.awaitIdle()

        assertEquals(listOf(RescueNotice(OKANE, "乗換案内", null, NOW + 1)), notices)
        assertEquals(notices, repository.takeNotices())
        assertEquals(emptyList<RescueNotice>(), repository.takeNotices())
        val record =
            onDb { database.learned.load() }
                .records
                .getValue(OKANE)
        assertEquals(2 to true, record.count to record.rescued)
        assertEquals(repository.memory, heard.last())

        repository.undo(notices.single())
        repository.awaitIdle()

        // 救済の前（選択 1 回）に戻る。データベースも同じ
        val restored =
            onDb { database.learned.load() }
                .records
                .getValue(OKANE)
        assertEquals(1 to false, restored.count to restored.rescued)
        assertEquals(repository.memory.records, onDb { database.learned.load() }.records)
    }

    @Test
    fun suppressRemoveAndForgetAreWrittenThrough() {
        repository.record(LearningDecision(MAPS_PAIR, listOf(OKANE)), NOW) { null }
        repository.suppress(LearnedPair(NormalizedText("ちず"), TRANSIT), NOW)
        repository.remove(MAPS_PAIR)
        repository.awaitIdle()
        assertEquals(
            setOf(OKANE, LearnedPair(NormalizedText("ちず"), TRANSIT)),
            onDb { database.learned.load() }
                .records.keys,
        )

        // 180 日たつと、印のない記録だけ消える
        repository.forgetExpired(NOW + LearningConfig.DEFAULT.forgetAfterMillis)
        repository.awaitIdle()
        val left =
            onDb { database.learned.load() }
                .records.values
                .single()
        assertTrue(left.suppressed)
    }

    @Test
    fun learningIsLoadedBeforeNewRecordsAreAdded() {
        onDb { database.learned.save(LearnedMemory.EMPTY, LearnedMemory.EMPTY.recorded(LearningDecision(MAPS_PAIR, emptyList()), NOW)) }
        // 読み込む前に記録しても、読み込みで上書きされない
        repository.record(LearningDecision(MAPS_PAIR, emptyList()), NOW + 1) { null }
        repository.awaitIdle()
        assertEquals(
            2,
            repository.memory.records
                .getValue(MAPS_PAIR)
                .count,
        )
        assertEquals(
            2,
            onDb { database.learned.load() }
                .records
                .getValue(MAPS_PAIR)
                .count,
        )
    }

    @Test
    fun exportClearAndImportRestoresTheLearning() {
        repository.record(LearningDecision(MAPS_PAIR, listOf(OKANE)), NOW) { null }
        repository.record(LearningDecision(MAPS_PAIR, emptyList()), NOW + 1) { null }
        repository.suppress(LearnedPair(NormalizedText("ちず"), TRANSIT), NOW + 2)
        repository.awaitIdle()
        val original = repository.memory.records

        val file = StringBuilder()
        var exported: Int? = -1
        repository.export(PROFILES, NOW + 3, write = { file.append(it) }, onDone = { exported = it })
        repository.awaitIdle()
        assertEquals(3, exported)

        // 全消去では、まだ出していない知らせも捨てる（取り消しで消した記録を戻さない）
        repository.clear()
        repository.awaitIdle()
        assertEquals(emptyMap<LearnedPair, LearnedRecord>(), repository.memory.records)
        assertEquals(emptyList<RescueNotice>(), repository.takeNotices())
        assertEquals(emptyMap<LearnedPair, LearnedRecord>(), onDb { database.learned.load() }.records)

        var imported: BackupReadResult? = null
        repository.import(PROFILES, NOW + 4, read = { file.toString() }, onDone = { imported = it })
        repository.awaitIdle()
        assertTrue(imported is BackupReadResult.Success)
        // 最後に使った時刻だけが、読み込んだ時刻にそろう
        val expected = original.mapValues { (_, record) -> record.copy(lastUsedAt = NOW + 4) }
        assertEquals(expected, repository.memory.records)
        assertEquals(expected, onDb { database.learned.load() }.records)
    }

    @Test
    fun importKeepsTheLargerCountAndKeepsMarks() {
        repository.record(LearningDecision(MAPS_PAIR, emptyList()), NOW) { null }
        repository.suppress(OKANE, NOW)
        repository.awaitIdle()
        val other =
            LearningBackup.encode(
                LearnedMemory.EMPTY
                    .recorded(LearningDecision(MAPS_PAIR, listOf(OKANE)), NOW + 5)
                    .recorded(LearningDecision(MAPS_PAIR, emptyList()), NOW + 6),
                PROFILES,
                NOW,
            )

        repository.import(PROFILES, NOW + 10, read = { other }, onDone = {})
        repository.awaitIdle()
        val records = onDb { database.learned.load() }.records
        assertEquals(2 to NOW + 10, records.getValue(MAPS_PAIR).let { it.count to it.lastUsedAt })
        assertTrue(records.getValue(OKANE).suppressed)
        assertEquals(repository.memory.records, records)

        // 同じファイルをもう一度読んでも回数は増えない
        repository.import(PROFILES, NOW + 11, read = { other }, onDone = {})
        repository.awaitIdle()
        assertEquals(2 to NOW + 11, onDb { database.learned.load() }.records.getValue(MAPS_PAIR).let { it.count to it.lastUsedAt })
    }

    @Test
    fun brokenOrNewerFilesChangeNothingAndAreReported() {
        repository.record(LearningDecision(MAPS_PAIR, listOf(OKANE)), NOW) { null }
        repository.awaitIdle()
        val before = repository.memory
        val beforeDb = onDb { database.learned.load() }.records
        val newer = """{"format": "${LearningBackup.FORMAT}", "version": 99, "records": []}"""
        val cases =
            listOf<Pair<() -> String?, BackupFailure>>(
                { "{\"format\": " } to BackupFailure.INVALID,
                { "{\"nested\":" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "}" } to BackupFailure.INVALID,
                { "\u0000\u0001binary" } to BackupFailure.INVALID,
                { newer } to BackupFailure.NEWER_VERSION,
                { null } to BackupFailure.TOO_LARGE,
                { throw IOException("読めない") } to BackupFailure.UNREADABLE,
                { throw SecurityException("許されない") } to BackupFailure.UNREADABLE,
            )
        for ((read, reason) in cases) {
            var result: BackupReadResult? = null
            repository.import(PROFILES, NOW, read = read, onDone = { result = it })
            repository.awaitIdle()
            assertEquals(BackupReadResult.Failure(reason), result)
            assertSame(before, repository.memory)
            assertEquals(beforeDb, onDb { database.learned.load() }.records)
        }
    }

    @Test
    fun exportReportsAFailedWrite() {
        repository.record(LearningDecision(MAPS_PAIR, emptyList()), NOW) { null }
        var exported: Int? = -1
        repository.export(PROFILES, NOW, write = { throw IOException("書けない") }, onDone = { exported = it })
        repository.awaitIdle()
        assertEquals(null, exported)
    }

    @Test
    fun importReportsSkippedRowsAndOnlyMergesAcceptedRows() {
        repository.record(LearningDecision(MAPS_PAIR, emptyList()), NOW) { null }
        repository.awaitIdle()
        val work = LearnedRecord(NormalizedText("きんたい"), TRANSIT.copy(profileSerial = 7), 2, NOW, rescued = true)
        val source = LearnedMemory(listOf(work, LearnedRecord(OKANE.query, TRANSIT, Int.MAX_VALUE, NOW, rescued = true)))
        val file = JSONObject(LearningBackup.encode(source, BackupProfiles(0, setOf(7)), NOW))
        val array = file.getJSONArray("records")
        array.put(JSONObject(array.getJSONObject(0).toString()).put("query", "あ".repeat(65)))
        var result: BackupReadResult? = null
        repository.import(PROFILES, NOW + 1, read = { file.toString() }, onDone = { result = it })
        repository.awaitIdle()
        val success = result as BackupReadResult.Success
        assertEquals(1, success.records.size)
        assertEquals(2, success.skippedCount)
        assertEquals(setOf(MAPS_PAIR, OKANE), repository.memory.records.keys)
        assertEquals(repository.memory.records, onDb { database.learned.load() }.records)

        repository.record(LearningDecision(OKANE, emptyList()), NOW + 2) { null }
        repository.awaitIdle()
        val record = onDb { database.learned.load() }.records.getValue(OKANE)
        assertEquals(Int.MAX_VALUE, record.count)
        assertEquals(NOW + 2, record.lastUsedAt)
    }

    @Test
    fun anImportWithEveryRowSkippedPreservesMemoryAndDatabase() {
        repository.record(LearningDecision(MAPS_PAIR, emptyList()), NOW) { null }
        repository.awaitIdle()
        val before = repository.memory
        val work = LearnedMemory(listOf(LearnedRecord(OKANE.query, TRANSIT.copy(profileSerial = 7), 2, NOW, rescued = true)))
        val file = LearningBackup.encode(work, BackupProfiles(0, setOf(7)), NOW)
        var result: BackupReadResult? = null
        repository.import(PROFILES, NOW + 1, read = { file }, onDone = { result = it })
        repository.awaitIdle()
        assertEquals(BackupReadResult.Success(emptyList(), skippedCount = 1), result)
        assertSame(before, repository.memory)
        assertEquals(before.records, onDb { database.learned.load() }.records)
    }

    @Test
    fun usingAnExistingLearnedKeyUpdatesTheDatabaseWithoutARescueNotice() {
        repository.record(LearningDecision(null, listOf(OKANE)), NOW) { null }
        repository.takeNotices()
        assertEquals(emptyList<RescueNotice>(), repository.record(LearningDecision(null, emptyList(), used = OKANE), NOW + 1) { null })
        repository.awaitIdle()
        assertEquals(emptyList<RescueNotice>(), repository.takeNotices())
        val record = onDb { database.learned.load() }.records.getValue(OKANE)
        assertEquals(1, record.count)
        assertEquals(NOW + 1, record.lastUsedAt)
    }

    // Room は画面のスレッドからの読み書きを拒む（Robolectric はテストを画面のスレッドで動かす）ので、記録のスレッドで読む
    private fun <T> onDb(block: () -> T): T = executor.submit(Callable { block() }).get()

    private companion object {
        const val NOW = 1_000L * LearningConfig.DAY_MILLIS
        val TRANSIT = AppId("io.example.transit", "Main", 0)
        val MAPS = AppId("io.example.maps", "Main", 0)
        val OKANE = LearnedPair(NormalizedText("おかね"), TRANSIT)
        val MAPS_PAIR = LearnedPair(NormalizedText("ちず"), MAPS)
        val PROFILES = BackupProfiles(own = 0, work = emptySet())
    }
}
