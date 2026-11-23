// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.state.RecordsFailure
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.UserRecords
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 学習の書き出しと読み込みで、ファイルの外の側（記録の書き手）に頼むこと。本物は `RecordsActor` の export と import。 */
interface LearningStore {
    /** それまでの頼みを当てた写しを [write] に渡す。読めていなければ [write] を呼ばずに null。 */
    suspend fun <T> export(write: suspend (UserRecords) -> T): T?

    /** [records] を、時刻 [at] に今の学習と合わせる。 */
    suspend fun import(
        at: Long,
        records: List<LearnedRecord>,
    ): RecordsResult
}

/**
 * 学習の書き出し・読み込み（learning.md の「保存と持ち出し」）。ファイルの読み書きと解析は [io] で行い、記録の書き手の列を止めない。
 * 書き手が行うのは、写しの受け渡しと、受け入れ先の確認・最新の記録との合わせ・保存だけ。
 *
 * ログには URI と例外のメッセージを出さず、例外の型だけを残す（識別子を含みうるため）。
 *
 * @param resolver アプリの `ContentResolver`（画面のものを握ると、作り直した後も古い画面を残すため）
 * @param profiles 個人用・仕事用を読み替える今の番号
 */
class LearningTransfer(
    private val store: LearningStore,
    private val resolver: ContentResolver,
    private val profiles: () -> BackupProfiles,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 今の学習を [uri] に書き出す。件数を返し、書けなければ件数なしにする。 */
    suspend fun export(uri: Uri): BackupOutcome.Exported {
        val now = clock()
        val count =
            try {
                store.export { records ->
                    withContext(io) {
                        val text = LearningBackup.encode(records.learned, profiles(), now)
                        // 既にあるファイルを選んだときに、前の中身の後ろが残らないよう切り詰める
                        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("書き出し先を開けない")
                        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        records.learned.records.size
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "学習を書き出せなかった: ${e.javaClass.simpleName}")
                null
            } catch (e: RuntimeException) {
                // 保存先の提供元が SecurityException などを投げることがある
                Log.w(TAG, "学習を書き出せなかった: ${e.javaClass.simpleName}")
                null
            }
        return BackupOutcome.Exported(count)
    }

    /**
     * [uri] の学習を読み、今の学習と合わせる。読めない・壊れている・新しい版なら、記録の書き手に何も頼まずにわけを返す。
     * 捨てた件数は、ファイルの中で捨てた分と、消えたプロファイルのために書き手が受けなかった分の和。
     */
    suspend fun import(uri: Uri): BackupOutcome.Imported {
        val decoded =
            try {
                withContext(io) {
                    readText(resolver, uri)?.let { LearningBackup.decode(it, profiles()) }
                        ?: BackupReadResult.Failure(BackupFailure.TOO_LARGE)
                }
            } catch (e: IOException) {
                Log.w(TAG, "学習のファイルを読めなかった: ${e.javaClass.simpleName}")
                BackupReadResult.Failure(BackupFailure.UNREADABLE)
            } catch (e: RuntimeException) {
                Log.w(TAG, "学習のファイルを読めなかった: ${e.javaClass.simpleName}")
                BackupReadResult.Failure(BackupFailure.UNREADABLE)
            }
        if (decoded !is BackupReadResult.Success) return BackupOutcome.Imported(decoded)
        return when (val result = store.import(clock(), decoded.records)) {
            is RecordsResult.Success -> {
                BackupOutcome.Imported(
                    decoded.copy(skippedCount = decoded.skippedCount + result.skipped),
                    accepted = decoded.records.size - result.skipped,
                )
            }

            is RecordsResult.Failure -> {
                val reason = if (result.reason == RecordsFailure.NOT_LOADED) BackupFailure.DATABASE_READ else BackupFailure.DATABASE_WRITE
                BackupOutcome.Imported(BackupReadResult.Failure(reason))
            }
        }
    }

    private companion object {
        const val TAG = "PiuLearning"
        const val BUFFER_BYTES = 16 * 1024

        /** [uri] を UTF-8 で読む。[LearningBackup.MAX_BYTES] を超えたら、そこで止めて null を返す（動画などを選んでもメモリを食わない）。 */
        fun readText(
            resolver: ContentResolver,
            uri: Uri,
        ): String? {
            val stream = resolver.openInputStream(uri) ?: throw IOException("ファイルを開けない")
            return stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > LearningBackup.MAX_BYTES) return null
                }
                out.toString(Charsets.UTF_8.name())
            }
        }
    }
}
