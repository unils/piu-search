// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import jp.piu.search.data.DatabaseWriter
import jp.piu.search.data.ProfileReconciler
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application の寿命でプロファイルの放送を受ける。削除の照合は画面を閉じていても行う。
 * 追加・再開による索引の作り直しは、検索画面が前に出ている間か、次に開いたときに行う。
 */
class ProfileLifecycle(
    private val context: Context,
    private val writer: DatabaseWriter,
    private val profiles: ProfileReconciler,
    private val requestSync: () -> Unit,
) : Closeable {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var started = false
    private var searchVisible = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                requestReconcile()
                listeners.forEach { it() }
                if (searchVisible) requestSync()
            }
        }

    /** プロセスの起動時に登録し、保存した番号を照合する。二重には登録しない。 */
    fun start() {
        if (started) return
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // OS だけが送れる放送なので、自前の権限を追加する ContextCompat の旧版向け登録は使わない
            context.registerReceiver(receiver, filter)
        }
        started = true
        requestReconcile()
    }

    /** 表示中の仕事用タブの状態を更新する [listener]。登録と通知は画面のスレッドで行う。 */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** 画面を閉じたときに [listener] を外す。 */
    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** 前面にあることを記録する。[syncNow] が false なら、開くときの同期は IndexLifecycle にまとめる。 */
    fun onSearchStarted(syncNow: Boolean = true) {
        searchVisible = true
        if (syncNow) requestSync()
    }

    /** 画面が裏に回った後は、放送だけで索引を作り直さない。 */
    fun onSearchStopped() {
        searchVisible = false
    }

    /** 受け取りを終える。プロセスの寿命より短いテストで登録を片付けるためにも使う。 */
    override fun close() {
        if (!started) return
        context.unregisterReceiver(receiver)
        started = false
    }

    private fun requestReconcile() {
        writer.execute {
            try {
                profiles.reconcile()
            } catch (e: RuntimeException) {
                // 保存が失敗したら写しを変えず、次の起動・放送・同期で照合をやり直す
                Log.w("PiuProfiles", "プロファイルの整理に失敗した: ${e.javaClass.simpleName}")
            }
        }
    }
}
