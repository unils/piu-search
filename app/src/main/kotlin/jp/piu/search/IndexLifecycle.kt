// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.data.IndexSync
import java.io.Closeable

/** プロセスの寿命で変更通知を受け、索引の作り直しと忘却のきっかけをまとめる。 */
class IndexLifecycle(
    private val sync: IndexSync,
    private val invalidate: (Set<String>) -> Unit,
    private val refreshRecords: ((StorageResult<Unit>) -> Unit) -> Unit,
) : Closeable {
    private var started = false

    /** Application の起動時に登録する。背景の Callback は通知の保持とアイコンの失効だけを行う。 */
    fun start() {
        if (started) return
        sync.addPackageListener(invalidate)
        sync.startWatching()
        started = true
    }

    /** 開くたびの同期・記録の読み込み・忘却を画面の寿命から切り離す。 */
    fun onSearchStarted(onDone: (StorageResult<Unit>) -> Unit) {
        sync.setSearchVisible(true)
        refreshRecords(onDone)
        sync.requestSync()
    }

    /** 背景では次に開くまで同期を始めない。 */
    fun onSearchStopped() {
        sync.setSearchVisible(false)
    }

    /** テストなどでプロセスより先に部品を閉じるときに登録を解く。 */
    override fun close() {
        if (!started) return
        onSearchStopped()
        sync.stopWatching()
        sync.removePackageListener(invalidate)
        started = false
    }
}
