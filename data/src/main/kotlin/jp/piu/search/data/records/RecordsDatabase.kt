// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import android.os.Process
import android.os.UserManager
import androidx.room.Room
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningLimits
import jp.piu.search.core.state.RecordsWrite
import jp.piu.search.core.state.UserRecords
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.LaunchStatRow
import jp.piu.search.data.db.LearnedRow
import java.io.Closeable
import java.io.File
import java.util.concurrent.Callable

/**
 * 記録の DB（Room の版 7。学習・起動の記録・インストールの印）。[RecordsActor] の保存先で、ほかから読み書きしない。
 *
 * 読み書きはファイルに触れるので、画面のスレッドでは呼ばない（Room が例外を投げる）。
 */
public class RecordsDatabase internal constructor(
    private val room: RecordsRoomDatabase,
    private val guard: RecordsInstallationGuard?,
) : RecordsStorage,
    Closeable {
    private val dao = room.recordsDao()

    // 転送の整理が済んだか。書き手は 1 つなので同期しない。整理が確定してから立て、失敗した読み込みの再試行でも整理を省かない
    private var guarded = guard == null

    override fun load(): StoredRecords {
        guard()
        return room.runInTransaction(
            Callable {
                val rows = dao.learned()
                // 上限を超える旧版の行は写しに見えず、消す操作も届かないので、読むときに消す（0.2.0 の LearnedStore と同じ）
                val (kept, legacy) = rows.partition { LearningLimits.accepts(NormalizedText(it.query)) }
                legacy.forEach { dao.deleteLearned(it.profileSerial, it.packageName, it.activityName, it.query) }
                val launches =
                    dao.launches().associate {
                        AppId(it.packageName, it.activityName, it.profileSerial) to
                            LaunchRecord(it.launchCount, it.lastLaunchedAt)
                    }
                StoredRecords(UserRecords(LearnedMemory(kept.map(::recordOf)), LaunchHistory(launches)), dao.serials().toSet())
            },
        )
    }

    override fun write(write: RecordsWrite) {
        if (write.isEmpty) return
        // 整理の前に書いた行を、後の整理が前の端末の行と取り違えて消さないよう、書く前にも済ませる
        guard()
        room.runInTransaction {
            // 全消去と整理を先に当てる。同じ書き込みで書く行を消さないため
            if (write.clearLearned) dao.clearLearned()
            if (write.clearLaunches) dao.clearLaunches()
            write.deleteSerials.forEach {
                dao.deleteLearnedOf(it)
                dao.deleteLaunchesOf(it)
            }
            write.learnedDelete.forEach { dao.deleteLearned(it.app.profileSerial, it.app.packageName, it.app.activityName, it.query.value) }
            if (write.learnedPut.isNotEmpty()) dao.putLearned(write.learnedPut.map(::rowOf))
            if (write.launchPut.isNotEmpty()) {
                dao.putLaunches(
                    write.launchPut.map { (app, record) ->
                        LaunchStatRow(app.packageName, app.activityName, app.profileSerial, record.count, record.lastLaunchedAt)
                    },
                )
            }
        }
    }

    private fun guard() {
        if (guarded) return
        checkNotNull(guard).run(room)
        guarded = true
    }

    override fun close() {
        room.close()
    }

    /** 開き方。 */
    public companion object {
        /**
         * アプリの `databases` の下の [name] を開く。なければ作る。版 6 以前の DB なら、最初に触れたときに版 7 へ移行する。
         * 製品の `piu.db` への切り替えは別に行うので、名前は呼ぶ側が決める。
         */
        public fun open(
            context: Context,
            name: String,
        ): RecordsDatabase = open(context, name, allowMainThread = false)

        /**
         * テストのため、[allowMainThread] で画面のスレッドからの読み書きを許せる（Robolectric はテストを画面のスレッドで動かす）。
         * [name] が null ならメモリの上に作り、転送の整理をしない（印を比べる DB の印がないため）。
         */
        internal fun open(
            context: Context,
            name: String?,
            allowMainThread: Boolean,
            ownSerial: () -> Long = { ownSerialOf(context) },
        ): RecordsDatabase {
            val appContext = context.applicationContext
            val builder =
                if (name == null) {
                    Room.inMemoryDatabaseBuilder(appContext, RecordsRoomDatabase::class.java)
                } else {
                    Room.databaseBuilder(appContext, RecordsRoomDatabase::class.java, name)
                }
            builder.addMigrations(*RecordsRoomDatabase.MIGRATIONS)
            if (allowMainThread) builder.allowMainThreadQueries()
            val guard =
                name?.let {
                    RecordsInstallationGuard(
                        InstallationMarker(File(appContext.noBackupFilesDir, MARKER_FILE)),
                        ownSerial,
                    )
                }
            return RecordsDatabase(builder.build(), guard)
        }

        // 0.2.0 の InstallationGuard と同じファイル。同じ端末の更新で印を一致させるため
        internal const val MARKER_FILE = "installation-id"

        private fun ownSerialOf(context: Context): Long {
            val serial = context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
            // 負は自分のユーザーが見つからない場合で、実際には起きないはず。全件を消すより、主ユーザー（0）として扱った 0.2.0 の振る舞いに倒す
            return if (serial >= 0) serial else 0
        }

        private fun recordOf(row: LearnedRow): LearnedRecord =
            LearnedRecord(
                query = NormalizedText(row.query),
                app = AppId(row.packageName, row.activityName, row.profileSerial),
                count = row.useCount,
                lastUsedAt = row.lastUsedAt,
                rescued = row.rescued,
                suppressed = row.suppressed,
            )

        private fun rowOf(record: LearnedRecord): LearnedRow =
            LearnedRow(
                query = record.query.value,
                packageName = record.app.packageName,
                activityName = record.app.activityName,
                profileSerial = record.app.profileSerial,
                useCount = record.count,
                lastUsedAt = record.lastUsedAt,
                rescued = record.rescued,
                suppressed = record.suppressed,
            )
    }
}
