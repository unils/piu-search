// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.util.AtomicFile
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
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
        db.beginTransaction()
        try {
            val previous = db.query("SELECT token FROM installation WHERE id = 0").use { if (it.moveToFirst()) it.getString(0) else null }
            if (previous != installationId) {
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

    private fun installationId(): String =
        synchronized(InstallationGuard::class.java) {
            val file = AtomicFile(File(context.noBackupFilesDir, "installation-id"))
            if (file.baseFile.exists()) {
                // 読めない印で処理を続けず、DB の公開を止める。次の起動で再試行できる。
                return@synchronized file.openRead().bufferedReader().use { UUID.fromString(it.readText()).toString() }
            }
            val token = UUID.randomUUID().toString()
            val output = file.startWrite()
            try {
                output.write(token.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
            token
        }
}
