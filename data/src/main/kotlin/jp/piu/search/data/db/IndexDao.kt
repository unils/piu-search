// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/** 索引の表の読み書き。まとめた操作の区切り（トランザクション）は呼ぶ側（`IndexStore`）が決める。 */
@Dao
internal interface IndexDao {
    @Query("SELECT * FROM meta WHERE id = 0")
    fun meta(): MetaRow?

    @Upsert
    fun putMeta(meta: MetaRow)

    @Query("SELECT * FROM app ORDER BY id")
    fun apps(): List<AppRow>

    // アプリの番号と順の順に並べ、読み戻すときに保存した順を保つ
    @Query("SELECT * FROM search_key ORDER BY app_id, position")
    fun keys(): List<SearchKeyRow>

    @Insert
    fun insertApp(app: AppRow): Long

    @Insert
    fun insertKeys(keys: List<SearchKeyRow>)

    @Query("SELECT id FROM app WHERE profile_serial = :serial AND package_name = :packageName AND activity_name = :activityName")
    fun appRowId(
        serial: Long,
        packageName: String,
        activityName: String,
    ): Long

    @Query("DELETE FROM search_key WHERE app_id = :appId AND kind = 'GENRE'")
    fun deleteGenreKeys(appId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM search_key WHERE app_id = :appId")
    fun nextKeyPosition(appId: Long): Int

    // 検索キーは外部キーの CASCADE で一緒に消える
    @Query("DELETE FROM app WHERE profile_serial = :profileSerial AND package_name = :packageName")
    fun deletePackage(
        profileSerial: Long,
        packageName: String,
    )

    @Query("DELETE FROM app WHERE profile_serial = :profileSerial")
    fun deleteProfile(profileSerial: Long)

    // 学習の表には触れない。clearAllTables はすべての表を消すので使わない
    @Query("DELETE FROM app")
    fun deleteAllApps()

    @Query("DELETE FROM meta")
    fun deleteMeta()
}
