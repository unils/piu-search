// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * 画面が使うプロファイルの状態。
 *
 * @property own このアプリが動いているプロファイルのシリアル番号
 * @property work 仕事用のプロファイルの番号。`LauncherApps.getProfiles` の自分以外をすべて仕事用とみなす（索引に入れる範囲と同じ）
 * @property workPaused 仕事用のどれかが一時停止中か
 */
public data class Profiles(
    val own: Long,
    val work: Set<Long>,
    val workPaused: Boolean,
)

/**
 * プロファイルの状態の流れ（[profiles]）。アプリの寿命で仕事用プロファイルの放送（追加・削除・一時停止・再開）を受け、読み直す。
 *
 * 存在の一覧（`UserManager.getUserProfiles`）は持たない。記録と索引の書き手は、頼みと同期のたびに自分で問い合わせる。
 * 画面は「自分と仕事用の番号だけを出す」ので、消えたプロファイルは `getProfiles` から消えた時点で隠れる。
 * 問い合わせが失敗したら前の値を保つ（失敗を「仕事用なし」に丸めると、仕事用のアプリが一瞬消えるため）。
 */
public class ProfileWatcher internal constructor(
    private val context: Context,
    scope: CoroutineScope,
    dispatcher: CoroutineContext,
    private val read: () -> Profiles,
) {
    /** UserManager と LauncherApps から読む [ProfileWatcher]。最初の値は作るときに呼んだスレッドで読む。 */
    public constructor(
        context: Context,
        scope: CoroutineScope,
        dispatcher: CoroutineContext = Dispatchers.IO,
    ) : this(context.applicationContext, scope, dispatcher, deviceReader(context.applicationContext))

    // 最初の値は同期で読む。非同期にすると、仕事用のある利用者で切り替えが遅れて現れる（0.2.0 も画面のスレッドで読んでいた）
    private val state = MutableStateFlow(readOrNull() ?: Profiles(0, emptySet(), workPaused = false))

    /** 今のプロファイルの状態。同じ値は配らない。 */
    public val profiles: StateFlow<Profiles> = state.asStateFlow()

    private val broadcasts = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * 放送を受けて読み直し終えたこと。値が変わらない放送（再開で番号が同じ、など）も流す。
     * 記録の整理と索引の同期のきっかけに使う（[StateFlow] は同じ値を配らないので、[profiles] だけでは再試行の機会がない）。
     */
    public val changes: SharedFlow<Unit> = broadcasts.asSharedFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val fromBroadcast = AtomicBoolean(false)
    private var started = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                fromBroadcast.set(true)
                requests.trySend(Unit)
            }
        }

    init {
        // 続けて届いた頼みは 1 回の読み直しにまとめる（間の値は画面に要らない）
        scope.launch(dispatcher) {
            for (request in requests) {
                val broadcast = fromBroadcast.getAndSet(false)
                readOrNull()?.let { state.value = it }
                if (broadcast) broadcasts.emit(Unit)
            }
        }
    }

    /** 放送の受け取りを始める。2 度呼んでも 1 つしか登録しない。画面のスレッドから呼ぶ。 */
    public fun start() {
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
    }

    /** 読み直しを頼む（裏で）。前面への復帰で呼ぶ（放送を取りこぼした場合に備える）。 */
    public fun refresh() {
        requests.trySend(Unit)
    }

    /** 受け取りを終える。プロセスの寿命より短いテストで登録を片付けるために使う。 */
    public fun close() {
        if (!started) return
        context.unregisterReceiver(receiver)
        started = false
    }

    private fun readOrNull(): Profiles? =
        try {
            read()
        } catch (e: RuntimeException) {
            // binder の失敗など。型だけを残し、前の値を保つ
            Log.w(TAG, "プロファイルを読めない: ${e.javaClass.name}")
            null
        }

    private companion object {
        const val TAG = "PiuProfiles"

        fun deviceReader(context: Context): () -> Profiles {
            val userManager = context.getSystemService(UserManager::class.java)
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            return {
                val me = Process.myUserHandle()
                // 負は自分のユーザーが見つからない場合で、動いているプロセスでは起きないはず。記録の DB と同じく 0 に倒す
                val own = userManager.getSerialNumberForUser(me).takeIf { it >= 0 } ?: 0
                // 仕事用かを確かめる API（UserManager.isManagedProfile(int)）はシステムのアプリにしか使えないので、自分以外をすべて仕事用とみなす
                val others = launcherApps.profiles.filter { it != me }
                Profiles(
                    own = own,
                    work = others.map { userManager.getSerialNumberForUser(it) }.filterTo(HashSet()) { it >= 0 },
                    workPaused = others.any { userManager.isQuietModeEnabled(it) },
                )
            }
        }
    }
}
