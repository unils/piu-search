// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.util.Log
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.data.LearnedStore
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 学習の受け取り手。学習が変わるたびに、記録のスレッドから呼ばれる。 */
fun interface LearningListener {
    /** 学習が [memory] になった。 */
    fun onLearningChanged(memory: LearnedMemory)
}

/**
 * 救済の知らせ 1 つ（ui.md の「すべてのアプリ」の「「おかね」で PayPay を出すようにしました［取り消す］」）。
 *
 * @property pair 救済で記録した組
 * @property label アプリの表示名（知らせの文に出す）
 * @property selection 同じセッションの選択。取り消しても選択の分は残す（[LearnedMemory.rescueUndone]）
 * @property at 記録した時刻
 */
data class RescueNotice(
    val pair: LearnedPair,
    val label: String,
    val selection: LearnedPair?,
    val at: Long,
)

/**
 * 学習（`learned`）のメモリ上の写しと、データベースへの書き込み（[LaunchHistoryRepository] と同じ形）。
 *
 * 読み込みと書き込みは [executor] の 1 本のスレッドで順に行う。読み込みが先に済んでから記録を足すので、
 * 読み込む前にアプリを開いても、記録が読み込みで上書きされて消えることはない。
 *
 * 救済の知らせは、アプリを開くと検索の画面がすぐ閉じるので、次に画面を開いたときに［取り消す］つきで出す（[takeNotices]）。
 * 知らせはメモリにだけ持つ（プロセスが終わると、取り消しの機会だけがなくなる。学習は残る）。
 */
class LearningRepository(
    private val store: () -> LearnedStore,
    private val executor: Executor,
) {
    private val loadRequested = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<LearningListener>()
    private val notices = ArrayList<RescueNotice>()

    // 救済を記録する前の記録（なければ null）。取り消しで戻す。executor のスレッドでだけ読み書きする
    private val beforeRescue = HashMap<RescueNotice, LearnedRecord?>()

    /** 今の学習。読み込む前は空。 */
    @Volatile
    var memory: LearnedMemory = LearnedMemory.EMPTY
        private set

    /** [listener] を足す。 */
    fun addListener(listener: LearningListener) {
        listeners += listener
    }

    /** [listener] を外す。 */
    fun removeListener(listener: LearningListener) {
        listeners -= listener
    }

    /** データベースから学習を読む。2 回目からは何もしない。 */
    fun requestLoad() {
        if (!loadRequested.compareAndSet(false, true)) return
        executor.execute {
            try {
                publish(store().load())
            } catch (e: RuntimeException) {
                // 読めなくても、学習が効かないだけで検索はできる
                Log.w(TAG, "学習を読めなかった", e)
            }
        }
    }

    /**
     * 1 つのセッションの判定 [decision] を時刻 [at] に記録する。救済の知らせを返し、次に画面を開いたときにも出せるよう取っておく。
     *
     * @param labelOf アプリの表示名。わからなければ null（パッケージ名を出す）
     */
    fun record(
        decision: LearningDecision,
        at: Long,
        labelOf: (LearnedPair) -> String?,
    ): List<RescueNotice> {
        if (decision.isEmpty) return emptyList()
        val made = decision.rescues.map { RescueNotice(it, labelOf(it) ?: it.app.packageName, decision.selection, at) }
        synchronized(notices) { notices += made }
        requestLoad()
        update { old ->
            made.forEach { beforeRescue[it] = old.records[it.pair] }
            old.recorded(decision, at)
        }
        return made
    }

    /** まだ出していない救済の知らせを取り出す（取り出したものは二度と返さない）。 */
    fun takeNotices(): List<RescueNotice> =
        synchronized(notices) {
            notices.toList().also { notices.clear() }
        }

    /**
     * 取り出したが出し終えていない知らせ [unshown] を戻す。次の [takeNotices] で、ほかの知らせより先に返す。
     * 向きの変更などで画面を作り直すときに使う（作り直した画面が取り出して出す）。
     */
    fun returnNotices(unshown: List<RescueNotice>) {
        if (unshown.isEmpty()) return
        synchronized(notices) { notices.addAll(0, unshown.filterNot { it in notices }) }
    }

    /** 救済の知らせで「取り消す」を選んだ: その組を記録する前に戻す（learning.md の R2）。 */
    fun undo(notice: RescueNotice) {
        update { old ->
            if (!beforeRescue.containsKey(notice)) return@update old
            old.rescueUndone(notice.pair, beforeRescue.remove(notice), notice.selection, notice.at)
        }
    }

    /** [pair] に「この語では出さない」の印を付ける（R4）。 */
    fun suppress(
        pair: LearnedPair,
        at: Long,
    ) {
        requestLoad()
        update { it.suppressed(pair, at) }
    }

    /** [pair] の学習を消す（「この語での学習を消す」）。 */
    fun remove(pair: LearnedPair) {
        requestLoad()
        update { it.removed(pair) }
    }

    /**
     * すべての学習を消す（管理画面の全消去）。まだ出していない救済の知らせも捨てる（取り消しで、消した記録を戻さないため）。
     */
    fun clear() {
        synchronized(notices) { notices.clear() }
        requestLoad()
        executor.execute {
            try {
                // メモリが空でも省略しない。旧版などの見えない行が DB に残っている場合も全消去するため
                store().clear()
            } catch (e: RuntimeException) {
                Log.w(TAG, "学習を消せなかった", e)
                return@execute
            }
            beforeRescue.clear()
            publish(memory.cleared())
        }
    }

    /**
     * 今の学習を書き出す。[write] には JSON の文字列を渡す（ファイルへの書き込みは呼ぶ側。記録のスレッドで呼ぶ）。
     * 読み込みと書き込みが済んでから書くので、書き出す途中の記録は次の書き出しに入る。
     *
     * @param onDone 終わったとき。記録のスレッドから、書き出した件数（書けなければ null）を渡す
     */
    fun export(
        profiles: BackupProfiles,
        now: Long,
        write: (String) -> Unit,
        onDone: (Int?) -> Unit,
    ) {
        requestLoad()
        executor.execute {
            val snapshot = memory
            val written =
                try {
                    write(LearningBackup.encode(snapshot, profiles, now))
                    snapshot.records.size
                } catch (e: IOException) {
                    Log.w(TAG, "学習を書き出せなかった", e)
                    null
                } catch (e: RuntimeException) {
                    // 保存先の提供元が SecurityException などを投げることがある
                    Log.w(TAG, "学習を書き出せなかった", e)
                    null
                }
            onDone(written)
        }
    }

    /**
     * [read] で読んだ JSON の学習を、時刻 [now] に今の学習と合わせる（[LearnedMemory.merged]。learning.md の「保存と持ち出し」）。
     * 読めない・壊れている・新しい版なら、何も変えずに [onDone] でわけを知らせる。
     *
     * @param read ファイルを読む。記録のスレッドで呼ぶ。大きすぎるなら null を返す
     * @param onDone 終わったとき。記録のスレッドから呼ぶ
     */
    fun import(
        profiles: BackupProfiles,
        now: Long,
        read: () -> String?,
        onDone: (BackupReadResult) -> Unit,
    ) {
        requestLoad()
        executor.execute {
            val result =
                try {
                    read()?.let { LearningBackup.decode(it, profiles) } ?: BackupReadResult.Failure(BackupFailure.TOO_LARGE)
                } catch (e: IOException) {
                    Log.w(TAG, "学習のファイルを読めなかった", e)
                    BackupReadResult.Failure(BackupFailure.UNREADABLE)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "学習のファイルを読めなかった", e)
                    BackupReadResult.Failure(BackupFailure.UNREADABLE)
                }
            // 同じスレッドの中なので、読み込みの結果を合わせる前にほかの記録が割り込まない
            if (result is BackupReadResult.Success) applyChange { it.merged(result.records, now) }
            onDone(result)
        }
    }

    /** 時刻 [now] で、最後に使ってから 180 日たった記録を消す（R5）。画面を開くたびに呼ぶ（読み込みの後に走る）。 */
    fun forgetExpired(now: Long) {
        requestLoad()
        update { it.forgotten(now) }
    }

    /** 頼んだ読み書き（読み込みを含む）がすべて終わってから、記録のスレッドで [action] を走らせる。 */
    fun runAfterPending(action: () -> Unit) {
        executor.execute(action)
    }

    /** 頼んだ読み書きがすべて終わるまで待つ。テストで使う。 */
    fun awaitIdle() {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        done.await(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun update(change: (LearnedMemory) -> LearnedMemory) {
        executor.execute { applyChange(change) }
    }

    // executor のスレッドでだけ呼ぶ
    private fun applyChange(change: (LearnedMemory) -> LearnedMemory) {
        val old = memory
        val next = change(old)
        if (next === old) return
        publish(next)
        try {
            store().save(old, next)
        } catch (e: RuntimeException) {
            Log.w(TAG, "学習を書けなかった", e)
        }
    }

    private fun publish(next: LearnedMemory) {
        memory = next
        listeners.forEach { it.onLearningChanged(next) }
    }

    private companion object {
        const val TAG = "PiuLearning"
        const val AWAIT_SECONDS = 10L
    }
}
