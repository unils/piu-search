// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import jp.piu.search.core.search.KeyForm
import jp.piu.search.core.search.SearchKeyKind

// 索引の表（architecture.md の「データの保存」の app・search_key・meta）。どれも端末のアプリから作り直せる。
// 起動の記録の表（launch_stat。M3-01）と、M4 で足す学習の表（learned）は、アプリを app の行の番号ではなく識別子の 3 つの組で持ち、ここへの外部キーを張らない。
// 索引を空にして作り直しても、学習が連鎖して消えないようにするため

/**
 * `app` の行。起動の入口 1 つ。
 *
 * [id] は検索キーを結ぶための番号で、索引を作り直すと変わる。表の外（学習など）には持ち出さない。
 */
@Entity(
    tableName = "app",
    indices = [
        // 同じ入口を二重に入れない。プロファイル・パッケージの単位で消すので、その順に並べる
        Index(value = ["profile_serial", "package_name", "activity_name"], unique = true),
    ],
)
internal data class AppRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_name") val activityName: String,
    @ColumnInfo(name = "profile_serial") val profileSerial: Long,
    val label: String,
    // パッケージの最後の更新の時刻（PackageInfo.lastUpdateTime、ミリ秒）。全件の見直しで、作り直すアプリを選ぶため。
    // 読めなかった時刻は IndexStore が Long.MIN_VALUE で表す（0 は有効な時刻）
    @ColumnInfo(name = "package_updated_at") val packageUpdatedAt: Long,
    @ColumnInfo(name = "apk_fingerprint") val apkFingerprint: String? = null,
    // 既定値は移行（Migration5To6）の SQL とそろえる。Room のスキーマの検査が、移行の後の表と entity を比べるため
    @ColumnInfo(name = "archived", defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(name = "rebuild_pending", defaultValue = "0") val rebuildPending: Boolean = false,
)

/**
 * `search_key` の行。アプリ 1 つの検索キー 1 つ。
 *
 * [position] はアプリの中でのキーの順。読み戻したときに [jp.piu.search.core.search.SearchKeyGenerator] の出した順にそろえ、
 * 保存の前と後で照合の結果を変えないため。種類と形は enum の名前で入る。名前を変えるときは索引の版を上げる
 * （版が違えば、読み出す前に索引の表を空にするので、古い名前を読むことはない）。
 * 重みは種類から照合のときに決まる（`MatchingConfig`）ので持たない。
 */
@Entity(
    tableName = "search_key",
    primaryKeys = ["app_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = AppRow::class,
            parentColumns = ["id"],
            childColumns = ["app_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SearchKeyRow(
    @ColumnInfo(name = "app_id") val appId: Long,
    val position: Int,
    val text: String,
    val kind: SearchKeyKind,
    val form: KeyForm,
    // 語の頭の位置（SearchKey.wordStarts）をコンマでつないだもの。なければ空の文字列
    @ColumnInfo(name = "word_starts") val wordStarts: String,
)

/**
 * `meta` の行。索引全体の状態で、1 行だけ置く（[id] は常に [SINGLE_ID]）。
 *
 * 行がないのは、索引をまだ作っていないか、版が変わって空にしたこと。
 */
@Entity(tableName = "meta")
internal data class MetaRow(
    @PrimaryKey val id: Int = SINGLE_ID,
    @ColumnInfo(name = "index_version") val indexVersion: Int,
    @ColumnInfo(name = "change_sequence") val changeSequence: Int?,
    @ColumnInfo(name = "boot_count") val bootCount: Int?,
    @ColumnInfo(name = "locales") val locales: String,
    @ColumnInfo(name = "build_fingerprint", defaultValue = "''") val buildFingerprint: String = "",
    @ColumnInfo(name = "app_version_code") val appVersionCode: Int? = null,
    // 表示と報告のためだけ。判断と保存前の照合には使わない
    @ColumnInfo(name = "last_saved_at") val lastSavedAt: Long? = null,
) {
    companion object {
        const val SINGLE_ID = 0
    }
}

/**
 * `material_issue` の行。組と段 1 つの材料の欠け（architecture.md の「同期のための列」）。行がない段は読めた。
 *
 * 段と理由は R8 で変わらない固定の文字列で持つ（enum の名前に頼らない）。区分は保存しない。
 */
@Entity(
    tableName = "material_issue",
    primaryKeys = ["profile_serial", "package_name", "stage"],
)
internal data class MaterialIssueRow(
    @ColumnInfo(name = "profile_serial") val profileSerial: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    val stage: String,
    val cause: String,
    val frames: String?,
    @ColumnInfo(name = "input_summary") val inputSummary: String?,
    @ColumnInfo(name = "version_code") val versionCode: Int,
    @ColumnInfo(name = "retry_once") val retryOnce: Boolean,
    @ColumnInfo(name = "first_failed_on") val firstFailedOn: String,
    @ColumnInfo(name = "last_failed_on") val lastFailedOn: String,
)
