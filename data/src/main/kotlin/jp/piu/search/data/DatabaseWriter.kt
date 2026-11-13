// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.os.Process
import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * 索引・学習・起動の記録に共通の書き手。読み込み確認から保存後のメモリ反映までを 1 つの仕事として並べる。
 * [run] も同じ列を通すので、同期を直接呼ぶ場合にも別の書き込みが割り込まない。
 */
public class DatabaseWriter :
    Executor,
    Closeable {
    private val running = ThreadLocal<Boolean>()
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "piu-database-writer")
        }

    /** [command] 全体を 1 つの仕事として列の末尾に並べる。 */
    override fun execute(command: Runnable) {
        executor.execute {
            running.set(true)
            try {
                command.run()
            } finally {
                running.remove()
            }
        }
    }

    /** [action] を同じ列で実行して結果を待つ。書き手自身からの呼び出しは、その仕事の中で続ける。 */
    public fun <T> run(action: () -> T): T {
        if (running.get() == true) return action()
        val task = FutureTask(action)
        execute(task)
        return try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** 新しい仕事の受付を終える。並べた仕事は最後まで実行する。 */
    override fun close() {
        executor.shutdown()
    }
}
