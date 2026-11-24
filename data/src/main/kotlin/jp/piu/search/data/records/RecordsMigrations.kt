// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// piu.db の版の移行。版 6 までは 0.2.0 以前の 1 つの DB（索引・学習・起動の記録）の移行で、0.1.x・0.2.0 の DB から版 7 へ順に当てる。
// どれも SQL だけで、版 6 までの書き出したスキーマ（data/schemas/jp.piu.search.data.db.PiuRoomDatabase/）は移行のテストが使う

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

/** 3 → 4: インストールの印を足す。移行の後の整理は、noBackupFilesDir を読める [RecordsInstallationGuard] が最初の読み込みの前に行う。 */
internal object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `installation` (`id` INTEGER NOT NULL, `token` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}

/** 4 → 5: 索引の変更検出の材料を足す。学習・起動の記録・インストールの印は保つ。 */
internal object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE app ADD COLUMN apk_fingerprint TEXT")
        db.execSQL("ALTER TABLE meta ADD COLUMN build_fingerprint TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * 5 → 6: 材料の欠けの表と、索引の列を足す（0.2.0）。足した索引の表と列は、続く 6 → 7 で表ごと落とす。
 *
 * `ADD COLUMN` と `CREATE TABLE` だけにし、`app` を作り直さない。`search_key` の外部キー（連鎖削除）と、SQLite の版による
 * 表の名前の付け替えの違いに触れないため。
 */
internal object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE app ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE app ADD COLUMN rebuild_pending INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE app SET rebuild_pending = 1 WHERE apk_fingerprint IS NULL")
        db.execSQL("ALTER TABLE meta ADD COLUMN app_version_code INTEGER")
        db.execSQL("ALTER TABLE meta ADD COLUMN last_saved_at INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `material_issue` (`profile_serial` INTEGER NOT NULL, `package_name` TEXT NOT NULL, " +
                "`stage` TEXT NOT NULL, `cause` TEXT NOT NULL, `frames` TEXT, `input_summary` TEXT, `version_code` INTEGER NOT NULL, " +
                "`retry_once` INTEGER NOT NULL, `first_failed_on` TEXT NOT NULL, `last_failed_on` TEXT NOT NULL, " +
                "PRIMARY KEY(`profile_serial`, `package_name`, `stage`))",
        )
    }
}

/**
 * 6 → 7: 索引の表（`search_key`・`app`・`meta`・`material_issue`）を落とす。
 * 学習・起動の記録・印は残す。落とした索引は、索引の DB の最初の同期の全件の作り直しで戻る。
 *
 * Room は移行を 1 つのトランザクションで当てるので、途中で終了しても版 6 のまま残り、次に開くときにまた試す。
 * `search_key` は `app` への外部キーを持つので先に落とす。
 */
internal object Migration6To7 : Migration(6, 7) {
    // 途中での終了のテストが、前の一部だけを当てられるよう、文の並びとして持つ
    val statements: List<String> =
        listOf(
            "DROP TABLE IF EXISTS `search_key`",
            "DROP TABLE IF EXISTS `app`",
            "DROP TABLE IF EXISTS `meta`",
            "DROP TABLE IF EXISTS `material_issue`",
        )

    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }
}
