// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.os.UserHandle
import android.util.Log
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.state.Environment
import jp.piu.search.core.state.IndexRules
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.Notice
import jp.piu.search.data.MaterialCauses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * @property unsaved 公開した写しを、まだ保存できていない（未保存の印）
 * @property lastSavedAt 最後に保存できた時刻（ミリ秒）。保存したことがなければ null
 */
public data class IndexStatus(
    val loaded: Boolean = false,
    val loadFailure: String? = null,
    val running: Boolean = false,
    val syncFailure: String? = null,
    val unsaved: Boolean = false,
    val lastSavedAt: Long? = null,
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
 * 同期の途中に届いた知らせは、その同期の後に列から取り出すので、その同期では消えない。前面なら続く同期を 1 回起こし、裏なら溜めるだけにする。
 * 続けて届いた頼みは 1 回の同期にまとめる（知らせの対象は落とさない）。
 */
public class IndexActor internal constructor(
    scope: CoroutineScope,
    private val storage: IndexStorage,
    private val reader: DeviceReader,
    private val keys: KeyFactory,
    private val appVersion: Int,
    context: CoroutineContext,
    private val clock: () -> Long = System::currentTimeMillis,
    onStopped: () -> Unit = {},
) {
    private val inbox = Channel<Message>(Channel.UNLIMITED) { it.cancel() }
    private val published = MutableStateFlow(IndexSnapshot.EMPTY)
    private val statusFlow = MutableStateFlow(IndexStatus())

    /** 索引の写し。保存した索引を読み戻すまでは空。 */
    public val snapshot: StateFlow<IndexSnapshot> = published.asStateFlow()

    /** 書き手の状態。 */
    public val status: StateFlow<IndexStatus> = statusFlow.asStateFlow()

    // 以下は書き手の coroutine の中だけで読み書きする
    private var current = IndexSnapshot.EMPTY

    // 最後に保存できた（か、読み戻した）写し。読み戻せなかったら null（何を保存したか分からないので、次の写しは必ず書く）
    private var saved: IndexSnapshot? = null
    private var unsaved = false
    private var foreground = false
    private var syncWanted = false
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
                if (syncWanted && isActive) {
                    syncWanted = false
                    val replies = waiting.toList()
                    waiting.clear()
                    sync()
                    replies.forEach { it.complete(Unit) }
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

    /** 検索画面が前面か。前面になったら、溜めた知らせがあれば同期し、未保存の写しがあれば保存を試す。 */
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
                if (foreground) syncWanted = true
            }

            is Message.Foreground -> {
                foreground = message.visible
                if (foreground) {
                    if (unapplied.isNotEmpty()) syncWanted = true
                    save()
                }
            }

            is Message.RetrySave -> {
                save()
                message.reply.complete(Unit)
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
        try {
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
            val plan = IndexRules.plan(current, observation, env, notices, userSerials(notices))
            val reads = plan.profileReads.values.map(reader::readMaterials)
            val prepared = IndexRules.prepare(plan, reads, utcDay(clock()))
            val built = if (prepared.jobs.isEmpty()) emptyMap() else keys.build(prepared.jobs)
            val outcome = IndexRules.apply(prepared, reader.genres(plan.genres), built)
            current = outcome.snapshot
            published.value = current
            outcome.consumableNotices.forEach { seq -> unapplied.remove(seq)?.let { awaitingSave[seq] = it } }
            if (current != saved) unsaved = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // 同期全体の失敗。写しと知らせは前のまま保ち、次の頼みでやり直す
            Log.w(TAG, "索引の同期に失敗した: ${MaterialCauses.causeOf(e)}")
            failure = MaterialCauses.causeOf(e)
        }
        statusFlow.value = statusFlow.value.copy(running = false, syncFailure = failure)
        save()
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
        try {
            storage.save(snapshot, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "索引を保存できない: ${e.javaClass.name}")
            statusFlow.value = statusFlow.value.copy(unsaved = true)
            return
        }
        unsaved = false
        saved = snapshot
        awaitingSave.clear()
        statusFlow.value = statusFlow.value.copy(unsaved = false, lastSavedAt = now)
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

        /** 製品の索引の DB のファイルの名前。バックアップと端末間の転送から外す（app の backup の規則）。 */
        public const val FILE_NAME: String = "index.db"

        // DB のファイルの絶対パス → 動いている書き手
        private val writers = HashMap<String, IndexActor>()

        /**
         * アプリの `databases` の下の索引の DB [name] を開き、その書き手を作る。書き手は最初に保存した索引を読み戻す。
         * 同じ DB の書き手がまだ動いていれば [IllegalStateException] を投げる。[scope] を取り消して書き手が止まると、DB を閉じる。
         *
         * @param scope 書き手の寿命。アプリの寿命の scope を渡す
         * @param appVersion このアプリの `versionCode`。欠けを書いた版と比べ、違えば欠けのある組を読み直す
         * @param analyzer キーの生成に使う形態素解析器。漢字の検索語に読みを付ける側（画面）と共有してよい
         * @param dispatcher 書き手を動かす Dispatcher。端末の読み取り・キーの生成・保存はここで行う（画面のスレッドにしない）
         */
        public fun open(
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
