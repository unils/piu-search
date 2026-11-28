// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.RecordsState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import jp.piu.search.data.index.IndexStatus as IndexWriterStatus

/**
 * 画面が記録の書き手（`RecordsActor`）に頼むこと。本物は [AppContainer] が書き手を包んで作り、テストでは偽物を渡す。
 * 画面は写しを読むだけで、変更の計算は書き手が自分の写しで行う（空の写しから差分を作らないため）。
 */
interface RecordsPort {
    /** 記録の写し。 */
    val state: StateFlow<RecordsState>

    /** 記録の DB が壊れていて、新しく始めたことを、まだ利用者に知らせていない。 */
    val rebuiltAfterCorruption: StateFlow<Boolean>

    /** [request] を頼む。受理した頼みは、待つのをやめても書き手が最後まで処理する。 */
    fun submit(request: RecordsRequest): Deferred<RecordsResult>

    /** [request] を頼み、結果を待つ。 */
    suspend fun request(request: RecordsRequest): RecordsResult = submit(request).await()

    /** 読めていなければ読み直し、終わっていない整理を試す（前面への復帰）。 */
    fun refresh(): Deferred<Unit>

    /** 知らせ [id] を出し終えた。 */
    fun consumeNotice(id: Long)

    /** 作り直したことを知らせた。 */
    fun acknowledgeRebuilt()
}

/** 画面と更新の後の仕事（[IndexRebuildJob]）が索引の書き手（`IndexActor`）に頼むこと。 */
interface IndexPort {
    /** 照合に使う索引（写しから作ったもの）。写しが変わるたびに、アプリの寿命の側で 1 回だけ作る。 */
    val searchIndex: StateFlow<SearchIndex>

    /** 索引の写し（一覧の状態と欠けを読むため）。 */
    val snapshot: StateFlow<IndexSnapshot>

    /** 書き手の状態。 */
    val status: StateFlow<IndexWriterStatus>

    /** 検索画面が前面か。前面になれば同期し、裏では Callback を溜めるだけにする。 */
    fun setForeground(visible: Boolean)

    /** 同期を頼む。返す [Deferred] は、この頼みを含む同期の写しを公開し、保存を試した後に終わる。画面は待たない。 */
    fun requestSync(): Deferred<Unit>
}

/** OS へアプリの起動を頼む。本物は [AppLauncher]。 */
fun interface Launcher {
    /** [app] を開く準備。選んだ時点で呼ぶ。 */
    fun prepare(app: AppId): PreparedLaunch
}

/** 計測の印（logcat のタグ `PiuTiming`）。定義は [LogTiming]。 */
interface SearchTiming {
    /** 今の時刻（起動からのミリ秒。眠っている間は進まない）。 */
    fun now(): Long

    /** 空でない索引を、検索の画面の状態に初めて当てた。 */
    fun searchReady()

    /** [acceptedAt] に受け付けた起動を、OS に頼む直前。[recorded] は記録の保存を待ったか。 */
    fun launchRequested(
        acceptedAt: Long,
        recorded: Boolean,
    )
}
