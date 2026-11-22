// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import androidx.room.Room
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.PackageState
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Callable

/**
 * 読み戻した索引。
 *
 * @property snapshot 写し。読めた組だけで、整合の検査（[IndexSnapshot.restore]）に落ちた組は入れない
 * @property savedAt 最後に保存できた時刻。保存したことがなければ null
 */
internal class StoredSnapshot(
    val snapshot: IndexSnapshot,
    val savedAt: Long?,
)

/**
 * 索引の保存先。[IndexActor] だけが、1 本の列の中から順に呼ぶ。どちらの操作も、失敗したら [Exception] を投げ、DB を変えない。
 * 呼ぶ側の coroutine を止めてファイルに触れる（書き込みの途中で取り消しが届いても、トランザクションを最後まで済ませるため）。
 */
internal interface IndexStorage {
    /** 保存した写しを 1 つのトランザクションで読む。 */
    fun load(): StoredSnapshot

    /** [snapshot] を丸ごと 1 つのトランザクションで書き、保存の時刻を [savedAt] にする。 */
    fun save(
        snapshot: IndexSnapshot,
        savedAt: Long,
    )
}

/**
 * index.db。[IndexActor] の保存先で、ほかから読み書きしない。
 *
 * 失敗の分け方:
 * - 形の違う DB（版が違う）は、Room が捨てて空の DB にする（`fallbackToDestructiveMigration`）
 * - SQLite が開くときに破損を知らせたら、Room の既定（`FrameworkSQLiteOpenHelper`）がファイルを消して空から始める
 * - 開いた後の破損（[SQLiteDatabaseCorruptException]）と、版が同じなのに形が合わない（Room のスキーマの検証の [IllegalStateException]）は、
 *   その場で閉じ、ファイルを消して開き直す。読み込みなら空の写しを返し、書き込みなら新しい DB に 1 回だけ書き直す
 * - ほかの失敗（容量・入出力）は一時的とみなし、ファイルを消さずに例外を投げる（書き手が後で試す）
 *
 * 利用者には知らせない（索引は端末から作り直せるため）。読み書きはファイルに触れるので、画面のスレッドでは呼ばない。
 */
internal class IndexDatabase private constructor(
    private val context: Context,
    private val name: String?,
    private val allowMainThread: Boolean,
) : IndexStorage,
    Closeable {
    private var room = build()

    override fun load(): StoredSnapshot =
        recovering(onReset = { StoredSnapshot(IndexSnapshot.EMPTY, null) }) {
            room.runInTransaction(
                Callable {
                    val dao = room.indexDao()
                    val states = dao.packages().mapNotNull(::stateOf)
                    // 知らない状態の名前の行は捨てる（次の同期で、その回の一覧の状態に戻る）
                    val listings =
                        dao.listings().mapNotNull { row ->
                            ListingStatus.entries.firstOrNull { it.name == row.status }?.let { row.profileSerial to it }
                        }
                    StoredSnapshot(IndexSnapshot.restore(states, listings.toMap()), dao.saved()?.savedAt)
                },
            )
        }

    override fun save(
        snapshot: IndexSnapshot,
        savedAt: Long,
    ) {
        val packages = snapshot.packages.values.map { PackageStateRow(it.key.profileSerial, it.key.packageName, StateCodec.encode(it)) }
        val listings = snapshot.listings.map { (serial, status) -> ListingRow(serial, status.name) }
        val write = {
            room.runInTransaction {
                val dao = room.indexDao()
                dao.clearPackages()
                dao.clearListings()
                dao.putPackages(packages)
                dao.putListings(listings)
                dao.putSaved(SavedRow(savedAt = savedAt))
            }
        }
        recovering(onReset = write, action = write)
    }

    // 行の形が合わない（壊れた行・知らない列挙の名前）なら、その組だけを捨て、次の同期でまだない組として作り直す
    private fun stateOf(row: PackageStateRow): PackageState? =
        try {
            StateCodec.decode(PackageKey(row.profileSerial, row.packageName), row.state)
        } catch (e: IOException) {
            Log.w(TAG, "索引の行を読めない: ${e.javaClass.name}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "索引の行を読めない: ${e.javaClass.name}")
            null
        }

    private fun <T> recovering(
        onReset: () -> T,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (e: SQLiteDatabaseCorruptException) {
            reset(e)
            onReset()
        } catch (e: IllegalStateException) {
            // Room のスキーマの検証の失敗と、破損の後に閉じられた接続（SQLiteClosable）の失敗。どちらも開き直すほかない
            reset(e)
            onReset()
        }

    private fun reset(cause: Exception) {
        Log.w(TAG, "索引の DB を作り直す: ${cause.javaClass.name}")
        room.close()
        name?.let(context::deleteDatabase)
        room = build()
    }

    private fun build(): IndexRoomDatabase {
        val builder =
            if (name == null) {
                Room.inMemoryDatabaseBuilder(context, IndexRoomDatabase::class.java)
            } else {
                Room.databaseBuilder(context, IndexRoomDatabase::class.java, name)
            }
        // 版が上がっても下がっても捨てる。下げるのは、新しい版を試した後に古い版を入れ直したとき
        builder.fallbackToDestructiveMigration(dropAllTables = true)
        builder.fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        if (allowMainThread) builder.allowMainThreadQueries()
        return builder.build()
    }

    override fun close() {
        room.close()
    }

    companion object {
        private const val TAG = "PiuIndexDb"

        /**
         * アプリの `databases` の下の [name] を開く（なければ作る）。null ならメモリの上に作る。
         * テストのため、[allowMainThread] で画面のスレッドからの読み書きを許せる（Robolectric はテストを画面のスレッドで動かす）。
         */
        fun open(
            context: Context,
            name: String?,
            allowMainThread: Boolean = false,
        ): IndexDatabase = IndexDatabase(context.applicationContext, name, allowMainThread)
    }
}
