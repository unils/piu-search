// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * `learned` の行。検索語とアプリの組 1 つの学習（learning.md の「何を覚えるか」）。形は版 3 から変えていない。
 *
 * アプリを識別子の 3 つの組で持ち、索引への外部キーを張らない（版 6 までは同じ DB に索引の表があり、索引を作り直しても学習が連鎖して消えないようにした）。
 */
@Entity(
    tableName = "learned",
    primaryKeys = ["profile_serial", "package_name", "activity_name", "query"],
)
internal data class LearnedRow(
    // 正規化した検索語（厳密形）
    @ColumnInfo(name = "query") val query: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_name") val activityName: String,
    @ColumnInfo(name = "profile_serial") val profileSerial: Long,
    @ColumnInfo(name = "use_count") val useCount: Int,
    // 最後に記録した時刻（エポックからのミリ秒）。R5 の忘却に使う
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
    @ColumnInfo(name = "rescued") val rescued: Boolean,
    @ColumnInfo(name = "suppressed") val suppressed: Boolean,
)

/** `launch_stat` の行。アプリ 1 つの起動の回数と最後の時刻。形は版 2 から変えていない（外部キーは [LearnedRow] と同じく張らない）。 */
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
