// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable

/**
 * インストールの印のファイル（`noBackupFilesDir` の乱数）。バックアップも端末間の転送もされないので、DB の中の印と違えば転送とみなせる。
 * 0.2.0 の `InstallationGuard` と同じファイル・同じ書式・同じ扱いにし、同じ端末の更新で印が一致するようにする。
 */
internal class InstallationMarker(
    file: File,
) {
    private val atomic = AtomicFile(file)

    /**
     * 印を読む。ない・壊れている・読めないなら新しい印を作って返す。新しい印を保存できなくても返す
     * （0.2.0 と同じ。次に開くときも不一致になり、古い仕事用の記録を信用しない）。
     */
    fun token(): String =
        synchronized(LOCK) {
            read()?.let { return@synchronized it }
            val token = UUID.randomUUID().toString()
            try {
                // Android 9・10 の初回の書き込みの中断では空の本体が残る。古い退避先も捨て、壊れた印へ戻らないようにする
                atomic.delete()
                val output = atomic.startWrite()
                try {
                    output.write(token.toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(output)
                } catch (e: IOException) {
                    atomic.failWrite(output)
                    throw e
                }
            } catch (e: IOException) {
                Log.w(TAG, "インストールの印を保存できないため、仕事用の記録を整理する: ${e.javaClass.name}")
            } catch (e: SecurityException) {
                Log.w(TAG, "インストールの印を保存できないため、仕事用の記録を整理する: ${e.javaClass.name}")
            }
            token
        }

    private fun read(): String? =
        try {
            // 本体がなくても、AtomicFile が前回の退避先から本体を戻してから開く
            atomic.openRead().bufferedReader().use { UUID.fromString(it.readText()).toString() }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private companion object {
        const val TAG = "PiuRecordsGuard"

        // 同じプロセスで 2 つの DB を開いても、印を 2 回作らない
        val LOCK = Any()
    }
}

/**
 * 記録の DB の、端末間の転送の整理。最初の読み書きの前に 1 回行う（0.2.0 の `InstallationGuard` を記録の表だけにしたもの）。
 *
 * 印が一致すれば（同じインストール。同じ端末の更新を含む）何も消さない。違えば、1 つのトランザクションで、このアプリが動いている
 * ユーザー以外の番号の行を消し、印を更新する。移行元のほかの番号は、移行先の同じ番号の仕事用と取り違えないよう捨てる。
 * 番号の変わる転送（移行元と移行先で自分の番号が違う）では、自分の記録も消える。
 *
 * @param ownSerial このアプリが動いているユーザーのシリアル番号
 */
internal class RecordsInstallationGuard(
    private val marker: InstallationMarker,
    private val ownSerial: () -> Long,
) {
    fun run(database: RecordsRoomDatabase) {
        val token = marker.token()
        val dao = database.recordsDao()
        // 通常の再オープンでは書き込みを取らない
        if (dao.installation()?.token == token) return
        database.runInTransaction(
            Callable {
                // 外で読んだ後に別の接続が整理を済ませた場合は、二重に消さない
                if (dao.installation()?.token == token) return@Callable
                val own = ownSerial()
                dao.deleteLearnedExcept(own)
                dao.deleteLaunchesExcept(own)
                // 消去と印の更新を同時に確定し、中断後に未処理の DB を同じインストールと扱わない
                dao.putInstallation(RecordsInstallationRow(token = token))
            },
        )
    }
}
