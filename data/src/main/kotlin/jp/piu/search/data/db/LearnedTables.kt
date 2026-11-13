// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * `learned` の行。検索語とアプリの組 1 つの学習（architecture.md の「データの保存」、learning.md の「何を覚えるか」）。
 *
 * `launch_stat` と同じく、アプリを識別子の 3 つの組で持ち、`app` の表への外部キーを張らない。
 * 索引を空にして作り直しても、学習が連鎖して消えないようにするため。
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

/** 学習の読み書き。 */
@Dao
internal interface LearnedDao {
    @Query("SELECT * FROM learned")
    fun all(): List<LearnedRow>

    @Query("DELETE FROM learned")
    fun deleteAll()

    // 外部キーも、ほかの表から参照されることもないので、置き換え（消して入れる）で足りる
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(rows: List<LearnedRow>)

    @Query(
        "DELETE FROM learned WHERE profile_serial = :profileSerial AND package_name = :packageName " +
            "AND activity_name = :activityName AND `query` = :query",
    )
    fun delete(
        profileSerial: Long,
        packageName: String,
        activityName: String,
        query: String,
    )
}
