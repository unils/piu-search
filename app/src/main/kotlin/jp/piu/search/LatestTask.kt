// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import java.util.concurrent.Executor

/** 未開始の仕事を最新の 1 件にまとめる。実行中の同期処理は止めず、完了結果の古さは呼び手が確かめる。 */
internal class LatestTask(
    private val executor: Executor,
) {
    private val lock = Any()
    private var pending: (() -> Unit)? = null
    private var scheduled = false

    fun execute(task: () -> Unit) {
        val submit =
            synchronized(lock) {
                pending = task
                if (scheduled) {
                    false
                } else {
                    scheduled = true
                    true
                }
            }
        if (submit) executor.execute(::drain)
    }

    fun cancel() {
        synchronized(lock) { pending = null }
    }

    private fun drain() {
        while (true) {
            val task =
                synchronized(lock) {
                    val next = pending
                    pending = null
                    if (next == null) scheduled = false
                    next
                } ?: return
            try {
                task()
            } catch (failure: Throwable) {
                synchronized(lock) {
                    scheduled = false
                    pending = null
                }
                throw failure
            }
        }
    }
}
