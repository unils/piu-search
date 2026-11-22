// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * adb から [IndexComparison] を動かす入口（debug 版だけ）。結果は `files/index-compare.txt` と logcat（タグ `PiuIndexCompare`）に出す。
 * 送れるのは `DUMP` の権限を持つもの（adb の shell）だけにする（マニフェストの `android:permission`）。
 */
internal class IndexCompareReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val app = context.applicationContext
        // 2 回の全件の同期は数秒かかるので、受け取りのスレッド（画面のスレッド）の外で行う
        thread(name = "piu-index-compare") {
            try {
                val text = IndexComparison.runOnDevice(app).render()
                File(app.filesDir, OUTPUT).writeText(text)
                text.lineSequence().filter { it.isNotEmpty() }.forEach { Log.i(TAG, it) }
            } catch (e: Exception) {
                Log.e(TAG, "比べられなかった", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PiuIndexCompare"
        const val OUTPUT = "index-compare.txt"
    }
}
