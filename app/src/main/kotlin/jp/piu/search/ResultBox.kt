// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.state.RecordsFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 終わった操作の結果を、画面が出し終えるまで預かる箱。
 *
 * 画面（Activity）は向きの変更などで作り直され、操作は画面を閉じた後に終わることもある。箱をプロセスに 1 つ（[AppContainer]）置き、
 * 画面は [current] を購読する。プロセスが終われば結果も消える。新しい結果は前の結果を置き換える（出すのは新しいほうだけでよい）。
 */
class ResultBox<T : Any> {
    /**
     * 預けた結果 1 つ。等しさは預けた回で決める（等しい内容の別の結果を、古い結果の [done] で消さないため）。
     *
     * @property value 結果
     */
    class Posted<T : Any> internal constructor(
        val value: T,
    )

    private val state = MutableStateFlow<Posted<T>?>(null)

    /** 預かっている結果。出し終えたらなし。 */
    val current: StateFlow<Posted<T>?> = state.asStateFlow()

    /** 結果 [value] を預ける。どのスレッドからでも呼べる。 */
    fun post(value: T): Posted<T> = Posted(value).also { state.value = it }

    /** [posted] を出し終えた。その間に新しい結果が来ていれば、そちらは残す。 */
    fun done(posted: Posted<T>) {
        // Posted は equals を上書きしないので、比べるのは預けた回そのもの
        state.compareAndSet(posted, null)
    }
}

/** 学習の書き出し・読み込みの結果（学習の一覧の画面に知らせる）。 */
sealed interface BackupOutcome {
    /**
     * 記録の読み込みか保存が失敗した。削除・全消去・一覧を開いたときの読み込みに使う。
     * @property reason 失敗の理由
     */
    data class Failed(
        val reason: RecordsFailure,
    ) : BackupOutcome

    /**
     * 書き出した。
     *
     * @property count 書き出した件数。書けなければ null
     */
    data class Exported(
        val count: Int?,
    ) : BackupOutcome

    /**
     * 読み込んだ、または読めなかった。
     *
     * @property result 読み込みの結果
     * @property accepted 今の学習と合わせた件数（消えたプロファイルのために受けなかった分を除く）
     */
    data class Imported(
        val result: BackupReadResult,
        val accepted: Int = (result as? BackupReadResult.Success)?.records?.size ?: 0,
    ) : BackupOutcome
}
