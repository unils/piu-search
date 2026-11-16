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

    // 索引の版の変更で、全入口に作り直しの印を立てる（architecture.md の「プロファイルと同期全体の失敗」）
    @Query("UPDATE app SET rebuild_pending = 1")
    fun markAllPending()

    @Query("UPDATE app SET rebuild_pending = 1 WHERE profile_serial = :profileSerial AND package_name = :packageName")
    fun markPending(
        profileSerial: Long,
        packageName: String,
    )

    @Query("SELECT * FROM material_issue ORDER BY profile_serial, package_name, stage")
    fun issues(): List<MaterialIssueRow>

    @Upsert
    fun putIssue(issue: MaterialIssueRow)

    @Query("DELETE FROM material_issue WHERE profile_serial = :profileSerial AND package_name = :packageName AND stage = :stage")
    fun deleteIssue(
        profileSerial: Long,
        packageName: String,
        stage: String,
    )

    @Query("DELETE FROM material_issue WHERE profile_serial = :profileSerial")
    fun deleteProfileIssues(profileSerial: Long)

    // 次の索引にない組の行。アンインストールと入口の消滅は、ここで行が消える
    @Query(
        "DELETE FROM material_issue WHERE NOT EXISTS (SELECT 1 FROM app WHERE app.profile_serial = material_issue.profile_serial " +
            "AND app.package_name = material_issue.package_name)",
    )
    fun deleteOrphanIssues()
}
