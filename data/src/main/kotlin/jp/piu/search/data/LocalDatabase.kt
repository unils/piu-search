// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import androidx.room.Room
import jp.piu.search.core.search.SearchIndexVersion
import jp.piu.search.data.db.PiuRoomDatabase
import java.io.Closeable

/**
 * 端末に保存するデータ（architecture.md の「データの保存」）。アプリの中で 1 つだけ開く。
 *
 * 索引（作り直せるもの）と、起動の記録・学習（作り直せないもの）を 1 つのファイルに置き、表で分ける。
 * Room の型を外に出さないよう、表ごとの読み書きの部品（[index] など）だけを見せる。
 * 読み書きはファイルに触れるので、画面のスレッドでは呼ばない（Room が例外を投げる）。
 */
public class LocalDatabase internal constructor(
    internal val room: PiuRoomDatabase,
    indexVersion: Int = SearchIndexVersion.CURRENT,
) : Closeable {
    /** 索引の表。 */
    public val index: IndexStore = IndexStore(room, indexVersion)

    /** 起動の記録の表。 */
    public val launchStats: LaunchStatStore = LaunchStatStore(room)

    /** 学習の表。 */
    public val learned: LearnedStore = LearnedStore(room)

    /** 索引・学習・起動の記録をまとめたプロファイル単位の整理。 */
    public val profiles: ProfileStore = ProfileStore(room)

    override fun close() {
        room.close()
    }

    /** 開き方。 */
    public companion object {
        /** データベースのファイルの名前。アプリの `databases` の下に置く（自動バックアップの対象外。app の backup の規則）。 */
        public const val FILE_NAME: String = "piu.db"

        /** アプリの `databases` の下のファイルを開く。なければ作る。 */
        public fun open(context: Context): LocalDatabase = open(context, FILE_NAME)

        /**
         * [name] のファイル（null ならメモリの上）を開く。テストのため、[allowMainThread] で画面のスレッドからの読み書きを許せる
         * （Robolectric はテストを画面のスレッドで動かす）。
         */
        internal fun open(
            context: Context,
            name: String?,
            indexVersion: Int = SearchIndexVersion.CURRENT,
            allowMainThread: Boolean = false,
        ): LocalDatabase {
            val appContext = context.applicationContext
            val builder =
                if (name == null) {
                    Room.inMemoryDatabaseBuilder(appContext, PiuRoomDatabase::class.java)
                } else {
                    Room.databaseBuilder(appContext, PiuRoomDatabase::class.java, name)
                }
            builder.addMigrations(*PiuRoomDatabase.MIGRATIONS)
            if (name != null) builder.addCallback(InstallationGuard(appContext))
            if (allowMainThread) builder.allowMainThreadQueries()
            return LocalDatabase(builder.build(), indexVersion)
        }
    }
}
