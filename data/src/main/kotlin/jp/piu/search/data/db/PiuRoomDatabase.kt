// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room のデータベース。表の定義の版は [version] で、索引の版（`SearchIndexVersion`）とは別に数える。
 *
 * 表の形を変えたら [version] を上げ、移行（Migration）を書く。`fallbackToDestructiveMigration` は使わない:
 * すべての表を消すので、起動の記録と学習まで消えるため。索引の表だけなら、移行の中で消して作り直してよい。
 */
@Database(
    entities = [AppRow::class, SearchKeyRow::class, MetaRow::class, LaunchStatRow::class, LearnedRow::class, InstallationRow::class],
    version = PiuRoomDatabase.VERSION,
    exportSchema = true,
)
internal abstract class PiuRoomDatabase : RoomDatabase() {
    abstract fun indexDao(): IndexDao

    abstract fun launchStatDao(): LaunchStatDao

    abstract fun learnedDao(): LearnedDao

    abstract fun profileDao(): ProfileDao

    companion object {
        /** 表の定義の版。 */
        const val VERSION = 5

        /** 版の移行。古い版から開くときに順に当てる。 */
        val MIGRATIONS: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5)
    }
}

/** 1 → 2: 起動の記録の表（`launch_stat`）を足す（M3-01）。索引の表には触れない。SQL は書き出したスキーマ（2.json）と同じ。 */
internal object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `launch_stat` (`package_name` TEXT NOT NULL, `activity_name` TEXT NOT NULL, " +
                "`profile_serial` INTEGER NOT NULL, `launch_count` INTEGER NOT NULL, `last_launched_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`profile_serial`, `package_name`, `activity_name`))",
        )
    }
}

/** 2 → 3: 学習の表（`learned`）を足す（M4-02）。索引と起動の記録の表には触れない。SQL は書き出したスキーマ（3.json）と同じ。 */
internal object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `learned` (`query` TEXT NOT NULL, `package_name` TEXT NOT NULL, " +
                "`activity_name` TEXT NOT NULL, `profile_serial` INTEGER NOT NULL, `use_count` INTEGER NOT NULL, " +
                "`last_used_at` INTEGER NOT NULL, `rescued` INTEGER NOT NULL, `suppressed` INTEGER NOT NULL, " +
                "PRIMARY KEY(`profile_serial`, `package_name`, `activity_name`, `query`))",
        )
    }
}

/** 3 → 4: インストールの印を足す。移行直後の整理は、noBackupFilesDir を読める InstallationGuard で行う。 */
internal object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `installation` (`id` INTEGER NOT NULL, `token` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}

/** 4 → 5: 索引の変更検出の材料を足す。利用者の学習・起動の記録・インストールの印は保つ。 */
internal object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE app ADD COLUMN apk_fingerprint TEXT")
        db.execSQL("ALTER TABLE meta ADD COLUMN build_fingerprint TEXT NOT NULL DEFAULT ''")
    }
}
