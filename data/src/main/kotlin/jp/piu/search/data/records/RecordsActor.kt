// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.util.Log
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.state.ProfileCheck
import jp.piu.search.core.state.RecordsChange
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 記録（学習と起動の記録）の唯一の書き手。1 本の coroutine が [Channel] から頼みを届いた順に 1 つずつ処理する。
 *
 * 1 つの頼みの処理（[RecordsRules] と同じ順）:
 * 1. その場で OS にプロファイルを問い合わせ（[ProfileProbe]）、読めていなければ読み直す（最初の読み込みの前に転送の整理）
 * 2. 規則で変更を計算し、1 つのトランザクションで DB に書く
 * 3. 書けたときだけ写しを差し替える（法則 6）。終わっていない整理（プロファイルの削除）を試し、[state] に流してから結果を返す
 *
 * 処理の途中で DB を待っても、次の頼みはその処理が終わるまで始めない。順序は 1 本の coroutine で守り、単一スレッドの Dispatcher には頼らない。
 * 頼みは受け付けた時点で列に入り、頼んだ側が待つのをやめても最後まで処理する（受理した起動の記録を、画面の寿命で失わないため）。
 *
 * @param scope 書き手の寿命。アプリの寿命の scope を渡す。取り消すと、列に残った頼みも取り消す
 * @param storage 保存先
 * @param profiles プロファイルの存在の問い合わせ
 * @param context 書き手を動かす Dispatcher。保存先と問い合わせはここで呼ぶ（ファイルと binder に触れるので、画面のスレッドにしない）
 */
public class RecordsActor(
    scope: CoroutineScope,
    private val storage: RecordsStorage,
    private val profiles: ProfileProbe,
    context: CoroutineContext = Dispatchers.IO,
) {
    private val inbox = Channel<Message<*>>(Channel.UNLIMITED) { it.cancel() }
    private val published = MutableStateFlow(RecordsState.INITIAL)

    // 書き手の coroutine の中だけで読み書きする。公開するのは、書けた後の値だけ
    private var current = RecordsState.INITIAL

    /**
     * 公開用の写し。書けたときだけ変わる。画面に出す記録は [RecordsState.records]（削除を確かめたプロファイルの記録を、整理の前から除く）。
     * 読み込み中は記録も失敗の理由もなく、読み込みに失敗したら [RecordsState.loadFailure] が理由を持つ。
     */
    public val state: StateFlow<RecordsState> = published.asStateFlow()

    init {
        val job =
            scope.launch(context) {
                // 頼みを待たずに読み込む。頼みは読み込みが終わってから処理する（Loading の間の頼みは待つ）
                load(probe())
                publish()
                for (message in inbox) handle(message)
            }
        job.invokeOnCompletion { inbox.cancel() }
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
                    val check = probe()
                    if (current.records == null) load(check) else current = RecordsRules.checked(current, check)
                    prune()
                    publish()
                    message.reply.complete(Unit)
                }

                is Message.Consume -> {
                    current = RecordsRules.consumeNotice(current, message.id)
                    publish()
                    message.reply.complete(Unit)
                }

                is Message.Snapshot -> {
                    if (current.records == null) load(probe())
                    prune()
                    publish()
                    message.reply.complete(current.records)
                }
            }
        } finally {
            // 処理の途中で書き手が取り消されたら、待つ側を残さない
            message.cancel()
        }
    }

    private fun process(request: RecordsRequest): RecordsResult {
        val check = probe()
        // 読み込みに失敗していれば読み直す。また失敗したら、規則が計算も書き込みもせずに失敗を返す（空の写しから書かない）
        if (current.records == null) load(check)
        val change = RecordsRules.change(current, request, check)
        val written = written(change)
        current = RecordsRules.commit(change, written)
        prune()
        publish()
        return RecordsRules.resultOf(change, written)
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
                // 型を理由に残す。失敗を空の記録に置き換えない（空から保存すると、保存済みの記録を上書きするため）
                Log.w(TAG, "記録を読めない: ${e.javaClass.name}")
                RecordsRules.loadFailed(current, e.javaClass.name)
            }
    }

    // 書くものがなければ書けたとみなす（規則の commit と resultOf も、書くものがなければ書き込みの成否を見ない）
    private fun written(change: RecordsChange): Boolean {
        val write = change.write ?: return true
        return try {
            storage.write(write)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // SQLite の失敗（容量・入出力・制約）は Room が RuntimeException で投げる。トランザクションは戻っている
            Log.w(TAG, "記録を保存できない: ${e.javaClass.name}")
            false
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
    }

    private companion object {
        const val TAG = "PiuRecords"
    }
}
