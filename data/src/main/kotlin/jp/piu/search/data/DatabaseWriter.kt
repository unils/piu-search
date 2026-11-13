// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.os.Process
import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * 索引・学習・起動の記録に共通の書き手。保存前の確認と保存・メモリ反映を 1 つの仕事として並べる。
 * 索引の材料の読み取りとキーの生成は外で済ませ、[run] では保存の前提の確認と保存・反映だけを行う。
 */
public class DatabaseWriter :
    Executor,
    Closeable {
    private val running = ThreadLocal<Boolean>()

    /** 重い計算から書き手を占有しないよう、同期の入口で呼び出し元を確かめるための印。 */
    internal val isCurrentThread: Boolean get() = running.get() == true
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
