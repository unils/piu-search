// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import android.util.Log
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.state.ProfileCheck
import jp.piu.search.core.state.RecordsChange
import jp.piu.search.core.state.RecordsFailure
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.RecordsRules
import jp.piu.search.core.state.RecordsState
import jp.piu.search.core.state.UserRecords
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
import kotlin.coroutines.CoroutineContext

/**
 * 記録（学習と起動の記録）の唯一の書き手。1 本の coroutine が [Channel] から頼みを届いた順に 1 つずつ処理する。
 * 作るのは [open] だけで、同じ DB の書き手は同時に 1 つしか作れない（2 つの書き手がそれぞれの写しから書くと、起動の回数などの増分を失うため）。
 * アプリの寿命の所有者が 1 つだけ作り、画面ごとの ViewModel には同じものを渡す。
 *
 * 1 つの頼みの処理（[RecordsRules] と同じ順）:
 * 1. その場で OS にプロファイルを問い合わせ（[ProfileProbe]）、読めていなければ読み直す（最初の読み込みの前に転送の整理）。
 *    確かめた削除は、DB の読み書きの成否によらず覚え、書き込みの前に [state] に流す（削除は整理を待たずに画面に効かせる）
 * 2. 規則で変更を計算し、1 つのトランザクションで DB に書く
 * 3. 書けたときだけ写しを差し替えて流す（法則 6）。終わっていない整理（プロファイルの削除）を試し、流してから結果を返す
 *
 * 処理の途中で DB を待っても、次の頼みはその処理が終わるまで始めない。順序は 1 本の coroutine で守り、単一スレッドの Dispatcher には頼らない。
 * 頼みは受け付けた時点で列に入り、頼んだ側が待つのをやめても最後まで処理する（受理した起動の記録を、画面の寿命で失わないため）。
 * 保存の失敗はその頼み 1 つの失敗（[RecordsFailure.WRITE_FAILED] など）として返す。書き手が止まった（scope を取り消した）ときだけ、
 * 返す [Deferred] が取り消しになる。
 */
public class RecordsActor internal constructor(
    scope: CoroutineScope,
    private val storage: RecordsStorage,
    private val profiles: ProfileProbe,
    context: CoroutineContext,
    onStopped: () -> Unit = {},
) {
    private val inbox = Channel<Message<*>>(Channel.UNLIMITED) { it.cancel() }
    private val published = MutableStateFlow(RecordsState.INITIAL)

    // 書き手の coroutine の中だけで読み書きする
    private var current = RecordsState.INITIAL

    /**
     * 公開用の写し。記録は書けたときだけ変わる。画面に出す記録は [RecordsState.records]（削除を確かめたプロファイルの記録を、整理の前から除く）。
     * 読み込み中は記録も失敗の理由もなく、読み込みに失敗したら [RecordsState.loadFailure] が理由を持つ。
     */
    public val state: StateFlow<RecordsState> = published.asStateFlow()

    private val rebuiltFlow = MutableStateFlow(false)

    /**
     * SQLite が破損を知らせ、記録の DB を消して空から作り直したか。画面は 1 回だけ知らせを出し、[acknowledgeRebuilt] で消す。
     * 印はファイルに残すので、知らせる前にプロセスが終わっても次の起動で出る。
     */
    public val rebuiltAfterCorruption: StateFlow<Boolean> = rebuiltFlow.asStateFlow()

    internal val job: Job =
        scope.launch(context) {
            // 頼みを待たずに読み込み、消えたプロファイルの整理まで試す。頼みは読み込みが終わってから処理する（Loading の間の頼みは待つ）
            load(probe())
            publish()
            prune()
            publish()
            for (message in inbox) {
                // 要素のある Channel は止まらずに次を返すので、始める前に書き手の取り消しを確かめる。始めた頼みは結果まで処理する
                if (!isActive) {
                    message.cancel()
                    break
                }
                handle(message)
            }
        }

    init {
        job.invokeOnCompletion {
            inbox.cancel()
            onStopped()
        }
    }

    /** [request] を列に入れる。結果は、書けた写しを [state] に流した後に返す。書き手が止まっていれば取り消した [Deferred] を返す。 */
    public fun submit(request: RecordsRequest): Deferred<RecordsResult> = send(Message.Request(request))

    /** [request] を処理し、結果を待つ。待つのをやめても、頼みは最後まで処理する。 */
    public suspend fun request(request: RecordsRequest): RecordsResult = submit(request).await()

    /**
     * 前面への復帰とプロファイルの変化の知らせ。その場で問い合わせて当て、読めていなければ読み直し、終わっていない整理を試す。
     * 整理の再試行を集合の変化の知らせだけに頼らないため（同じ集合は [StateFlow] が配らない）。
     */
    public fun refresh(): Deferred<Unit> = send(Message.Refresh())

    /** 作り直したことを利用者に知らせた。[rebuiltAfterCorruption] を下ろす。 */
    public fun acknowledgeRebuilt() {
        send(Message.AcknowledgeRebuilt())
    }

    /** 知らせ [id] を出し終えた。取り消しの材料は保つ。 */
    public fun consumeNotice(id: Long) {
        send(Message.Consume(id))
    }

    /**
     * 記録を書き出す。それまでに頼んだ変更を当てた写しを列の中で受け取り、[write]（ファイルへの出力）は列の外の呼ぶ側の coroutine で行う。
     * 遅い保存先への出力で、ほかの頼みを止めないため。読めなければ [write] を呼ばずに null を返す。
     *
     * 写しは画面に出すものと同じで、削除を確かめたプロファイルの記録を含まない。
     */
    public suspend fun <T> export(write: suspend (UserRecords) -> T): T? {
        val records = send(Message.Snapshot()).await() ?: return null
        return write(records)
    }

    /**
     * 書き出した学習を読み込んで合わせる。[read]（ファイルの読み取りと解析）は列の外の呼ぶ側の coroutine で行い、読めた記録だけを列に入れる。
     * 受け入れ先の確認（削除を確かめたプロファイルの記録を受けない）と、最新の記録との合わせは列の中で行う。
     * [read] の例外はそのまま呼ぶ側に返す（記録は変えない）。
     */
    public suspend fun import(
        at: Long,
        read: suspend () -> List<LearnedRecord>,
    ): RecordsResult = request(RecordsRequest.Import(read(), at))

    private fun <T> send(message: Message<T>): Deferred<T> {
        if (inbox.trySend(message).isFailure) message.cancel()
        return message.reply
    }

    private fun handle(message: Message<*>) {
        try {
            when (message) {
                is Message.Request -> {
                    message.reply.complete(process(message.request))
                }

                is Message.Refresh -> {
                    ensureChecked(probe())
                    prune()
                    publish()
                    message.reply.complete(Unit)
                }

                is Message.Consume -> {
                    current = RecordsRules.consumeNotice(current, message.id)
                    publish()
                    message.reply.complete(Unit)
                }

                is Message.AcknowledgeRebuilt -> {
                    storage.clearRebuilt()
                    noticeRebuilt()
                    message.reply.complete(Unit)
                }

                is Message.Snapshot -> {
                    ensureChecked(probe())
                    prune()
                    publish()
                    message.reply.complete(current.records)
                }
            }
        } finally {
            // 処理の途中で書き手が止まったら、待つ側を残さない
            message.cancel()
        }
    }

    private fun process(request: RecordsRequest): RecordsResult {
        val check = probe()
        // 読み込みに失敗していれば読み直す。また失敗したら、規則が計算も書き込みもせずに失敗を返す（空の写しから書かない）
        if (current.records == null) load(check)
        val change = RecordsRules.change(current, request, check)
        // 確かめた削除を、書き込みを待たずに流す（base は頼みの前の記録に確認だけを当てたもので、書けなくてもここに戻る）
        current = change.base
        publish()
        val written = written(change)
        current = RecordsRules.commit(change, written)
        publish()
        prune()
        publish()
        return RecordsRules.resultOf(change, written)
    }

    // 読めていなければ読み直し、読めていれば確認を当てて流す
    private fun ensureChecked(check: ProfileCheck) {
        if (current.records == null) {
            load(check)
        } else {
            current = RecordsRules.checked(current, check)
        }
        publish()
    }

    // 終わっていない整理を試す。失敗したら対象を残し、次の頼みと前面への復帰で再試行する
    private fun prune() {
        val change = RecordsRules.pruneChange(current) ?: return
        current = RecordsRules.commit(change, written(change))
    }

    private fun load(check: ProfileCheck) {
        current =
            try {
                val stored = storage.load()
                RecordsRules.loaded(current, stored.records, stored.storedSerials, check)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 型を理由に残す。失敗を空の記録に置き換えない（空から保存すると、保存済みの記録を上書きするため）。
                // 確かめたプロファイルは DB の成否と別に覚える（読めない間に確かめた削除を、復旧した後の問い合わせの失敗で忘れないため）
                Log.w(TAG, "記録を読めない: ${e.javaClass.name}")
                RecordsRules.checked(RecordsRules.loadFailed(current, e.javaClass.name), check)
            }
        noticeRebuilt()
    }

    // 破損は DB に触れたときに知らされるので、読み書きのたびに印を見る
    private fun noticeRebuilt() {
        rebuiltFlow.value = storage.rebuiltAfterCorruption()
    }

    // 書くものがなければ書けたとみなす（規則の commit と resultOf も、書くものがなければ書き込みの成否を見ない）
    private fun written(change: RecordsChange): Boolean {
        val write = change.write ?: return true
        return try {
            storage.write(write)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // SQLite の失敗（容量・入出力・制約）の RuntimeException と、保存先の IOException。トランザクションは戻っている
            Log.w(TAG, "記録を保存できない: ${e.javaClass.name}")
            false
        } finally {
            noticeRebuilt()
        }
    }

    private fun probe(): ProfileCheck =
        try {
            profiles.check()
        } catch (e: RuntimeException) {
            Log.w(TAG, "プロファイルを問い合わせられない: ${e.javaClass.name}")
            ProfileCheck.Unknown
        }

    private fun publish() {
        published.value = current
    }

    private sealed class Message<T> {
        val reply = CompletableDeferred<T>()

        fun cancel() {
            reply.cancel()
        }

        class Request(
            val request: RecordsRequest,
        ) : Message<RecordsResult>()

        class Refresh : Message<Unit>()

        class Consume(
            val id: Long,
        ) : Message<Unit>()

        class Snapshot : Message<UserRecords?>()

        class AcknowledgeRebuilt : Message<Unit>()
    }

    /** 作り方。 */
    public companion object {
        private const val TAG = "PiuRecords"

        // DB のファイルの絶対パス → 動いている書き手
        private val writers = HashMap<String, RecordsActor>()

        /**
         * アプリの `databases` の下の記録の DB [name] を開き、その書き手を作る。版 6 以前の DB なら、最初の読み込みで版 7 へ移行する。
         * 製品の `piu.db` への切り替えは別に行うので、名前は呼ぶ側が決める。
         *
         * 同じ DB の書き手がまだ動いていれば [IllegalStateException] を投げる（同じ DB に 2 つの書き手を作らないため）。
         * [scope] を取り消して書き手が止まると、DB を閉じ、同じ名前でまた作れるようになる。
         *
         * @param scope 書き手の寿命。アプリの寿命の scope を渡す。取り消すと、始めていない頼みは取り消しになる
         * @param profiles プロファイルの存在の問い合わせ
         * @param dispatcher 書き手を動かす Dispatcher。保存先と問い合わせはここで呼ぶ（ファイルと binder に触れるので、画面のスレッドにしない）
         */
        public fun open(
            context: Context,
            name: String,
            scope: CoroutineScope,
            profiles: ProfileProbe = DeviceProfileProbe(context),
            dispatcher: CoroutineContext = Dispatchers.IO,
        ): RecordsActor = open(context, name, scope, profiles, dispatcher) { RecordsDatabase.open(context, name, allowMainThread = false) }

        internal fun open(
            context: Context,
            name: String,
            scope: CoroutineScope,
            profiles: ProfileProbe,
            dispatcher: CoroutineContext,
            database: () -> RecordsDatabase,
        ): RecordsActor {
            val key = context.getDatabasePath(name).absolutePath
            synchronized(writers) {
                check(writers[key]?.job?.isCompleted != false) {
                    "記録の DB $name の書き手はすでに動いている。アプリの寿命の所有者が 1 つだけ作り、同じものを渡す"
                }
                val db = database()
                // 止まった書き手の登録を外す。作る途中で止まる（scope がすでに取り消されている）こともあるので、書き手そのものは参照しない
                val actor =
                    RecordsActor(scope, db, profiles, dispatcher) {
                        db.close()
                        synchronized(writers) { if (writers[key]?.job?.isCompleted == true) writers.remove(key) }
                    }
                if (!actor.job.isCompleted) writers[key] = actor
                return actor
            }
        }
    }
}
