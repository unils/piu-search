// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 転送された DB を読む前に、前の端末の索引と仕事用の記録を捨てる。
 *
 * noBackupFilesDir の乱数はバックアップも端末間転送もされない。DB 内の印だけが移るので、
 * BOOT_COUNT やプロファイル番号が偶然同じでも移行を検出できる。端末の識別子は使わない。
 */
internal class InstallationGuard(
    private val context: Context,
) : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        val installationId = installationId()
        // 通常の再オープンでは書き込みを取らない。別接続の書き込み中も WAL の読み出しを妨げないため。
        if (matchesInstallation(db, installationId)) return
        db.beginTransaction()
        try {
            // ロックを待つ間に別接続が整理を済ませた場合は、二重に消さない。
            if (!matchesInstallation(db, installationId)) {
                // 古い版には印がなく、元の端末か移行先か区別できない。その場合も安全側に倒す。
                db.execSQL("DELETE FROM app")
                db.execSQL("DELETE FROM meta")
                // 主ユーザーの serial は 0。ほかの番号は、移行先の同じ番号の仕事用に取り違えないよう捨てる。
                db.execSQL("DELETE FROM learned WHERE profile_serial != 0")
                db.execSQL("DELETE FROM launch_stat WHERE profile_serial != 0")
                // 消去と印の更新を同時に確定し、中断後に未処理の DB を同じインストールだと扱わない。
                db.execSQL("INSERT OR REPLACE INTO installation (id, token) VALUES (0, ?)", arrayOf(installationId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun matchesInstallation(
        db: SupportSQLiteDatabase,
        token: String,
    ): Boolean = db.query("SELECT token FROM installation WHERE id = 0").use { it.moveToFirst() && it.getString(0) == token }

    private fun installationId(): String =
        synchronized(InstallationGuard::class.java) {
            val token = UUID.randomUUID().toString()
            try {
                val file = AtomicFile(File(context.noBackupFilesDir, "installation-id"))
                readInstallationId(file)?.let { return@synchronized it }
                // Android 9・10 の初回書き込みの中断では空の本体が残る。古い退避先も捨て、壊れた印へ戻らないようにする。
                file.delete()
                val output = file.startWrite()
                try {
                    output.write(token.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
            } catch (e: IOException) {
                Log.w(TAG, "インストールの印を保存できないため、索引と仕事用の記録を整理する", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "インストールの印を保存できないため、索引と仕事用の記録を整理する", e)
            }
            // 保存できなくても新しい印で整理して DB を開く。次のオープンでも不一致となるため、古い索引や仕事用を信用しない。
            token
        }

    private fun readInstallationId(file: AtomicFile): String? =
        try {
            // 本体がなくても、AtomicFile が前回の退避先から復元できる場合がある。
            file.openRead().bufferedReader().use { UUID.fromString(it.readText()).toString() }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        }

    private companion object {
        const val TAG = "PiuInstallationGuard"
    }
}
