// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 裏のスレッドで終わった操作の結果を、画面が出し終えるまで預かる箱。
 *
 * 画面（Activity）は向きの変更などで作り直される。結果を画面に直接渡すと、作り直す前の画面に届いて失われるので、
 * 箱をプロセスに 1 つ（[AppContainer]）置き、画面は前に出るたびに [current] を見る。プロセスが終われば結果も消える。
 */
class PendingResult<T : Any> {
    private val lock = Any()
    private var value: T? = null
    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()

    /** 預かっている結果。出し終えたらなし。 */
    val current: T? get() = synchronized(lock) { value }

    /** 結果 [result] を預け、受け手に知らせる。前の結果があれば置き換える（出すのは新しいほうだけでよい）。どのスレッドからでも呼べる。 */
    fun post(result: T) {
        synchronized(lock) { value = result }
        listeners.forEach { it(result) }
    }

    /** [result] を出し終えた。その間に新しい結果が来ていれば、そちらは残す。 */
    fun done(result: T) {
        synchronized(lock) { if (value === result) value = null }
    }

    /** [listener] を足す。[post] を呼んだスレッドから呼ばれる。 */
    fun addListener(listener: (T) -> Unit) {
        listeners += listener
    }

    /** [listener] を外す。 */
    fun removeListener(listener: (T) -> Unit) {
        listeners -= listener
    }
}

/** 学習の書き出し・読み込みの結果（学習の一覧の画面に知らせる）。 */
sealed interface BackupOutcome {
    /**
     * 保存先の操作が失敗した。削除・全消去・初回の読み込みにも使う。
     * @property reason 操作の失敗理由
     */
    data class Failed(
        val reason: StorageFailure,
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
     */
    data class Imported(
        val result: BackupReadResult,
    ) : BackupOutcome
}
