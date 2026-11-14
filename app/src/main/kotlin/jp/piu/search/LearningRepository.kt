// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.util.Log
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.data.LearnedStorage
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** 学習の受け取り手。学習が変わるたびに、記録のスレッドから呼ばれる。 */
fun interface LearningListener {
    /** 学習が [memory] になった。 */
    fun onLearningChanged(memory: LearnedMemory)
}

/** 取り消せなくなった救済の知らせの受け取り手。記録のスレッドから呼ばれる。 */
fun interface RescueNoticeListener {
    /** 古い取り消し材料が無効になった。表示中の知らせも [LearningRepository.canUndo] で確かめ直す。 */
    fun onNoticesInvalidated()
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
) {
    /** 同じ時刻・内容でも、別の保存で作った知らせを取り違えないための印。 */
    internal val undoKey = Any()
}

/**
 * 学習（`learned`）のメモリ上の写しと、データベースへの書き込み（[LaunchHistoryRepository] と同じ形）。
 *
 * 読み込みと書き込みは [executor] の 1 本のスレッドで順に行う。読み込みが先に済んでから記録を足すので、
 * 読み込む前にアプリを開いても、記録が読み込みで上書きされて消えることはない。
 *
 * 救済の知らせは、アプリを開くと検索の画面がすぐ閉じるので、次に画面を開いたときに［取り消す］つきで出す（[pendingNotices]）。
 * 知らせはメモリにだけ持つ（プロセスが終わると、取り消しの機会だけがなくなる。学習は残る）。
 */
class LearningRepository(
    private val store: () -> LearnedStorage,
    private val executor: Executor,
    private val isProfilePresent: (Long) -> Boolean = { true },
) {
    private val listeners = CopyOnWriteArrayList<LearningListener>()
    private val noticeListeners = CopyOnWriteArrayList<RescueNoticeListener>()
    private val notices = ArrayList<RescueNotice>()

    // 救済前の記録（なければ null）。更新は Executor に閉じ、画面からの有効性の確認は notices と同じロックで守る
    private val beforeRescue = IdentityHashMap<RescueNotice, LearnedRecord?>()

    private val stored =
        StoredSnapshot(LearnedMemory.EMPTY, TAG, { store().load() }) { next ->
            listeners.forEach { it.onLearningChanged(next) }
        }

    /** 最後に読み込みまたは保存できた学習。読み込む前は空。 */
    val memory: LearnedMemory get() = stored.snapshot

    /** 保存先の読み込み状態。保存失敗では Ready の写しを保つ。 */
    val loadState: LoadState<LearnedMemory> get() = stored.state

    /** [listener] を足す。 */
    fun addListener(listener: LearningListener) {
        listeners += listener
    }

    /** [listener] を外す。 */
    fun removeListener(listener: LearningListener) {
        listeners -= listener
    }

    /** 取り消しが無効になったことを受け取る [listener] を足す。 */
    fun addNoticeListener(listener: RescueNoticeListener) {
        noticeListeners += listener
    }

    /** 取り消しが無効になったことを受け取る [listener] を外す。 */
    fun removeNoticeListener(listener: RescueNoticeListener) {
        noticeListeners -= listener
    }

    /** [notice] の取り消し材料がまだ有効かを返す。どのスレッドからでも呼べる。 */
    fun canUndo(notice: RescueNotice): Boolean = synchronized(notices) { beforeRescue.containsKey(notice) }

    /** 未読・読み込み失敗なら読み直し、結果を記録のスレッドから [onDone] に返す。 */
    fun requestLoad(onDone: (StorageResult<Unit>) -> Unit = {}) {
        executor.execute { onDone(stored.ensureLoaded()) }
    }

    /**
     * 判定 [decision] を時刻 [at] に保存し、救済の知らせを [onDone] に返す。失敗時は知らせと取り消し材料を変えない。
     * 結果は記録のスレッドから返し、成功した知らせは次に画面を開いたときにも取り出せるよう預かる。
     *
     * @param labelOf 選んだ時点で固定したアプリの表示名。わからなければ null（パッケージ名を出す）。画面の現在値を読み直さない
     */
    fun record(
        decision: LearningDecision,
        at: Long,
        onDone: (StorageResult<List<RescueNotice>>) -> Unit = {},
        labelOf: (LearnedPair) -> String?,
    ) {
        executor.execute {
            val loaded = stored.ensureLoaded()
            if (loaded is StorageResult.Failure) {
                onDone(loaded)
                return@execute
            }
            val accepted =
                decision.copy(
                    selection = decision.selection?.takeIf { isProfilePresent(it.app.profileSerial) },
                    rescues = decision.rescues.filter { isProfilePresent(it.app.profileSerial) },
                    used = decision.used?.takeIf { isProfilePresent(it.app.profileSerial) },
                )
            val old = memory
            val made = accepted.rescues.map { RescueNotice(it, labelOf(it) ?: it.app.packageName, accepted.selection, at) }
            val result = applyChange { it.recorded(accepted, at) }
            if (result is StorageResult.Failure) {
                onDone(result)
            } else {
                // 回数が上限で値が変わらない記録も、後の保存を古い取り消しで巻き戻さない
                val recordedPairs = (accepted.rescues + listOfNotNull(accepted.selection, accepted.used)).toSet()
                invalidateNotices { it.pair in recordedPairs }
                synchronized(notices) {
                    made.forEach { beforeRescue[it] = old.records[it.pair] }
                    notices += made
                }
                onDone(StorageResult.Success(made))
            }
        }
    }

    /** まだ出し終えていない知らせの写し。読むだけでは消費せず、構成の変更でも取り消す機会を保つ。 */
    val pendingNotices: List<RescueNotice> get() = synchronized(notices) { notices.toList() }

    /** [notice] を出し終えた。取り消しの保存とは別の操作なので、取り消し材料は保存の完了まで保つ。 */
    fun consumeNotice(notice: RescueNotice) {
        synchronized(notices) { notices.removeAll { it === notice } }
    }

    /**
     * 救済を記録する前に戻し、保存結果を記録のスレッドから [onDone] に返す。
     * 失敗時は再試行できるが、その間に同じ組を保存したら古い取り消しは何も変えずに成功を返す。
     */
    fun undo(
        notice: RescueNotice,
        onDone: (StorageResult<Unit>) -> Unit = {},
    ) {
        update(onDone, afterSave = { invalidateNotices { it === notice } }) { old ->
            if (!beforeRescue.containsKey(notice)) {
                old
            } else {
                old.rescueUndone(notice.pair, beforeRescue[notice], notice.selection, notice.at)
            }
        }
    }

    /** [pair] に「この語では出さない」の印を保存し、結果を記録のスレッドから [onDone] に返す。 */
    fun suppress(
        pair: LearnedPair,
        at: Long,
        onDone: (StorageResult<Unit>) -> Unit = {},
    ) {
        update(onDone) { if (isProfilePresent(pair.app.profileSerial)) it.suppressed(pair, at) else it }
    }

    /** [pair] の学習を消し、保存結果を記録のスレッドから [onDone] に返す。 */
    fun remove(
        pair: LearnedPair,
        onDone: (StorageResult<Unit>) -> Unit = {},
    ) {
        update(onDone) { it.removed(pair) }
    }

    /** 全行を消してから写しと取り消し材料を空にし、結果を記録のスレッドから [onDone] に返す。 */
    fun clear(onDone: (StorageResult<Unit>) -> Unit = {}) {
        executor.execute {
            val loaded = stored.ensureLoaded()
            if (loaded is StorageResult.Failure) {
                onDone(loaded)
                return@execute
            }
            // 差分ではなく全行を消す。旧版などのメモリに見えない行も残さないため
            val result = stored.save(memory.cleared()) { store().clear() }
            if (result is StorageResult.Success) {
                invalidateNotices { true }
            }
            onDone(result)
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
        executor.execute {
            if (stored.ensureLoaded() is StorageResult.Failure) {
                onDone(null)
                return@execute
            }
            val snapshot = memory
            val written =
                try {
                    write(LearningBackup.encode(snapshot, profiles, now))
                    snapshot.records.size
                } catch (e: IOException) {
                    Log.w(TAG, "学習を書き出せなかった: ${e.javaClass.simpleName}")
                    null
                } catch (e: RuntimeException) {
                    // 保存先の提供元が SecurityException などを投げることがある
                    Log.w(TAG, "学習を書き出せなかった: ${e.javaClass.simpleName}")
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
        executor.execute {
            if (stored.ensureLoaded() is StorageResult.Failure) {
                onDone(BackupReadResult.Failure(BackupFailure.DATABASE_READ))
                return@execute
            }
            val result =
                try {
                    read()?.let { LearningBackup.decode(it, profiles) }?.let(::acceptExistingProfiles)
                        ?: BackupReadResult.Failure(BackupFailure.TOO_LARGE)
                } catch (e: IOException) {
                    Log.w(TAG, "学習のファイルを読めなかった: ${e.javaClass.simpleName}")
                    BackupReadResult.Failure(BackupFailure.UNREADABLE)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "学習のファイルを読めなかった: ${e.javaClass.simpleName}")
                    BackupReadResult.Failure(BackupFailure.UNREADABLE)
                }
            // 同じスレッドの中なので、読み込みの結果を合わせる前にほかの記録が割り込まない
            if (result is BackupReadResult.Success && applyChange { it.merged(result.records, now) } is StorageResult.Failure) {
                onDone(BackupReadResult.Failure(BackupFailure.DATABASE_WRITE))
            } else {
                onDone(result)
            }
        }
    }

    /** 時刻 [now] で、最後に使ってから 180 日たった記録を消す（R5）。画面を開くたびに呼ぶ（読み込みの後に走る）。 */
    fun forgetExpired(
        now: Long,
        onDone: (StorageResult<Unit>) -> Unit = {},
    ) {
        update(onDone) { it.forgotten(now) }
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

    /** 共通の書き手で削除が確定した後、写しと取り消し材料を差し替える。新しい仕事は積まない。 */
    internal fun profilesRemoved(serials: Set<Long>) {
        val retained = memory.records.values.filter { it.app.profileSerial !in serials }
        if (retained.size != memory.records.size) stored.reflectSaved(LearnedMemory(retained))
        invalidateNotices { it.pair.app.profileSerial in serials || it.selection?.app?.profileSerial in serials }
    }

    private fun acceptExistingProfiles(result: BackupReadResult): BackupReadResult {
        if (result !is BackupReadResult.Success) return result
        val records = result.records.filter { isProfilePresent(it.app.profileSerial) }
        return result.copy(records = records, skippedCount = result.skippedCount + result.records.size - records.size)
    }

    private fun update(
        onDone: (StorageResult<Unit>) -> Unit,
        afterSave: () -> Unit = {},
        change: (LearnedMemory) -> LearnedMemory,
    ) {
        executor.execute {
            val loaded = stored.ensureLoaded()
            val result = if (loaded is StorageResult.Failure) loaded else applyChange(change)
            if (result is StorageResult.Success) afterSave()
            onDone(result)
        }
    }

    // 変更前の写しを保存成功まで保つ。次の差分も同じ DB の内容を基準にできるようにするため
    private fun applyChange(change: (LearnedMemory) -> LearnedMemory): StorageResult<Unit> {
        val old = memory
        val next = change(old)
        if (next === old) return StorageResult.Success(Unit)
        val result = stored.save(next) { store().save(old, next) }
        if (result is StorageResult.Success) {
            invalidateNotices { old.records[it.pair] != next.records[it.pair] }
        }
        return result
    }

    // 保存済みの新しい学習を、古いスナップショットに戻す取り消しで失わないようにする
    private fun invalidateNotices(affected: (RescueNotice) -> Boolean) {
        val invalidated =
            synchronized(notices) {
                val removed = beforeRescue.keys.filter(affected)
                removed.forEach { beforeRescue.remove(it) }
                val removedKeys = removed.map { it.undoKey }.toSet()
                notices.removeAll { it.undoKey in removedKeys }
                removed.isNotEmpty()
            }
        if (invalidated) noticeListeners.forEach { it.onNoticesInvalidated() }
    }

    private companion object {
        const val TAG = "PiuLearning"
        const val AWAIT_SECONDS = 10L
    }
}
