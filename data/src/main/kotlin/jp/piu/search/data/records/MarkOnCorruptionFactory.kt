// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.io.IOException

/**
 * SQLite が破損を知らせたら、「壊れて作り直した」の印 [mark] を残してから、既定の扱い（DB のファイルを消し、開き直して空から始める）に任せる
 * open helper。壊れることはまれなので、原本を残して記録を使えないままにするより、空から始めて、そのことを利用者に 1 回知らせる。
 * 印は `noBackupFilesDir` の小さなファイルで、[RecordsActor] が公開し、画面が知らせを出したら消す。
 */
internal class MarkOnCorruptionFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
    private val mark: File,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        delegate.create(
            SupportSQLiteOpenHelper.Configuration
                .builder(configuration.context)
                .name(configuration.name)
                .callback(MarkOnCorruption(configuration.callback, mark))
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build(),
        )

    private class MarkOnCorruption(
        private val delegate: SupportSQLiteOpenHelper.Callback,
        private val mark: File,
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
            Log.w(TAG, "記録の DB の破損を検出した。消して作り直す")
            // 印を残せなくても作り直す（知らせが出ないだけで、記録の機能は使える）
            try {
                mark.parentFile?.mkdirs()
                mark.createNewFile()
            } catch (e: IOException) {
                Log.w(TAG, "作り直しの印を残せない: ${e.javaClass.name}")
            } catch (e: SecurityException) {
                Log.w(TAG, "作り直しの印を残せない: ${e.javaClass.name}")
            }
            delegate.onCorruption(db)
        }
    }

    private companion object {
        const val TAG = "PiuRecordsDb"
    }
}
