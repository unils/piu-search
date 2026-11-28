// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * このアプリの更新の知らせ（`ACTION_MY_PACKAGE_REPLACED`。OS が更新の後に、更新したアプリにだけ送る）を受け、
 * 索引の同期を [IndexRebuildJob] に預ける。キーの作り方が変わる版では最初の同期が全件の作り直しになるので、利用者が開く前に始める。
 *
 * 受け手の持ち時間（約 10 秒）の中では同期しない。預けるだけにする。
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // 守られた放送（OS だけが送れる）を受ける受け手は、action を確かめる（lint の UnsafeProtectedBroadcastReceiver）
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        IndexRebuildJob.schedule(context)
    }
}

/**
 * 更新の後の索引の同期。索引の書き手に同期を頼み、写しを公開して保存を試したら終える。
 *
 * 止められたら（[onStopJob]）待つのをやめ、預け直さない。書き手の同期は続くことがあり、
 * 終わらずにプロセスが終わっても、次に開いたときの同期が、保存できた写しから続きを読む。
 */
class IndexRebuildJob : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = appContainer
        running =
            container.appScope.launch {
                // join は頼みの取り消し（書き手が止まった）では例外を投げない。この仕事が止められたときだけ抜け、jobFinished を呼ばない
                container.indexPort.requestSync().join()
                running = null
                jobFinished(params, false)
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        return false
    }

    /** 預け方。 */
    companion object {
        /** 仕事の番号。決まった 1 つにし、何度預けても待つ仕事を 1 つにする（同じ番号で預け直すと前のものを置き換える）。 */
        const val JOB_ID: Int = 1

        private const val TAG = "PiuUpdate"

        /**
         * 同期の仕事を預ける。Android 12 以上では急ぎの仕事（expedited）にする。ふつうの仕事は、OS が資源の都合で後へずらしうるため。
         * 急ぎの枠を使い切っていて断られたら、ふつうの仕事で預け直す。
         */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val service = ComponentName(context, IndexRebuildJob::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val expedited = JobInfo.Builder(JOB_ID, service).setExpedited(true).build()
                if (scheduler.schedule(expedited) == JobScheduler.RESULT_SUCCESS) {
                    Log.i(TAG, "更新の後の同期を預けた: expedited=true")
                    return
                }
            }
            val builder = JobInfo.Builder(JOB_ID, service)
            // Android 9 は条件のない仕事を作れない（build が例外を投げる）。待ち時間 0 を条件にする
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) builder.setMinimumLatency(0)
            val result = scheduler.schedule(builder.build())
            Log.i(TAG, "更新の後の同期を預けた: expedited=false ok=${result == JobScheduler.RESULT_SUCCESS}")
        }
    }
}
