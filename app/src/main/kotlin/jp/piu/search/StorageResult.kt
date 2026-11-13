// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.util.Log

/** 保存先の読み込み状態。「頼んだ」と「読めた」を区別し、空の写しによる上書きを防ぐ。 */
sealed interface LoadState<out T> {
    /** まだ読んでいない。 */
    data object NotLoaded : LoadState<Nothing>

    /** 読み込み中。 */
    data object Loading : LoadState<Nothing>

    /**
     * 読み込み済み。
     * @property snapshot 保存済みの写し
     */
    data class Ready<T>(
        val snapshot: T,
    ) : LoadState<T>

    /**
     * 読めなかった。次の操作で読み直す。
     * @property reason 読み込みの失敗理由
     */
    data class Failed(
        val reason: StorageFailure,
    ) : LoadState<Nothing>
}

/** 保存先の操作結果。コールバックは記録のスレッドから呼ぶ。 */
sealed interface StorageResult<out T> {
    /**
     * 保存が済んだ。
     * @property value 操作の結果
     */
    data class Success<T>(
        val value: T,
    ) : StorageResult<T>

    /**
     * 保存または読み込みが失敗した。
     * @property reason 操作の失敗理由
     */
    data class Failure(
        val reason: StorageFailure,
    ) : StorageResult<Nothing>
}

/** 失敗した操作の種類。 */
enum class StorageOperation {
    /** 保存済みの内容の読み込み。 */
    LOAD,

    /** 計算した変更の保存。 */
    SAVE,
}

/**
 * 失敗の理由。識別子を含み得る例外のメッセージと原因は保持しない。
 *
 * @property operation 失敗した操作
 * @property exceptionType 例外の型
 */
data class StorageFailure(
    val operation: StorageOperation,
    val exceptionType: String,
)

/** 状態の変更は記録の Executor に閉じる。画面には最後に保存できた写しだけを公開する。 */
internal class StoredSnapshot<T>(
    initial: T,
    private val tag: String,
    private val load: () -> T,
    private val onChanged: (T) -> Unit,
) {
    /** 最後に読み込みまたは保存できた写し。読み込みの失敗でも捨てない。 */
    @Volatile
    var snapshot: T = initial
        private set

    /** 読み込みの状態。保存の失敗は写しを無効にしない。 */
    @Volatile
    var state: LoadState<T> = LoadState.NotLoaded
        private set

    /** 読み込み済みであることを確かめ、失敗していれば読み直す。 */
    fun ensureLoaded(): StorageResult<Unit> {
        if (state is LoadState.Ready) return StorageResult.Success(Unit)
        state = LoadState.Loading
        val loaded =
            try {
                load()
            } catch (e: RuntimeException) {
                val failure = failure(StorageOperation.LOAD, e)
                state = LoadState.Failed(failure.reason)
                return failure
            }
        publish(loaded)
        return StorageResult.Success(Unit)
    }

    /** [write] が成功した後にだけ [next] を公開する。 */
    fun save(
        next: T,
        write: () -> Unit,
    ): StorageResult<Unit> {
        try {
            write()
        } catch (e: RuntimeException) {
            return failure(StorageOperation.SAVE, e)
        }
        if (next !== snapshot) publish(next)
        return StorageResult.Success(Unit)
    }

    private fun publish(next: T) {
        snapshot = next
        state = LoadState.Ready(next)
        onChanged(next)
    }

    private fun failure(
        operation: StorageOperation,
        exception: RuntimeException,
    ): StorageResult.Failure {
        val reason = StorageFailure(operation, exception.javaClass.simpleName)
        Log.w(tag, "記録の操作に失敗した: ${reason.operation}, ${reason.exceptionType}")
        return StorageResult.Failure(reason)
    }
}
