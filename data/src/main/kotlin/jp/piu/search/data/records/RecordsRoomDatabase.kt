// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration

/**
 * 記録の DB（版 7）。学習・起動の記録・インストールの印だけを持ち、索引の表は持たない（索引は別の DB で、形が変われば捨てて作り直す）。
 *
 * 版 6 までの `piu.db`（0.2.0 以前の、索引と記録の 1 つの DB）と同じファイルを開けるよう、版の数え方を引き継ぐ。
 * `learned`・`launch_stat`・`installation` の形は版 6 と同じ。`fallbackToDestructiveMigration` は使わない: 利用者の記録まで消えるため。
 */
@Database(
    entities = [LearnedRow::class, LaunchStatRow::class, RecordsInstallationRow::class],
    version = RecordsRoomDatabase.VERSION,
    exportSchema = true,
)
internal abstract class RecordsRoomDatabase : RoomDatabase() {
    abstract fun recordsDao(): RecordsDao

    companion object {
        /** 表の定義の版。 */
        const val VERSION = 7

        /** 版の移行。0.1.x の DB からも開けるよう、版 6 までの移行を前に並べる（どれも SQL だけで、索引の表は 6 → 7 で落とす）。 */
        val MIGRATIONS: Array<Migration> =
            arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7)
    }
}

/** `installation` の行。端末間の転送を見分ける印（版 6 と同じ形）。 */
@Entity(tableName = "installation")
internal data class RecordsInstallationRow(
    @PrimaryKey val id: Int = 0,
    val token: String,
)

/** 記録の DB の読み書き。まとめた操作の区切り（トランザクション）は呼ぶ側（[RecordsDatabase]）が決める。 */
@Dao
internal interface RecordsDao {
    @Query("SELECT * FROM learned")
    fun learned(): List<LearnedRow>

    @Query("SELECT * FROM launch_stat")
    fun launches(): List<LaunchStatRow>

    // 写しに見えない行（長すぎる語の学習など）の番号も数える。整理の対象を写しの番号だけで決めないため
    @Query("SELECT profile_serial FROM learned UNION SELECT profile_serial FROM launch_stat")
    fun serials(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putLearned(rows: List<LearnedRow>)

    // 書き手が 1 つで、写しから計算した回数と時刻をそのまま書くので、加算（0.2.0 の increment）ではなく置き換える
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putLaunches(rows: List<LaunchStatRow>)

    @Query(
        "DELETE FROM learned WHERE profile_serial = :profileSerial AND package_name = :packageName " +
            "AND activity_name = :activityName AND `query` = :query",
    )
    fun deleteLearned(
        profileSerial: Long,
        packageName: String,
        activityName: String,
        query: String,
    )

    @Query("DELETE FROM learned")
    fun clearLearned()

    @Query("DELETE FROM launch_stat")
    fun clearLaunches()

    @Query("DELETE FROM learned WHERE profile_serial = :serial")
    fun deleteLearnedOf(serial: Long)

    @Query("DELETE FROM launch_stat WHERE profile_serial = :serial")
    fun deleteLaunchesOf(serial: Long)

    @Query("DELETE FROM learned WHERE profile_serial != :serial")
    fun deleteLearnedExcept(serial: Long)

    @Query("DELETE FROM launch_stat WHERE profile_serial != :serial")
    fun deleteLaunchesExcept(serial: Long)

    @Query("SELECT * FROM installation WHERE id = 0")
    fun installation(): RecordsInstallationRow?

    @Upsert
    fun putInstallation(row: RecordsInstallationRow)
}
