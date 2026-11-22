// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * 索引の DB（index.db）。索引の写しだけを持つ。記録の DB とは別のファイルで、形が変われば捨てて作り直す
 * （`fallbackToDestructiveMigration`。索引は端末から作り直せるので、移行を書かない）。
 */
@Database(
    entities = [PackageStateRow::class, ListingRow::class, SavedRow::class],
    version = IndexRoomDatabase.VERSION,
    exportSchema = true,
)
internal abstract class IndexRoomDatabase : RoomDatabase() {
    abstract fun indexDao(): IndexStateDao

    companion object {
        /** 表の定義と、行のバイト列（[StateCodec]）の形の版。どちらかを変えたら上げる。 */
        const val VERSION = 1
    }
}

/** 組 1 つの状態（[StateCodec] のバイト列）。 */
@Entity(tableName = "package_state", primaryKeys = ["profile_serial", "package_name"])
internal class PackageStateRow(
    @ColumnInfo(name = "profile_serial") val profileSerial: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "state", typeAffinity = ColumnInfo.BLOB) val state: ByteArray,
)

/** プロファイル 1 つの、最後の一覧の状態（`ListingStatus` の名前）。 */
@Entity(tableName = "listing")
internal data class ListingRow(
    @PrimaryKey @ColumnInfo(name = "profile_serial") val profileSerial: Long,
    val status: String,
)

/** 最後に保存できた時刻（1 行だけ）。 */
@Entity(tableName = "saved")
internal data class SavedRow(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

/** 索引の DB の読み書き。トランザクションの区切りは呼ぶ側（[IndexDatabase]）が決める。 */
@Dao
internal interface IndexStateDao {
    @Query("SELECT * FROM package_state")
    fun packages(): List<PackageStateRow>

    @Query("SELECT * FROM listing")
    fun listings(): List<ListingRow>

    @Query("SELECT * FROM saved WHERE id = 0")
    fun saved(): SavedRow?

    @Query("DELETE FROM package_state")
    fun clearPackages()

    @Query("DELETE FROM listing")
    fun clearListings()

    @Insert
    fun putPackages(rows: List<PackageStateRow>)

    @Insert
    fun putListings(rows: List<ListingRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSaved(row: SavedRow)
}
