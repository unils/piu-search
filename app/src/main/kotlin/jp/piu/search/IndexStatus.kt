// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.PackageState
import jp.piu.search.core.state.Stage
import jp.piu.search.data.index.IndexStatus as IndexWriterStatus

/**
 * プロファイルの呼び名。シリアル番号は端末ごとに違い、報告にも画面にも要らないので、役割の順番で呼ぶ。
 *
 * @property workOrdinal 0 は個人用。仕事用は 1 から、シリアル番号の小さい順
 */
data class ProfileRole(
    val workOrdinal: Int,
) {
    /** 個人用か。 */
    val isPersonal: Boolean get() = workOrdinal == 0

    /** 報告用の文での名前（`personal`・`work`・`work2`…）。 */
    val reportName: String get() =
        when (workOrdinal) {
            0 -> "personal"
            1 -> "work"
            else -> "work$workOrdinal"
        }

    /** 個人用と仕事用の呼び名。 */
    companion object {
        /** 個人用。 */
        val PERSONAL: ProfileRole = ProfileRole(0)
    }
}

/**
 * 組 1 つの、最後の試みの欠け（索引の写しの `Attempt.issue` を、画面と報告で使う形にしたもの）。
 *
 * @property key 組
 * @property stage 欠けた段
 * @property cause 理由。`java.`・`android.` で始まる最寄りのクラス名か、固定の符号
 * @property category 区分
 * @property frames 不具合の疑いの行だけ、例外の先頭のフレーム
 * @property inputSummary キーの生成の不具合の疑いの行だけ、落ちた入力の種類と形
 * @property versionCode 失敗した試みの Piu Search の `versionCode`
 * @property retryOnce 次の同期で 1 回だけ読み直すか
 * @property firstFailedOn 続けて欠けている間の初めの日（UTC の `YYYY-MM-DD`）
 * @property lastFailedOn 最後に欠けた日（同上）
 */
data class IndexIssue(
    val key: PackageKey,
    val stage: Stage,
    val cause: String,
    val category: IssueCategory,
    val frames: String?,
    val inputSummary: String?,
    val versionCode: Int,
    val retryOnce: Boolean,
    val firstFailedOn: String,
    val lastFailedOn: String,
) {
    /** 作り方。 */
    companion object {
        /** [state] の最後の試みの欠け。成功なら null。 */
        fun of(state: PackageState): IndexIssue? {
            val issue = state.attempt.issue ?: return null
            val failure = issue.failure
            return IndexIssue(
                key = state.key,
                stage = issue.stage,
                cause = failure.cause,
                category = failure.category,
                frames = failure.frames,
                inputSummary = failure.inputSummary,
                versionCode = issue.version,
                retryOnce = state.attempt.retry,
                firstFailedOn = issue.firstFailedOn,
                lastFailedOn = issue.lastFailedOn,
            )
        }
    }
}

/** このプロセスの最後の同期の結果（architecture.md の「知らせ」）。上から最初に当てはまるもの。 */
enum class LastSyncResult {
    /** 同期全体の失敗。何も更新できなかった。 */
    FAILED,

    /** あるプロファイルの一覧が読めなかった、またはジャンルの判定が失敗した。一部を古いまま残した。 */
    PARTIAL,

    /** 読めていない情報がある（読めないプロファイルの保った組を含む）。 */
    ISSUES,

    /** 更新できた。 */
    OK,
}

/**
 * 読めていない情報の一覧の 1 行。
 *
 * @property issue 欠けの行
 * @property role 組のプロファイル
 * @property label 今の索引の表示名。索引になければパッケージ名
 */
data class IssueRow(
    val issue: IndexIssue,
    val role: ProfileRole,
    val label: String,
) {
    /** 画面と報告での区分の並び（不具合の疑い → 一時的 → 決定的）。一時的の中では、読み直しを待つ行を先にする。 */
    val rank: Int get() =
        when (issue.category) {
            IssueCategory.SUSPECTED_BUG -> 0
            IssueCategory.TRANSIENT -> if (issue.retryOnce) 1 else 2
            IssueCategory.DETERMINISTIC -> 3
        }
}

/**
 * プロファイル 1 つの、最後の同期での一覧の結果。
 *
 * @property role プロファイル
 * @property status 読めたか
 */
data class ProfileLine(
    val role: ProfileRole,
    val status: ListingStatus,
)

/**
 * 「索引の状態」の画面と報告用の文の中身（ui.md の「索引の状態」）。索引の写しと書き手の状態だけから作る。
 *
 * @property apps 索引のアプリ（パッケージ）の数。プロファイルの順
 * @property archived アーカイブ済みのアプリの数。まだ同期が終わっていなければ null
 * @property lastSavedAt 最後に保存できた同期の時刻（ミリ秒）。なければ null
 * @property running 同期を実行中か
 * @property outcome このプロセスの最後の同期の結果。まだなければ null
 * @property profiles 最後に終わった同期のプロファイルごとの一覧の状態
 * @property genresChecked 最後に終わった同期があるか（ジャンルの判定の結果を出せるか）
 * @property genreFailure ジャンルの判定の失敗の理由。成功なら null
 * @property issues 読めていない情報。区分の順、同じ区分の中は表示名の順
 */
data class IndexStatus(
    val apps: List<Pair<ProfileRole, Int>> = emptyList(),
    val archived: Int? = null,
    val lastSavedAt: Long? = null,
    val running: Boolean = false,
    val outcome: LastSyncResult? = null,
    val profiles: List<ProfileLine> = emptyList(),
    val genresChecked: Boolean = false,
    val genreFailure: String? = null,
    val issues: List<IssueRow> = emptyList(),
) {
    /** 索引のアプリの数の合計。 */
    val totalApps: Int get() = apps.sumOf { it.second }

    /** 区分ごとの件数。 */
    val counts: IssueTally get() = IssueTally.of(issues.map { it.issue })

    /** 作り方。 */
    companion object {
        /**
         * 索引の写し [snapshot] と書き手の状態 [status] から作る。
         *
         * 同期の結果・プロファイルの一覧・ジャンルは、このプロセスで同期が終わってからだけ出す（写しの一覧の状態は保存されていて、
         * 前のプロセスの結果を含むため）。アプリの数と読めていない情報は、保存した写しからも数える。
         *
         * @param ownSerial 個人用のシリアル番号
         */
        fun of(
            snapshot: IndexSnapshot,
            status: IndexWriterStatus,
            ownSerial: Long,
        ): IndexStatus {
            val packages = snapshot.packages.values.associate { it.key to (it.apps().firstOrNull()?.label ?: it.key.packageName) }
            val issues = snapshot.packages.values.mapNotNull(IndexIssue::of)
            val synced = status.lastSync != null
            val listings = if (synced) snapshot.listings else emptyMap()
            val serials = packages.keys.map { it.profileSerial } + issues.map { it.key.profileSerial } + listings.keys
            val roles = rolesOf(serials, ownSerial)
            val counts = packages.keys.groupingBy { it.profileSerial }.eachCount()
            val rows =
                issues
                    .map { IssueRow(it, roles.getValue(it.key.profileSerial), packages[it.key] ?: it.key.packageName) }
                    .sortedWith(
                        compareBy<IssueRow>(
                            { it.rank },
                            { it.label },
                            { it.issue.key.packageName },
                            { it.role.workOrdinal },
                            { it.issue.stage },
                        ),
                    )
            val outcome =
                when {
                    !synced -> null
                    status.syncFailure != null -> LastSyncResult.FAILED
                    listings.values.any { it == ListingStatus.FAILED } || status.genreFailure != null -> LastSyncResult.PARTIAL
                    issues.isNotEmpty() -> LastSyncResult.ISSUES
                    else -> LastSyncResult.OK
                }
            return IndexStatus(
                apps = roles.entries.sortedBy { it.value.workOrdinal }.map { (serial, role) -> role to (counts[serial] ?: 0) },
                archived = if (synced) snapshot.packages.values.count { it.observed.archived } else null,
                lastSavedAt = status.lastSavedAt,
                running = status.running,
                outcome = outcome,
                profiles =
                    listings
                        .map { (serial, read) -> ProfileLine(roles.getValue(serial), read) }
                        .sortedBy { it.role.workOrdinal },
                genresChecked = synced,
                genreFailure = status.genreFailure,
                issues = rows,
            )
        }

        // 個人用を先に、ほかのプロファイルはシリアル番号の順に仕事用・仕事用 2… とする。個人用は索引が空でも必ず置く
        private fun rolesOf(
            serials: Collection<Long>,
            ownSerial: Long,
        ): Map<Long, ProfileRole> {
            val roles = linkedMapOf(ownSerial to ProfileRole.PERSONAL)
            serials
                .filter { it != ownSerial }
                .distinct()
                .sorted()
                .forEachIndexed { i, serial -> roles[serial] = ProfileRole(i + 1) }
            return roles
        }
    }
}

/**
 * 区分ごとの欠けの件数。画面と報告では「一時的」を 1 つの区分にし、その中の読み直しを待つ数を添える。
 *
 * @property bug 不具合の疑い
 * @property transient 一時的
 * @property retry 一時的のうち、次の更新で読み直すもの
 * @property deterministic 決定的
 */
data class IssueTally(
    val bug: Int,
    val transient: Int,
    val retry: Int,
    val deterministic: Int,
) {
    /** 行の数。 */
    val total: Int get() = bug + transient + deterministic

    /** 数え方。 */
    companion object {
        /** [issues] を区分ごとに数える。 */
        fun of(issues: Collection<IndexIssue>): IssueTally =
            IssueTally(
                bug = issues.count { it.category == IssueCategory.SUSPECTED_BUG },
                transient = issues.count { it.category == IssueCategory.TRANSIENT },
                retry = issues.count { it.category == IssueCategory.TRANSIENT && it.retryOnce },
                deterministic = issues.count { it.category == IssueCategory.DETERMINISTIC },
            )
    }
}
