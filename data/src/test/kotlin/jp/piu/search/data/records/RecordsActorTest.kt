// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.state.RecordsFailure
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.UserRecords
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

// 記録の書き手を、本物の Room（Robolectric）の上で確かめる。故障は、保存先の偽物（DB に触れる前に失敗）と、
// SQLite のトリガー（トランザクションの途中で失敗）の 2 通りで起こす
// runCurrent（仮想の時計を進めずに、今動ける coroutine を動かす）は 1.9 で実験的な API
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RecordsActorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val probe = FakeProbe()
    private val opened = ArrayList<RecordsDatabase>()
    private val database by lazy { RecordsDatabase.open(context, FILE, allowMainThread = true, ownSerial = { OWN }).also(opened::add) }

    @After
    fun close() {
        opened.forEach(RecordsDatabase::close)
        context.deleteDatabase(FILE)
    }

    @Test
    fun aFailedWriteChangesNeitherTheSnapshotNorTheDatabase() =
        runTest {
            database.write(seedWrite())
            val actor = actor(database)
            actor.refresh().await()
            val before = actor.state.value
            // 学習の行を書いた後、起動の記録の行を書くところで失敗させる（アプリを開く頼みは 1 つのトランザクション）
            trigger("CREATE TRIGGER fail_launch BEFORE INSERT ON launch_stat BEGIN SELECT RAISE(ABORT, '書き込みの失敗'); END")
            val open = RecordsRequest.Open(LearningDecision(pair("あたらしい", OWN_APP), emptyList()), OWN_APP, at = 2000)
            assertEquals(RecordsResult.Failure(RecordsFailure.WRITE_FAILED), actor.request(open))
            assertEquals(before, actor.state.value)
            assertEquals(before.records, database.load().records)

            trigger("DROP TRIGGER fail_launch")
            assertTrue(actor.request(open) is RecordsResult.Success)
            val after = checkNotNull(actor.state.value.records)
            assertTrue(pair("あたらしい", OWN_APP) in after.learned.records)
            assertEquals(LaunchRecord(3, 2000), after.launches.records[OWN_APP])
            assertEquals(after, database.load().records)
        }

    @Test
    fun requestsDuringALoadFailureAreRefusedWithoutWritingAndTheNextOneReloads() =
        runTest {
            database.write(seedWrite())
            val storage = FaultyStorage(database).apply { failLoads = 2 }
            val actor = actor(storage)
            runCurrent()
            assertEquals("java.io.IOException", actor.state.value.loadFailure)
            assertNull(actor.state.value.records)
            // 読み直してもまた失敗する。空の写しから計算して保存済みの記録を上書きしない
            val suppress = RecordsRequest.Suppress(pair("よみ", OWN_APP), at = 3000)
            assertEquals(RecordsResult.Failure(RecordsFailure.NOT_LOADED), actor.request(suppress))
            assertTrue(storage.writes.isEmpty())
            assertEquals(2, storage.loads)

            // 次の頼みで読み直し、保存済みの記録の上に積む
            assertTrue(actor.request(suppress) is RecordsResult.Success)
            val records = checkNotNull(actor.state.value.records)
            assertEquals(true, records.learned.records[pair("よみ", OWN_APP)]?.suppressed)
            assertEquals(setOf(OWN_APP, WORK_APP), records.launches.records.keys)
            assertEquals(records, database.load().records)
        }

    @Test
    fun aRefreshReloadsAfterALoadFailure() =
        runTest {
            database.write(seedWrite())
            val actor = actor(FaultyStorage(database).apply { failLoads = 1 })
            runCurrent()
            assertTrue(actor.state.value.loadFailure != null)
            actor.refresh().await()
            assertEquals(database.load().records, actor.state.value.records)
        }

    @Test
    fun requestsSentWhileLoadingWaitForTheLoad() =
        runTest {
            database.write(seedWrite())
            val actor = actor(database)
            // 書き手が読み込む前に頼む。読み込みを待ってから積むので、保存済みの記録は消えない
            val result = actor.submit(RecordsRequest.Open(LearningDecision(null, emptyList()), OTHER_APP, at = 5))
            assertTrue(result.await() is RecordsResult.Success)
            assertEquals(
                setOf(OWN_APP, WORK_APP, OTHER_APP),
                database
                    .load()
                    .records.launches.records.keys,
            )
        }

    @Test
    fun lateRecordsAndStaleUndoForARemovedProfileAreRefused() =
        runTest {
            database.write(seedWrite())
            val actor = actor(database)
            val rescue = RecordsRequest.Open(LearningDecision(null, listOf(pair("しごと", WORK_APP))), WORK_APP, at = 2000)
            val notice = (actor.request(rescue) as RecordsResult.Success).notices.single()
            assertTrue(actor.state.value.canUndo(notice.id))

            // 仕事用プロファイルを消した。知らせを受けた書き手が、その場で問い合わせて確かめる
            probe.serials = setOf(OWN)
            actor.refresh().await()
            assertEquals(
                setOf(OWN),
                actor.state.value.records
                    ?.serials,
            )
            assertTrue(!actor.state.value.canUndo(notice.id))
            assertEquals(setOf(OWN), database.load().storedSerials)

            // 削除の後に届いた遅い記録と、削除の前に作った取り消しは、何も書かない
            assertEquals(RecordsResult.Success(), actor.request(rescue))
            assertEquals(RecordsResult.Success(), actor.request(RecordsRequest.Undo(notice.id)))
            assertEquals(setOf(OWN), database.load().storedSerials)
            assertEquals(
                setOf(OWN),
                actor.state.value.records
                    ?.serials,
            )
        }

    @Test
    fun aFailedPruneIsRetriedByTheNextRequestAndByRefreshWithTheSameProfiles() =
        runTest {
            database.write(seedWrite())
            val actor = actor(database)
            actor.refresh().await()
            trigger(
                "CREATE TRIGGER fail_prune BEFORE DELETE ON learned WHEN OLD.profile_serial = $WORK " +
                    "BEGIN SELECT RAISE(ABORT, '整理の失敗'); END",
            )
            probe.serials = setOf(OWN)
            actor.refresh().await()
            // 画面には、整理を待たずに削除を効かせる。DB には行が残り、整理は終わっていない
            assertEquals(
                setOf(OWN),
                actor.state.value.records
                    ?.serials,
            )
            assertEquals(setOf(WORK), actor.state.value.pendingPrune)
            assertEquals(setOf(OWN, WORK), database.load().storedSerials)

            // 集合は変わらないまま。頼みのたびに試す（まだ失敗する間も、頼みそのものは書ける）
            assertTrue(actor.request(RecordsRequest.Suppress(pair("よみ", OWN_APP), at = 3000)) is RecordsResult.Success)
            assertEquals(setOf(WORK), actor.state.value.pendingPrune)
            trigger("DROP TRIGGER fail_prune")
            assertTrue(actor.request(RecordsRequest.Forget(0)) is RecordsResult.Success)
            assertTrue(
                actor.state.value.pendingPrune
                    .isEmpty(),
            )
            assertEquals(setOf(OWN), database.load().storedSerials)
        }

    @Test
    fun aFailedPruneIsRetriedOnReturningToTheForeground() =
        runTest {
            database.write(seedWrite())
            val storage = FaultyStorage(database)
            val actor = actor(storage)
            runCurrent()
            probe.serials = setOf(OWN)
            storage.failWrites = 1
            actor.refresh().await()
            assertEquals(setOf(WORK), actor.state.value.pendingPrune)
            actor.refresh().await()
            assertTrue(
                actor.state.value.pendingPrune
                    .isEmpty(),
            )
            assertEquals(setOf(OWN), database.load().storedSerials)
        }

    @Test
    fun requestsFromOneSenderAreAppliedInTheOrderTheyWereSent() =
        runTest {
            val actor = actor(database)
            val random = Random(7)
            val pairs = (0 until 20).map { pair("ご$it", OWN_APP) }
            val last = HashMap<Int, Boolean>()
            // 待たずに続けて入れる。抑制と削除は順序で結果が変わる
            val replies =
                (0 until 200).map {
                    val i = random.nextInt(pairs.size)
                    val suppress = random.nextBoolean()
                    last[i] = suppress
                    actor.submit(if (suppress) RecordsRequest.Suppress(pairs[i], at = it.toLong()) else RecordsRequest.Remove(pairs[i]))
                }
            replies.awaitAll()
            val stored =
                database
                    .load()
                    .records.learned.records
            last.forEach { (i, suppress) -> assertEquals("組 $i", suppress, pairs[i] in stored) }
            assertEquals(
                stored,
                actor.state.value.records
                    ?.learned
                    ?.records,
            )
        }

    @Test
    fun manyConcurrentSendersAreProcessedOneByOneAndTheSnapshotMatchesTheDatabase() {
        // 本物のスレッドで、書き手も多くの送り手も並行に動かす
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val actor = RecordsActor(scope, database, probe, Dispatchers.IO)
            val apps = (0 until 5).map { AppId("io.example.app$it", "Main", if (it == 0) WORK else OWN) }
            runBlocking(Dispatchers.Default) {
                val senders =
                    (0 until 8).map { sender ->
                        async(Dispatchers.Default) {
                            (0 until 50).map { i ->
                                val app = apps[(sender + i) % apps.size]
                                actor.submit(
                                    RecordsRequest.Open(LearningDecision(pair("く$sender", app), emptyList()), app, at = i.toLong()),
                                )
                            }
                        }
                    }
                withTimeout(TIMEOUT) { senders.awaitAll().flatten().awaitAll() }.forEach { assertTrue(it is RecordsResult.Success) }
            }
            val stored = database.load().records
            // 加算を取りこぼさない: 起動は 8 × 50 回
            assertEquals(
                400,
                stored.launches.records.values
                    .sumOf { it.count },
            )
            assertEquals(stored, actor.state.value.records)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun exportAndImportReadAndWriteFilesOutsideTheQueue() =
        runTest {
            database.write(seedWrite())
            val actor = actor(database)
            val writing = CompletableDeferred<Unit>()
            val exported = CompletableDeferred<UserRecords>()
            val export =
                launch {
                    actor.export { records ->
                        // 遅い保存先への出力
                        writing.await()
                        exported.complete(records)
                    }
                }
            runCurrent()
            // 出力を待つ間も、ほかの頼みは進む。書き出す写しは、頼んだ時点の確定した写し
            assertTrue(actor.request(RecordsRequest.Suppress(pair("よみ", OWN_APP), at = 3000)) is RecordsResult.Success)
            writing.complete(Unit)
            export.join()
            assertEquals(
                false,
                exported
                    .await()
                    .learned.records[pair("よみ", OWN_APP)]
                    ?.suppressed,
            )

            val reading = CompletableDeferred<Unit>()
            val import =
                async {
                    actor.import(at = 4000) {
                        reading.await()
                        listOf(record("よみこみ", OWN_APP, count = 3), record("よみこみ", WORK_APP))
                    }
                }
            runCurrent()
            assertTrue(actor.request(RecordsRequest.Remove(pair("よみ", WORK_APP))) is RecordsResult.Success)
            reading.complete(Unit)
            assertTrue(import.await() is RecordsResult.Success)
            val records =
                database
                    .load()
                    .records.learned.records
            assertEquals(3, records[pair("よみこみ", OWN_APP)]?.count)
            assertTrue(pair("よみ", WORK_APP) !in records)
            assertEquals(
                records,
                actor.state.value.records
                    ?.learned
                    ?.records,
            )
        }

    @Test
    fun anExportDuringALoadFailureDoesNotWrite() =
        runTest {
            val actor = actor(FaultyStorage(database).apply { failLoads = 2 })
            assertNull(actor.export { error("読めない間は書き出さない") })
        }

    @Test
    fun anAcceptedRequestIsFinishedEvenIfTheSenderStopsWaiting() {
        val storage = BlockingStorage(database) { it.launchPut.isNotEmpty() }
        threaded(storage) { actor, _ ->
            runBlocking {
                val sender =
                    launch(Dispatchers.Default) { actor.request(RecordsRequest.Open(LearningDecision(null, emptyList()), OWN_APP, at = 1)) }
                // 書き込みの最中に、送り手だけが待つのをやめる
                storage.awaitEntered()
                sender.cancel()
                storage.release.countDown()
                withTimeout(TIMEOUT) { actor.refresh().await() }
            }
            assertEquals(
                LaunchRecord(1, 1),
                database
                    .load()
                    .records.launches.records[OWN_APP],
            )
        }
    }

    @Test
    fun stoppingTheWriterFinishesTheStartedRequestAndCancelsTheQueuedOnes() {
        val storage = BlockingStorage(database) { it.launchPut.isNotEmpty() }
        threaded(storage) { actor, scope ->
            val started = actor.submit(RecordsRequest.Open(LearningDecision(null, emptyList()), OWN_APP, at = 1))
            storage.awaitEntered()
            val queued = actor.submit(RecordsRequest.Open(LearningDecision(null, emptyList()), OTHER_APP, at = 2))
            scope.cancel()
            storage.release.countDown()
            runBlocking {
                // 始めたトランザクションは結果まで処理する
                assertTrue(withTimeout(TIMEOUT) { started.await() } is RecordsResult.Success)
                withTimeout(TIMEOUT) { scope.coroutineContext[Job]!!.join() }
            }
            assertTrue(queued.isCancelled)
            assertEquals(
                setOf(OWN_APP),
                database
                    .load()
                    .records.launches.records.keys,
            )
        }
    }

    @Test
    fun aRemovalIsPublishedBeforeThePruneIsWritten() {
        database.write(seedWrite())
        val storage = BlockingStorage(database) { it.deleteSerials.isNotEmpty() }
        threaded(storage) { actor, _ ->
            runBlocking { withTimeout(TIMEOUT) { actor.refresh().await() } }
            probe.serials = setOf(OWN)
            val refresh = actor.refresh()
            storage.awaitEntered()
            // 整理の書き込みを止めている間も、画面には削除が効いている
            assertEquals(
                setOf(OWN),
                actor.state.value.records
                    ?.serials,
            )
            storage.failBlocked = true
            storage.release.countDown()
            runBlocking { withTimeout(TIMEOUT) { refresh.await() } }
            assertEquals(
                setOf(OWN),
                actor.state.value.records
                    ?.serials,
            )
            assertEquals(setOf(WORK), actor.state.value.pendingPrune)
        }
    }

    @Test
    fun aWriteInProgressIsNotPublishedAndAFailedOneIsNeverPublished() {
        database.write(seedWrite())
        val storage = BlockingStorage(database) { it.learnedPut.isNotEmpty() }
        threaded(storage) { actor, _ ->
            runBlocking { withTimeout(TIMEOUT) { actor.refresh().await() } }
            val before = actor.state.value
            val reply = actor.submit(RecordsRequest.Suppress(pair("よみ", OWN_APP), at = 3000))
            storage.awaitEntered()
            assertEquals(before, actor.state.value)
            storage.failBlocked = true
            storage.release.countDown()
            assertEquals(RecordsResult.Failure(RecordsFailure.WRITE_FAILED), runBlocking { withTimeout(TIMEOUT) { reply.await() } })
            assertEquals(before, actor.state.value)
        }
    }

    @Test
    fun theFirstLoadPrunesProfilesThatNoLongerExistWithoutWaitingForARequest() =
        runTest {
            database.write(seedWrite())
            probe.serials = setOf(OWN)
            val actor = actor(database)
            runCurrent()
            assertEquals(
                setOf(OWN),
                actor.state.value.records
                    ?.serials,
            )
            assertEquals(setOf(OWN), database.load().storedSerials)
        }

    @Test
    fun theProductWriterOpensTheRecordsFileOfTheEarlierVersion() {
        assertEquals("piu.db", RecordsActor.FILE_NAME)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val actor = RecordsActor.open(context, scope, probe)
            runBlocking { withTimeout(TIMEOUT) { actor.refresh().await() } }
            assertTrue(context.getDatabasePath(RecordsActor.FILE_NAME).exists())
        } finally {
            scope.cancel()
            runBlocking { withTimeout(TIMEOUT) { scope.coroutineContext[Job]!!.join() } }
            context.deleteDatabase(RecordsActor.FILE_NAME)
        }
    }

    @Test
    fun onlyOneWriterCanBeOpenedForTheSameDatabase() {
        val name = "m802a-records-single-writer.db"
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val actor = RecordsActor.open(context, name, first, probe)
            runBlocking {
                withTimeout(
                    TIMEOUT,
                ) { actor.request(RecordsRequest.Open(LearningDecision(null, emptyList()), OWN_APP, at = 1)) }
            }
            // 2 つ目の書き手は、それぞれの写しから書いて増分を失うので作らせない。同じものを渡す
            val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            assertThrows(IllegalStateException::class.java) { RecordsActor.open(context, name, second, probe) }
            second.cancel()
            // 止めれば、同じ名前でまた作れる（前の書き手の DB は閉じている）
            first.cancel()
            runBlocking { withTimeout(TIMEOUT) { first.coroutineContext[Job]!!.join() } }
            val again = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopened = RecordsActor.open(context, name, again, probe)
                val result =
                    runBlocking {
                        withTimeout(
                            TIMEOUT,
                        ) { reopened.request(RecordsRequest.Open(LearningDecision(null, emptyList()), OWN_APP, at = 2)) }
                    }
                assertTrue(result is RecordsResult.Success)
                assertEquals(
                    LaunchRecord(2, 2),
                    reopened.state.value.records
                        ?.launches
                        ?.records
                        ?.get(OWN_APP),
                )
            } finally {
                again.cancel()
                runBlocking { withTimeout(TIMEOUT) { again.coroutineContext[Job]!!.join() } }
            }
        } finally {
            first.cancel()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun clearingTheLearningKeepsTheLaunchesAndViceVersa() =
        runTest {
            database.write(seedWrite())
            val actor = actor(database)
            assertTrue(actor.request(RecordsRequest.ClearLearned) is RecordsResult.Success)
            assertTrue(
                database
                    .load()
                    .records.learned.records
                    .isEmpty(),
            )
            assertEquals(
                setOf(OWN_APP, WORK_APP),
                database
                    .load()
                    .records.launches.records.keys,
            )
            actor.request(RecordsRequest.Open(LearningDecision(pair("よみ", OWN_APP), emptyList()), null, at = 5))
            assertTrue(actor.request(RecordsRequest.ClearLaunches) is RecordsResult.Success)
            assertTrue(
                database
                    .load()
                    .records.launches.records
                    .isEmpty(),
            )
            assertEquals(
                setOf(pair("よみ", OWN_APP)),
                database
                    .load()
                    .records.learned.records.keys,
            )
        }

    @Test
    fun aCorruptDatabaseStartsEmptyAndTellsTheUserOnce() =
        runTest {
            database.write(seedWrite())
            database.close()
            opened.clear()
            corrupt(context, FILE)
            val reopened = RecordsDatabase.open(context, FILE, allowMainThread = true, ownSerial = { OWN }).also(opened::add)
            val actor = actor(reopened)
            runCurrent()
            // 空の Ready で始め、作り直したことを出す
            assertEquals(UserRecords.EMPTY, actor.state.value.records)
            assertTrue(actor.rebuiltAfterCorruption.value)
            // ほかの頼みでは消えない。画面が知らせを出したら消す
            assertTrue(actor.request(RecordsRequest.Open(LearningDecision(null, emptyList()), OWN_APP, at = 1)) is RecordsResult.Success)
            assertTrue(actor.rebuiltAfterCorruption.value)
            actor.acknowledgeRebuilt()
            actor.refresh().await()
            assertTrue(!actor.rebuiltAfterCorruption.value)
            assertTrue(!rebuiltMark(context, FILE).exists())
        }

    @Test
    fun anIOExceptionFromTheStorageFailsOnlyThatRequest() =
        runTest {
            val storage =
                FaultyStorage(database).apply {
                    failWrites = 1
                    writeFailure = { java.io.IOException("保存先の入出力の失敗") }
                }
            val actor = actor(storage)
            val open = RecordsRequest.Open(LearningDecision(null, emptyList()), OWN_APP, at = 1)
            assertEquals(RecordsResult.Failure(RecordsFailure.WRITE_FAILED), actor.request(open))
            assertTrue(actor.request(open) is RecordsResult.Success)
            assertEquals(
                LaunchRecord(1, 1),
                database
                    .load()
                    .records.launches.records[OWN_APP],
            )
        }

    @Test
    fun requestsAfterTheWriterStoppedAreCancelled() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val actor = RecordsActor(scope, database, probe, StandardTestDispatcher(testScheduler))
            scope.cancel()
            runCurrent()
            assertTrue(actor.submit(RecordsRequest.Forget(0)).isCancelled)
        }

    @Test
    fun theProbeIsAskedOnEveryRequest() =
        runTest {
            val actor = actor(database)
            runCurrent()
            val before = probe.calls
            actor.request(RecordsRequest.Forget(0))
            actor.request(RecordsRequest.Forget(0))
            assertEquals(before + 2, probe.calls)
        }

    private fun TestScope.actor(storage: RecordsStorage): RecordsActor =
        RecordsActor(backgroundScope, storage, probe, StandardTestDispatcher(testScheduler))

    // 書き手と送り手を本物のスレッドで動かす
    private fun threaded(
        storage: RecordsStorage,
        body: (RecordsActor, CoroutineScope) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            body(RecordsActor(scope, storage, probe, Dispatchers.IO), scope)
        } finally {
            scope.cancel()
        }
    }

    private fun trigger(sql: String) {
        withRawDatabase(context, FILE) { it.execSQL(sql) }
    }

    private companion object {
        const val FILE = "m802a-records-actor-test.db"
        const val TIMEOUT = 30_000L
    }
}
