// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable

/** インストールの印のファイルが今は読めない（あるのに開けない）。転送かどうか決められないので、整理を保留する。 */
public class InstallationMarkerUnavailableException(
    cause: Throwable,
) : IOException("インストールの印を読めない", cause)

/** 印のファイルを読んだ結果。 */
internal sealed interface MarkerRead {
    /** 読めた。 */
    data class Present(
        val token: String,
    ) : MarkerRead

    /** ない（新規のインストール、または DB だけが転送された）。 */
    data object Absent : MarkerRead

    /** 読めたが中身が印でない（空・壊れた乱数。Android 9・10 の初回の書き込みの中断で空の本体が残る）。 */
    data object Corrupt : MarkerRead

    /** あるのに読めない。 */
    data class Unreadable(
        val cause: Throwable,
    ) : MarkerRead
}

/**
 * インストールの印のファイル（`noBackupFilesDir` の乱数）。バックアップも端末間の転送もされないので、DB の中の印と違えば転送とみなせる。
 * 0.2.0 の `InstallationGuard` と同じファイル・同じ書式にし、同じ端末の更新で印が一致するようにする。
 */
internal class InstallationMarker(
    private val file: File,
) {
    private val atomic = AtomicFile(file)

    fun read(): MarkerRead =
        try {
            // 本体がなくても、AtomicFile が前回の退避先から本体を戻してから開く
            val text = atomic.openRead().bufferedReader().use { it.readText() }
            try {
                MarkerRead.Present(UUID.fromString(text).toString())
            } catch (_: IllegalArgumentException) {
                MarkerRead.Corrupt
            }
        } catch (e: FileNotFoundException) {
            // 開けないのが「ない」からか、権限などで開けないのかを、本体の有無で分ける（退避先は openRead が本体に戻している）
            if (exists()) MarkerRead.Unreadable(e) else MarkerRead.Absent
        } catch (e: IOException) {
            MarkerRead.Unreadable(e)
        } catch (e: SecurityException) {
            MarkerRead.Unreadable(e)
        }

    // 有無を確かめられないときは、読めない側に倒す（ないと決めると、新しい印で整理してしまう）
    private fun exists(): Boolean =
        try {
            file.exists()
        } catch (_: SecurityException) {
            true
        }

    /** 新しい印を書き、その値を返す。書けなくても返す（0.2.0 と同じく、次に開くときも不一致になり、古い仕事用の記録を信用しない）。 */
    fun create(): String {
        val token = UUID.randomUUID().toString()
        try {
            // 中断で残った空の本体と古い退避先を捨て、壊れた印へ戻らないようにする
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
            Log.w(TAG, "インストールの印を保存できない: ${e.javaClass.name}")
        } catch (e: SecurityException) {
            Log.w(TAG, "インストールの印を保存できない: ${e.javaClass.name}")
        }
        return token
    }

    private companion object {
        const val TAG = "PiuRecordsGuard"
    }
}

/**
 * 記録の DB の、端末間の転送の整理。最初の読み込みの前に 1 回行う（0.2.0 の `InstallationGuard` を記録の表だけにしたもの）。
 *
 * - 印が一致する（同じインストール。同じ端末の更新を含む）: 何も消さない。DB の印に自分の番号がなければ書き足す
 * - 印のファイルがない・壊れている: 新しい印を作り、転送として整理する（0.2.0 と同じ）
 * - 印のファイルがあるのに読めない: 整理せずに [InstallationMarkerUnavailableException] を投げる。記録は読み込みの失敗になり、
 *   次の頼みで読み直す。一時的な失敗を転送とみなして、仕事用の記録を消さないため
 *
 * 転送とみなしたら、1 つのトランザクションで、このアプリが動いているユーザー以外の番号の行を消し、印を更新する。移行元の自分の番号
 * （DB の印の `owner_serial`）が分かり、今の番号と違えば、その番号の行を残して今の番号に付け替える。分からなければ（版 6 の DB・0.2 の復元）
 * 今の番号の行だけを残す。番号の変わる転送では、移行元の自分の記録も消える。移行元のほかの番号は、移行先の同じ番号の仕事用と取り違えないよう捨てる。
 */
internal class RecordsInstallationGuard(
    private val marker: InstallationMarker,
    private val ownSerial: () -> Long,
) {
    fun run(database: RecordsRoomDatabase) {
        val token =
            synchronized(LOCK) {
                when (val read = marker.read()) {
                    is MarkerRead.Present -> read.token
                    is MarkerRead.Unreadable -> throw InstallationMarkerUnavailableException(read.cause)
                    MarkerRead.Absent, MarkerRead.Corrupt -> marker.create()
                }
            }
        val own = ownSerial()
        val dao = database.recordsDao()
        // 通常の再オープンでは書き込みを取らない
        if (dao.installation()?.let { it.token == token && it.ownerSerial == own } == true) return
        database.runInTransaction(
            Callable {
                val row = dao.installation()
                if (row?.token != token) {
                    val source = row?.ownerSerial
                    val keep = if (source != null && source != own) source else own
                    dao.deleteLearnedExcept(keep)
                    dao.deleteLaunchesExcept(keep)
                    // 消した後なので、今の番号の行はなく、主キーはぶつからない
                    if (keep != own) {
                        dao.moveLearned(keep, own)
                        dao.moveLaunches(keep, own)
                    }
                }
                // 消去・付け替えと印の更新を同時に確定し、中断後に未処理の DB を同じインストールと扱わない
                dao.putInstallation(RecordsInstallationRow(token = token, ownerSerial = own))
            },
        )
    }

    private companion object {
        // 同じプロセスで 2 つの DB を開いても、印を 2 回作らない
        val LOCK = Any()
    }
}
