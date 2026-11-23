// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import jp.piu.search.core.state.RecordsFailure

/** 画面が閉じても出す短い知らせ（Toast）。 */
sealed interface UserMessage {
    /**
     * 記録の読み込みか保存に失敗した（検索画面での抑制・削除・取り消し・開いたときの読み込みと忘却）。
     *
     * @property failure 理由
     */
    data class RecordsFailed(
        val failure: RecordsFailure,
    ) : UserMessage

    /**
     * OS がアプリを開かなかった。
     *
     * @property label アプリの表示名
     */
    data class LaunchFailed(
        val label: String,
    ) : UserMessage

    /**
     * アプリ情報を開けなかった。
     *
     * @property label アプリの表示名
     */
    data class AppInfoFailed(
        val label: String,
    ) : UserMessage

    /**
     * 救済を覚えた（開いたアプリの上に出す）。
     *
     * @property query 検索語
     * @property label アプリの表示名
     */
    data class RescueLearned(
        val query: String,
        val label: String,
    ) : UserMessage
}

/** 知らせを出す口。テストでは偽物を渡す。 */
fun interface UserMessages {
    /** [message] を出す。どのスレッドから呼んでもよい。 */
    fun show(message: UserMessage)
}

/** アプリの [Context] で Toast を出す。画面（Activity）を握らないので、画面を閉じた後に終わった操作の知らせも出せる。 */
class ToastMessages(
    context: Context,
) : UserMessages {
    private val context = context.applicationContext

    override fun show(message: UserMessage) {
        ContextCompat.getMainExecutor(context).execute {
            val (text, length) =
                when (message) {
                    is UserMessage.RecordsFailed -> {
                        val id =
                            when (message.failure) {
                                RecordsFailure.NOT_LOADED -> R.string.search_record_load_failed
                                RecordsFailure.WRITE_FAILED -> R.string.search_record_failed
                            }
                        context.getString(id) to Toast.LENGTH_SHORT
                    }

                    is UserMessage.LaunchFailed -> {
                        context.getString(R.string.launch_failed, message.label) to Toast.LENGTH_SHORT
                    }

                    is UserMessage.AppInfoFailed -> {
                        context.getString(R.string.app_info_failed, message.label) to Toast.LENGTH_SHORT
                    }

                    is UserMessage.RescueLearned -> {
                        context.getString(R.string.rescue_learned, message.query, message.label) to Toast.LENGTH_LONG
                    }
                }
            Toast.makeText(context, text, length).show()
        }
    }
}

/**
 * 計測の印を logcat（タグ `PiuTiming`、情報の水準）に出す。識別子は出さず、時間だけを残す。時刻はすべて `SystemClock.uptimeMillis()`。
 *
 * - `searchReady ms=<整数>`: プロセスの開始（`Process.getStartUptimeMillis()`）から、このプロセスで初めて空でない索引を検索の画面の状態に当てたときまで。
 *   プロセスに 1 回だけ出す
 * - `launchRequested ms=<整数> recorded=<true|false>`: 行を押して起動を受け付けたときから、`LauncherApps.startMainActivity` を呼ぶ直前まで。
 *   recorded は記録の保存を待ったか（一時停止中の仕事用アプリへの要求では待たない）
 *
 * 索引の同期の時間は、data の索引の書き手が `PiuIndexSync` に出す。
 */
class LogTiming : SearchTiming {
    @Volatile private var readyLogged = false

    override fun now(): Long = SystemClock.uptimeMillis()

    override fun searchReady() {
        if (readyLogged) return
        readyLogged = true
        Log.i(TAG, "searchReady ms=${now() - Process.getStartUptimeMillis()}")
    }

    override fun launchRequested(
        acceptedAt: Long,
        recorded: Boolean,
    ) {
        Log.i(TAG, "launchRequested ms=${now() - acceptedAt} recorded=$recorded")
    }

    private companion object {
        const val TAG = "PiuTiming"
    }
}
