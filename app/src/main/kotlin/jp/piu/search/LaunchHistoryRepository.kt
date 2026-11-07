// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.util.Log
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.data.LaunchStatStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** 起動の記録の受け取り手。記録が変わるたびに、記録のスレッドから呼ばれる。 */
fun interface LaunchHistoryListener {
    /** 記録が [history] になった。 */
    fun onHistoryChanged(history: LaunchHistory)
}

/**
 * 起動の記録（`launch_stat`）のメモリ上の写しと、データベースへの書き込み。
 *
 * 読み込みと書き込みは [executor] の 1 本のスレッドで順に行う。読み込みが先に済んでから記録を足すので、
 * 読み込む前に起動しても、記録が読み込みで上書きされて消えることはない。
 */
class LaunchHistoryRepository(
    private val store: () -> LaunchStatStore,
    private val executor: Executor,
) {
    private val loadRequested = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<LaunchHistoryListener>()

    /** 今の記録。読み込む前は空。 */
    @Volatile
    var history: LaunchHistory = LaunchHistory.EMPTY
        private set

    /** [listener] を足す。 */
    fun addListener(listener: LaunchHistoryListener) {
        listeners += listener
    }

    /** [listener] を外す。 */
    fun removeListener(listener: LaunchHistoryListener) {
        listeners -= listener
    }

    /** データベースから記録を読む。2 回目からは何もしない。 */
    fun requestLoad() {
        if (!loadRequested.compareAndSet(false, true)) return
        executor.execute {
            try {
                publish(store().load())
            } catch (e: RuntimeException) {
                // 読めなくても、加点と空の検索語の一覧がないだけで検索はできる
                Log.w(TAG, "起動の記録を読めなかった", e)
            }
        }
    }

    /** [app] を時刻 [at] に起動したことを記録する。 */
    fun record(
        app: AppId,
        at: Long,
    ) {
        requestLoad()
        executor.execute {
            publish(history.recorded(app, at))
            try {
                store().record(app, at)
            } catch (e: RuntimeException) {
                Log.w(TAG, "起動を記録できなかった: $app", e)
            }
        }
    }

    private fun publish(next: LaunchHistory) {
        history = next
        listeners.forEach { it.onHistoryChanged(next) }
    }

    private companion object {
        const val TAG = "PiuLaunchHistory"
    }
}
