// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query

/**
 * `launch_stat` の行。アプリ 1 つの起動の回数と最後の時刻（architecture.md の「データの保存」）。
 *
 * 学習の表と同じく、アプリを識別子の 3 つの組で持ち、`app` の表への外部キーを張らない。
 * 索引を空にして作り直しても、起動の記録が連鎖して消えないようにするため。
 */
@Entity(
    tableName = "launch_stat",
    primaryKeys = ["profile_serial", "package_name", "activity_name"],
)
internal data class LaunchStatRow(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_name") val activityName: String,
    @ColumnInfo(name = "profile_serial") val profileSerial: Long,
    @ColumnInfo(name = "launch_count") val launchCount: Int,
    // 最後に起動した時刻（エポックからのミリ秒）
    @ColumnInfo(name = "last_launched_at") val lastLaunchedAt: Long,
)

/** 起動の記録の読み書き。 */
@Dao
internal interface LaunchStatDao {
    @Query("SELECT * FROM launch_stat")
    fun all(): List<LaunchStatRow>

    // UPSERT の構文（ON CONFLICT DO UPDATE）は SQLite 3.24 から。API 26 の SQLite は古いので、更新と追加に分ける
    @Query(
        "UPDATE launch_stat SET launch_count = launch_count + 1, last_launched_at = MAX(last_launched_at, :at) " +
            "WHERE profile_serial = :profileSerial AND package_name = :packageName AND activity_name = :activityName",
    )
    fun increment(
        profileSerial: Long,
        packageName: String,
        activityName: String,
        at: Long,
    ): Int

    @Insert
    fun insert(row: LaunchStatRow)
}
