// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.data.LaunchStatStorage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** 起動の記録の受け取り手。記録が変わるたびに、記録のスレッドから呼ばれる。 */
fun interface LaunchHistoryListener {
    /** 記録が [history] になった。 */
    fun onHistoryChanged(history: LaunchHistory)
}

/**
 * 起動の記録の写し。読み込み確認から保存・反映までを [executor] の 1 本のスレッドで順に行う。
 * 保存に失敗した操作は写しに反映せず、[StorageResult.Failure] を返す。
 */
class LaunchHistoryRepository(
    private val store: () -> LaunchStatStorage,
    private val executor: Executor,
) {
    private val listeners = CopyOnWriteArrayList<LaunchHistoryListener>()
    private val stored =
        StoredSnapshot(LaunchHistory.EMPTY, "PiuLaunchHistory", { store().load() }) { next ->
            listeners.forEach { it.onHistoryChanged(next) }
        }

    /** 最後に読み込みまたは保存できた記録。読み込む前は空。 */
    val history: LaunchHistory get() = stored.snapshot

    /** 保存先の読み込み状態。保存失敗では Ready の写しを保つ。 */
    val loadState: LoadState<LaunchHistory> get() = stored.state

    /** [listener] を足す。 */
    fun addListener(listener: LaunchHistoryListener) {
        listeners += listener
    }

    /** [listener] を外す。 */
    fun removeListener(listener: LaunchHistoryListener) {
        listeners -= listener
    }

    /** 未読・読み込み失敗なら読み直し、結果を記録のスレッドから [onDone] に返す。 */
    fun requestLoad(onDone: (StorageResult<Unit>) -> Unit = {}) {
        executor.execute { onDone(stored.ensureLoaded()) }
    }

    /**
     * [app] を時刻 [at] に開くために選んだ記録を保存し、結果を記録のスレッドから [onDone] に返す。
     * 呼び出し元は OS へ渡す前に結果を待つ。一時停止の再開確認への要求は渡さない（learning.md）。
     */
    fun record(
        app: AppId,
        at: Long,
        onDone: (StorageResult<Unit>) -> Unit = {},
    ) {
        executor.execute {
            val loaded = stored.ensureLoaded()
            onDone(if (loaded is StorageResult.Failure) loaded else stored.save(history.recorded(app, at)) { store().record(app, at) })
        }
    }

    /** 全行を消してから写しを空にし、結果を記録のスレッドから [onDone] に返す。 */
    fun clear(onDone: (StorageResult<Unit>) -> Unit) {
        executor.execute {
            val loaded = stored.ensureLoaded()
            onDone(if (loaded is StorageResult.Failure) loaded else stored.save(LaunchHistory.EMPTY) { store().clear() })
        }
    }
}
