// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.SearchQuery
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 書き出しと読み込みで、プロファイルのシリアル番号を「個人用・仕事用」に読み替えるための今の端末の番号。
 *
 * シリアル番号は端末ごとに振られるので、別の端末へ持ち出すと同じ番号が同じプロファイルを指すとは限らない。
 * そこで個人用（このアプリが動いているプロファイル）か仕事用かを書き、読み込むときに今の端末の番号に直す。
 *
 * @property own このアプリが動いているプロファイルの番号
 * @property work 仕事用プロファイルの番号
 */
data class BackupProfiles(
    val own: Long,
    val work: Set<Long>,
)

/** 学習のファイルを読んだ結果。 */
sealed interface BackupReadResult {
    /**
     * 読めた。
     *
     * @property records 読んだ記録。プロファイルは今の端末の番号に直してある
     */
    data class Success(
        val records: List<LearnedRecord>,
    ) : BackupReadResult

    /**
     * 読めなかった。今の学習は変えない。
     *
     * @property reason 読めなかったわけ
     */
    data class Failure(
        val reason: BackupFailure,
    ) : BackupReadResult
}

/** 学習のファイルを読めなかったわけ。 */
enum class BackupFailure {
    /** ファイルを開けない・読めない。 */
    UNREADABLE,

    /** 大きすぎる（[LearningBackup.MAX_BYTES] を超える）。学習の書き出しではない見込み。 */
    TOO_LARGE,

    /** JSON でない・このアプリの学習の書き出しでない・中身が壊れている。 */
    INVALID,

    /** 新しい版のアプリで書き出した（[LearningBackup.VERSION] より新しい版）。読めない項目を落とさないよう、何も読まない。 */
    NEWER_VERSION,
}

/**
 * 学習の書き出しの書式（JSON。learning.md の「保存と持ち出し」）。
 *
 * 中身は学習の記録だけ。打った文字の列（セッションの出来事）は持たない。依存を足さないよう、Android にある `org.json` で読み書きする。
 *
 * ```json
 * {
 *   "format": "jp.piu.search.learning",
 *   "version": 1,
 *   "exportedAt": 1793232000000,
 *   "records": [
 *     {"query": "おかね", "package": "jp.ne.paypay.android.app", "activity": "jp.ne.paypay.android.app.MainActivity",
 *      "profile": "personal", "profileSerial": 0, "count": 2, "lastUsedAt": 1793232000000,
 *      "rescued": true, "suppressed": false}
 *   ]
 * }
 * ```
 *
 * - `version` は書式の版。項目の意味を変えたり、読み落とすと困る項目を足したりしたら上げる。読めない版は何も読まない
 * - `query` は正規化した検索語（厳密形）。読み込むときに今の版の正規化でもう一度揃える
 * - `profile` は `personal`（個人用）か `work`（仕事用）。`profileSerial` は書き出した端末での番号で、仕事用の読み替えにだけ使う
 * - 時刻はエポックからのミリ秒
 * - 1 つでも壊れた記録があれば、ファイル全体を読まない（一部だけ読むと、どこまで戻ったかわからないため）
 */
object LearningBackup {
    /** 書式の名前。ほかの JSON を取り違えて読まないための印。 */
    const val FORMAT = "jp.piu.search.learning"

    /** 今の書式の版。 */
    const val VERSION = 1

    /** 読むファイルの大きさの上限（バイト）。記録 1 件は 300 バイトほどなので、数万件まで入る。 */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** 学習 [memory] を、時刻 [exportedAt] の書き出しとして JSON の文字列にする。 */
    fun encode(
        memory: LearnedMemory,
        profiles: BackupProfiles,
        exportedAt: Long,
    ): String {
        val records = JSONArray()
        // 並びを決めておく（同じ学習なら同じファイルになり、差分を見やすい）
        memory.records.values
            .sortedWith(compareBy({ it.query.value }, { it.app.packageName }, { it.app.activityName }, { it.app.profileSerial }))
            .forEach { record ->
                records.put(
                    JSONObject()
                        .put(KEY_QUERY, record.query.value)
                        .put(KEY_PACKAGE, record.app.packageName)
                        .put(KEY_ACTIVITY, record.app.activityName)
                        .put(KEY_PROFILE, if (record.app.profileSerial == profiles.own) PERSONAL else WORK)
                        .put(KEY_PROFILE_SERIAL, record.app.profileSerial)
                        .put(KEY_COUNT, record.count)
                        .put(KEY_LAST_USED_AT, record.lastUsedAt)
                        .put(KEY_RESCUED, record.rescued)
                        .put(KEY_SUPPRESSED, record.suppressed),
                )
            }
        return JSONObject()
            .put(KEY_FORMAT, FORMAT)
            .put(KEY_VERSION, VERSION)
            .put(KEY_EXPORTED_AT, exportedAt)
            .put(KEY_RECORDS, records)
            .toString(2)
    }

    /** JSON の文字列 [text] を読む。落ちずに、読めなければ [BackupReadResult.Failure] を返す。 */
    fun decode(
        text: String,
        profiles: BackupProfiles,
    ): BackupReadResult =
        try {
            decodeOrThrow(JSONObject(text), profiles)
        } catch (_: JSONException) {
            BackupReadResult.Failure(BackupFailure.INVALID)
        } catch (_: InvalidBackup) {
            BackupReadResult.Failure(BackupFailure.INVALID)
        }

    private fun decodeOrThrow(
        root: JSONObject,
        profiles: BackupProfiles,
    ): BackupReadResult {
        if (root.opt(KEY_FORMAT) != FORMAT) throw InvalidBackup()
        val version = root.long(KEY_VERSION)
        if (version > VERSION) return BackupReadResult.Failure(BackupFailure.NEWER_VERSION)
        if (version < 1) throw InvalidBackup()
        val array = root.opt(KEY_RECORDS) as? JSONArray ?: throw InvalidBackup()
        val records = (0 until array.length()).map { i -> recordOf(array.opt(i) as? JSONObject ?: throw InvalidBackup(), profiles) }
        return BackupReadResult.Success(records)
    }

    private fun recordOf(
        item: JSONObject,
        profiles: BackupProfiles,
    ): LearnedRecord {
        // 正規化の決まりが版で変わっても、今の照合で引ける形に揃える
        val query = SearchQuery.of(item.text(KEY_QUERY)).strict
        if (query.isEmpty) throw InvalidBackup()
        val serial =
            when (item.opt(KEY_PROFILE) as? String) {
                PERSONAL -> profiles.own

                // 仕事用が 1 つだけなら、それに読み替える。ないか 2 つ以上なら、書き出した端末の番号のまま残す（索引にないアプリの記録と同じく、効かないだけ）
                WORK -> profiles.work.singleOrNull() ?: item.long(KEY_PROFILE_SERIAL)

                else -> throw InvalidBackup()
            }
        val count = item.long(KEY_COUNT)
        val lastUsedAt = item.long(KEY_LAST_USED_AT)
        if (count !in 0..Int.MAX_VALUE.toLong() || lastUsedAt < 0) throw InvalidBackup()
        return LearnedRecord(
            query = query,
            app = AppId(item.text(KEY_PACKAGE), item.text(KEY_ACTIVITY), serial),
            count = count.toInt(),
            lastUsedAt = lastUsedAt,
            rescued = item.flag(KEY_RESCUED),
            suppressed = item.flag(KEY_SUPPRESSED),
        )
    }

    // org.json の getInt などは文字列の "2" も数に直して受けるので、型を自分で確かめる

    private fun JSONObject.text(key: String): String = (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: throw InvalidBackup()

    private fun JSONObject.long(key: String): Long {
        val value = opt(key)
        if (value !is Int && value !is Long) throw InvalidBackup()
        return (value as Number).toLong()
    }

    private fun JSONObject.flag(key: String): Boolean = opt(key) as? Boolean ?: throw InvalidBackup()

    private class InvalidBackup : Exception()

    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_RECORDS = "records"
    private const val KEY_QUERY = "query"
    private const val KEY_PACKAGE = "package"
    private const val KEY_ACTIVITY = "activity"
    private const val KEY_PROFILE = "profile"
    private const val KEY_PROFILE_SERIAL = "profileSerial"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST_USED_AT = "lastUsedAt"
    private const val KEY_RESCUED = "rescued"
    private const val KEY_SUPPRESSED = "suppressed"
    private const val PERSONAL = "personal"
    private const val WORK = "work"
}
