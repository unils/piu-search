// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.os.UserHandle
import android.util.Log
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.state.Environment
import jp.piu.search.core.state.GenreResult
import jp.piu.search.core.state.IndexRules
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.Notice
import jp.piu.search.core.state.PackageKey
import jp.piu.search.data.MaterialCauses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

/**
 * 索引の書き手の状態。索引の写し（[IndexActor.snapshot]）とは別に流す。
 *
 * @property loaded 保存した索引の読み戻しを終えた（読めなかった場合を含む）
 * @property loadFailure 読み戻しの失敗の理由。空から全件を作り直す
 * @property running 同期の途中
 * @property syncFailure 最後の同期が全体で失敗した理由（観測の例外・規則の誤り）。次に終わった同期で消える
 * @property genreFailure 最後に終わった同期のジャンルの問い合わせの失敗の理由。成功なら null（既存のジャンルを保った）
 * @property unsaved 公開した写しを、まだ保存できていない（未保存の印）
 * @property lastSavedAt 最後に保存できた時刻（ミリ秒）。保存したことがなければ null
 * @property lastSync このプロセスで最後に終わった同期（全体で失敗した回を含む）。まだなければ null。
 *   保存した写しの一覧の状態は前のプロセスの結果を含むので、「このプロセスの同期の結果」を出すときはこれで見分ける
 */
public data class IndexStatus(
    val loaded: Boolean = false,
    val loadFailure: String? = null,
    val running: Boolean = false,
    val syncFailure: String? = null,
    val genreFailure: String? = null,
    val unsaved: Boolean = false,
    val lastSavedAt: Long? = null,
    val lastSync: SyncSummary? = null,
)

/**
 * 同期 1 回の要約。報告と計測に使い、判断には使わない。
 *
 * @property finishedAt 終わった時刻（ミリ秒）
 * @property millis 観測の始めから写しの公開まで（ミリ秒）。保存は含まない
 * @property materialsRead 材料を読んだ組の数
 * @property keysBuilt キーを作った入口の数
 * @property removed 写しから消した組の数
 * @property failed 同期全体が失敗した（写しは前のまま）
 */
public data class SyncSummary(
    val finishedAt: Long,
    val millis: Long,
    val materialsRead: Int,
    val keysBuilt: Int,
    val removed: Int,
    val failed: Boolean,
)

/**
 * 索引の写しの唯一の書き手。1 本の coroutine が [Channel] から頼み（同期・パッケージの知らせ・前面かどうか・保存の再試行）を
 * 届いた順に 1 つずつ処理する。作るのは [open] だけで、同じ DB の書き手は同時に 1 つしか作れない。
 *
 * 同期 1 回（[IndexRules] の plan → prepare → apply）:
 * 1. 一覧を観測し、まだ当てていない知らせと前の写しから、作り直す組を決める
 * 2. 端末から材料とジャンルを読み、キーを作る。この間、写しは変わらない（書き手はこの列だけで、処理の途中に次の頼みを始めないため）
 * 3. 次の写しを公開してから index.db に書く（索引は消えてよいので、保存を待たずに検索に使う）
 *
 * 保存に失敗したら未保存の印を立てたまま、次の機会（次の同期・前面への復帰・[retrySave]）に今の写しを丸ごと書き直す。
 * 材料は読み直さない（公開した写しの試みの鍵が今の値なので、同じ入力の同期は何も読まない）。成功したときだけ印を下ろす。
 *
 * 知らせは、届いた時点で「まだ当てていない」に入れ、それを当てた写しを公開したら「保存待ち」に移し、その写しを保存できたら消す。
 * 同期の途中に届いた知らせは、その同期の後に列から取り出すので、その同期では消えない。知らせで同期するかは、同期を始める直前の前面の状態で決め、
 * 前面なら続く同期を 1 回起こし、裏なら溜めるだけにする。前面になったら、知らせの有無によらず同期する（プロセスがない間の変更を一覧の観測で拾うため）。
 * 続けて届いた頼み（前面になった・同期の頼み・知らせ）は 1 回の同期にまとめる（知らせの対象は落とさない）。
 *
 * 作り直そうとした組と消した組は [staleIcons] に流す。写しが前と同じ回でも流す（アイコンだけが替わった更新を、画面のアイコンの写しから落とすため）。
 */
public class IndexActor internal constructor(
    scope: CoroutineScope,
    private val storage: IndexStorage,
    private val reader: DeviceReader,
    private val keys: KeyFactory,
    private val appVersion: Int,
    context: CoroutineContext,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    onStopped: () -> Unit = {},
) {
    private val inbox = Channel<Message>(Channel.UNLIMITED) { it.cancel() }
    private val published = MutableStateFlow(IndexSnapshot.EMPTY)
    private val statusFlow = MutableStateFlow(IndexStatus())

    /** 索引の写し。保存した索引を読み戻すまでは空。 */
    public val snapshot: StateFlow<IndexSnapshot> = published.asStateFlow()

    /** 書き手の状態。 */
    public val status: StateFlow<IndexStatus> = statusFlow.asStateFlow()

    // 購読していない間の分は捨てる（アイコンの写しを持つ側は、書き手と同じ寿命で購読し続ける）
    private val staleFlow = MutableSharedFlow<Set<PackageKey>>(extraBufferCapacity = Int.MAX_VALUE)

    /** アイコンを失効させる組（同期で作り直そうとした組と消した組）。空の回は流さない。 */
    public val staleIcons: SharedFlow<Set<PackageKey>> = staleFlow.asSharedFlow()

    // 以下は書き手の coroutine の中だけで読み書きする
    private var current = IndexSnapshot.EMPTY

    // 最後に保存できた（か、読み戻した）写し。読み戻せなかったら null（何を保存したか分からないので、次の写しは必ず書く）
    private var saved: IndexSnapshot? = null
    private var unsaved = false
    private var foreground = false
    private var syncWanted = false
    private var noticed = false
    private var nextSeq = 0L
    private val unapplied = LinkedHashMap<Long, Notice>()
    private val awaitingSave = LinkedHashMap<Long, Notice>()
    private val users = LinkedHashMap<UserHandle, Int>()
    private val waiting = ArrayList<CompletableDeferred<Unit>>()

    internal val job: Job =
        scope.launch(context) {
            load()
            for (message in inbox) {
                if (!isActive) {
                    message.cancel()
                    break
                }
                handle(message)
                // 続けて届いている頼みを先に受け、同期を 1 回にまとめる
                while (isActive) handle(inbox.tryReceive().getOrNull() ?: break)
                // 知らせによる同期は、始める直前の前面の状態で決める（知らせの後に裏へ回ったなら溜めるだけ）
                if ((syncWanted || (noticed && foreground)) && isActive) {
                    syncWanted = false
                    noticed = false
                    val replies = waiting.toList()
                    waiting.clear()
                    try {
                        sync()
                        replies.forEach { it.complete(Unit) }
                    } finally {
                        // 処理の途中で書き手が止まったら、待つ側を残さない（終わった返信には効かない）
                        replies.forEach { it.cancel() }
                    }
                }
            }
        }

    init {
        job.invokeOnCompletion {
            inbox.cancel()
            waiting.forEach { it.cancel() }
            onStopped()
        }
    }

    /** 同期を頼む。返す [Deferred] は、この頼みを含む同期の写しを公開し、保存を試した後に終わる。書き手が止まっていれば取り消しになる。 */
    public fun requestSync(): Deferred<Unit> {
        val reply = CompletableDeferred<Unit>()
        if (inbox.trySend(Message.Sync(reply)).isFailure) reply.cancel()
        return reply
    }

    /**
     * パッケージの追加・変更・削除の知らせ（`LauncherApps.Callback`）。通知元のシリアル番号は、ここで分かれば持ち、
     * 分からなければ同期のときに解決する。前面なら同期を起こし、裏なら溜めるだけにする。
     */
    public fun packagesChanged(
        packageNames: Collection<String>,
        user: UserHandle,
    ) {
        val serial =
            try {
                reader.serialOf(user)
            } catch (_: RuntimeException) {
                // 番号が取れなくても知らせは捨てず、同期のときに通知元から解決し直す
                null
            }
        inbox.trySend(Message.Noticed(packageNames.toList(), user, serial))
    }

    /**
     * 検索画面が前面か。前面になったら同期する（一覧の観測は毎回行い、未保存の写しがあれば保存も試す）。
     * 続けて [requestSync] を送っても、まとめて 1 回の同期にする。裏の間は、知らせを溜めるだけにする。
     */
    public fun setForeground(visible: Boolean) {
        inbox.trySend(Message.Foreground(visible))
    }

    /** 未保存の写しがあれば、材料を読まずに丸ごと書き直す。 */
    public fun retrySave(): Deferred<Unit> {
        val reply = CompletableDeferred<Unit>()
        if (inbox.trySend(Message.RetrySave(reply)).isFailure) reply.cancel()
        return reply
    }

    /** まだ消していない知らせの数（まだ当てていないもの、当てて保存を待つもの）。テストで消し方を確かめる。 */
    internal suspend fun pendingNotices(): Pair<Int, Int> {
        val reply = CompletableDeferred<Pair<Int, Int>>()
        inbox.send(Message.Inspect(reply))
        return reply.await()
    }

    private fun handle(message: Message) {
        when (message) {
            is Message.Sync -> {
                waiting += message.reply
                syncWanted = true
            }

            is Message.Noticed -> {
                val handle = users.getOrPut(message.user) { users.size }
                message.packageNames.forEach {
                    val notice = Notice(nextSeq++, it, message.serial, handle)
                    unapplied[notice.seq] = notice
                }
                noticed = true
            }

            is Message.Foreground -> {
                foreground = message.visible
                // 同期は最後に保存も試すので、未保存の写しもここで書き直す
                if (foreground) syncWanted = true
            }

            is Message.RetrySave -> {
                try {
                    save()
                    message.reply.complete(Unit)
                } finally {
                    message.cancel()
                }
            }

            is Message.Inspect -> {
                message.reply.complete(unapplied.size to awaitingSave.size)
            }
        }
    }

    private fun load() {
        val next =
            try {
                val stored = storage.load()
                current = stored.snapshot
                saved = stored.snapshot
                IndexStatus(loaded = true, lastSavedAt = stored.savedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 読めなければ空から全件を作り直す。ファイルは消さない（一時的な失敗なら、次の保存で丸ごと書き直す）
                Log.w(TAG, "保存した索引を読めない: ${e.javaClass.name}")
                IndexStatus(loaded = true, loadFailure = MaterialCauses.causeOf(e))
            }
        published.value = current
        statusFlow.value = next
    }

    private fun sync() {
        statusFlow.value = statusFlow.value.copy(running = true)
        var failure: String? = null
        val started = monotonic()
        var counts = Counts(0, 0, 0)
        var finished = false
        try {
            counts = syncOnce()
            finished = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // 同期全体の失敗。写しと知らせは前のまま保ち、次の頼みでやり直す
            Log.w(TAG, "索引の同期に失敗した: ${MaterialCauses.causeOf(e)}")
            failure = MaterialCauses.causeOf(e)
            finished = true
        } finally {
            // 取り消しで抜けても「同期の途中」を残さない。取り消しの回は終わった同期に数えない
            val summary =
                if (finished) {
                    SyncSummary(clock(), monotonic() - started, counts.read, counts.built, counts.removed, failure != null)
                } else {
                    statusFlow.value.lastSync
                }
            statusFlow.value = statusFlow.value.copy(running = false, syncFailure = failure, lastSync = summary)
            // 識別子は出さず、件数と時間だけを残す（release-test と計測が読む）
            if (finished && summary != null) {
                Log.i(
                    SYNC_TAG,
                    "索引を同期した: read=${summary.materialsRead} built=${summary.keysBuilt} removed=${summary.removed} " +
                        "failed=${summary.failed} millis=${summary.millis}",
                )
            }
        }
        save()
    }

    private class Counts(
        val read: Int,
        val built: Int,
        val removed: Int,
    )

    private fun syncOnce(): Counts {
        val device = reader.environment()
        val env =
            Environment(
                device.locale,
                device.osBuild,
                reader.readerVersion,
                keys.generatorVersion,
                keys.brandTableHash,
                appVersion,
                device.bootCount,
            )
        val known = current.packages.keys.mapTo(HashSet()) { it.profileSerial } + current.listings.keys
        val observation = reader.observe(known)
        val notices = unapplied.values.toList()
        val previous = current.packages.keys
        val plan = IndexRules.plan(current, observation, env, notices, userSerials(notices))
        val reads = plan.profileReads.values.map(reader::readMaterials)
        val prepared = IndexRules.prepare(plan, reads, utcDay(clock()))
        val built = if (prepared.jobs.isEmpty()) emptyMap() else keys.build(prepared.jobs)
        val genres = reader.genres(plan.genres)
        val outcome = IndexRules.apply(prepared, genres, built)
        statusFlow.value = statusFlow.value.copy(genreFailure = (genres as? GenreResult.Failed)?.cause)
        current = outcome.snapshot
        published.value = current
        outcome.consumableNotices.forEach { seq -> unapplied.remove(seq)?.let { awaitingSave[seq] = it } }
        if (outcome.staleIcons.isNotEmpty()) staleFlow.tryEmit(outcome.staleIcons)
        if (current != saved) unsaved = true
        return Counts(
            plan.profileReads.values.sumOf { it.jobs.size },
            prepared.jobs.size,
            previous.count { it !in current.packages },
        )
    }

    // 受けた時点で番号の分からなかった知らせの通知元を、今の番号に解決する。解決できなければ知らせは残る
    private fun userSerials(notices: List<Notice>): Map<Int, Long> {
        val asked = notices.filter { it.profileSerial == null }.mapTo(HashSet()) { it.userHandle }
        return users.entries
            .filter { it.value in asked }
            .mapNotNull { (user, handle) ->
                try {
                    reader.serialOf(user)?.let { handle to it }
                } catch (_: RuntimeException) {
                    null
                }
            }.toMap()
    }

    // 未保存の印が立っていれば、今の写しを丸ごと書く。差分にしないのは、失敗した回の変更を取りこぼさないため。
    // 印がなければ今の写しは保存済みと同じなので、当てた知らせはもう消してよい
    private fun save() {
        if (!unsaved) {
            awaitingSave.clear()
            return
        }
        val snapshot = current
        val now = clock()
        // 保存の途中も、公開した写しがまだ保存されていないことを見せる。成功したときだけ下ろす
        statusFlow.value = statusFlow.value.copy(unsaved = true)
        try {
            storage.save(snapshot, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "索引を保存できない: ${e.javaClass.name}")
            return
        }
        unsaved = false
        saved = snapshot
        awaitingSave.clear()
        statusFlow.value = statusFlow.value.copy(unsaved = false, lastSavedAt = now)
        // 保存を観測できるようにする（release-test がプロセスを止める前に待つ）。件数も識別子も出さない
        Log.i(SYNC_TAG, "索引を保存した")
    }

    private sealed class Message {
        open fun cancel() {}

        class Sync(
            val reply: CompletableDeferred<Unit>,
        ) : Message() {
            override fun cancel() {
                reply.cancel()
            }
        }

        class Noticed(
            val packageNames: List<String>,
            val user: UserHandle,
            val serial: Long?,
        ) : Message()

        class Foreground(
            val visible: Boolean,
        ) : Message()

        class RetrySave(
            val reply: CompletableDeferred<Unit>,
        ) : Message() {
            override fun cancel() {
                reply.cancel()
            }
        }

        class Inspect(
            val reply: CompletableDeferred<Pair<Int, Int>>,
        ) : Message() {
            override fun cancel() {
                reply.cancel()
            }
        }
    }

    /** 作り方。 */
    public companion object {
        private const val TAG = "PiuIndex"

        // 0.2.0 の同期と同じタグ。release-test と計測が logcat で読む
        private const val SYNC_TAG = "PiuIndexSync"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** 製品の索引の DB のファイルの名前。バックアップと端末間の転送から外す（app の backup の規則）。 */
        public const val FILE_NAME: String = "index.db"

        // DB のファイルの絶対パス → 動いている書き手
        private val writers = HashMap<String, IndexActor>()

        /**
         * アプリの `databases` の下の索引の DB（[FILE_NAME]）を開き、その書き手を作る。書き手は最初に保存した索引を読み戻す。
         * 同じ DB の書き手がまだ動いていれば [IllegalStateException] を投げる。[scope] を取り消して書き手が止まると、DB を閉じる。
         *
         * ファイルの名前は受けない。索引の DB は形が変われば中身を捨てるので、記録の DB（`piu.db`）を取り違えて開くと記録が消えるため。
         *
         * @param scope 書き手の寿命。アプリの寿命の scope を渡す
         * @param appVersion このアプリの `versionCode`。欠けを書いた版と比べ、違えば欠けのある組を読み直す
         * @param analyzer キーの生成に使う形態素解析器。漢字の検索語に読みを付ける側（画面）と共有してよい
         * @param dispatcher 書き手を動かす Dispatcher。端末の読み取り・キーの生成・保存はここで行う（画面のスレッドにしない）
         */
        public fun open(
            context: Context,
            scope: CoroutineScope,
            appVersion: Int,
            analyzer: ReadingAnalyzer,
            dispatcher: CoroutineContext = Dispatchers.IO,
        ): IndexActor = openNamed(context, FILE_NAME, scope, appVersion, analyzer, dispatcher)

        /** 名前 [name] の索引の DB で [open] する。テストと debug 版の比べる道具だけが、製品と別の名前で使う。 */
        internal fun openNamed(
            context: Context,
            name: String,
            scope: CoroutineScope,
            appVersion: Int,
            analyzer: ReadingAnalyzer,
            dispatcher: CoroutineContext = Dispatchers.IO,
        ): IndexActor =
            open(context, name, scope, appVersion, DeviceAppsReader(context), GeneratorKeyFactory(analyzer), dispatcher) {
                IndexDatabase.open(context, name)
            }

        @Suppress("LongParameterList")
        internal fun open(
            context: Context,
            name: String,
            scope: CoroutineScope,
            appVersion: Int,
            reader: DeviceReader,
            keys: KeyFactory,
            dispatcher: CoroutineContext,
            database: () -> IndexDatabase,
        ): IndexActor {
            val key = context.getDatabasePath(name).absolutePath
            synchronized(writers) {
                check(writers[key]?.job?.isCompleted != false) { "索引の DB $name の書き手はすでに動いている" }
                val db = database()
                val actor =
                    IndexActor(scope, db, reader, keys, appVersion, dispatcher) {
                        db.close()
                        synchronized(writers) { if (writers[key]?.job?.isCompleted == true) writers.remove(key) }
                    }
                if (!actor.job.isCompleted) writers[key] = actor
                return actor
            }
        }

        private fun utcDay(millis: Long): String =
            Instant
                .ofEpochMilli(millis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString()
    }
}
