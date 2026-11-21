// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.io.IOException

/**
 * 破損を知らされても DB のファイルを消さない open helper。
 *
 * 既定（`SupportSQLiteOpenHelper.Callback.onCorruption`）は DB のファイルを消し、Android は開く途中の破損なら直後に開き直すので、
 * 空の DB ができて、記録が空のまま読めてしまう。記録は作り直せないので、原本をそのまま残し、調べるための写し（`<名前>.corrupt`）を作る。
 * 開き直しも同じ破損で失敗し、書き手には読み込みの失敗として届く（空の写しを公開しない）。
 */
internal class KeepOnCorruptionFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        delegate.create(
            SupportSQLiteOpenHelper.Configuration
                .builder(configuration.context)
                .name(configuration.name)
                .callback(KeepOnCorruption(configuration.callback))
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build(),
        )

    private class KeepOnCorruption(
        private val delegate: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(delegate.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

        override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = delegate.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = delegate.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            val path = db.path ?: return
            Log.w(TAG, "記録の DB の破損を検出した。原本を残す")
            try {
                File(path).copyTo(File("$path.corrupt"), overwrite = true)
            } catch (e: IOException) {
                Log.w(TAG, "破損した記録の DB の写しを作れない: ${e.javaClass.name}")
            } catch (e: SecurityException) {
                Log.w(TAG, "破損した記録の DB の写しを作れない: ${e.javaClass.name}")
            }
        }
    }

    private companion object {
        const val TAG = "PiuRecordsDb"
    }
}
