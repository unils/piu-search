// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.search.SearchIndex
import jp.piu.search.data.IndexSyncStatus
import jp.piu.search.data.IssueCategory
import jp.piu.search.data.MaterialIssue
import jp.piu.search.data.PackageKey
import jp.piu.search.data.ProfileReadStatus
import jp.piu.search.data.SyncMode
import jp.piu.search.data.SyncOutcome

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
 * 読めていない情報の一覧の 1 行。
 *
 * @property issue 欠けの行
 * @property role 組のプロファイル
 * @property label 今の索引の表示名。索引になければパッケージ名
 */
data class IssueRow(
    val issue: MaterialIssue,
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
    val status: ProfileReadStatus,
)

/**
 * 「索引の状態」の画面と報告用の文の中身（ui.md の「索引の状態」）。[jp.piu.search.data.IndexSync] の公開の値だけから作る。
 *
 * @property apps 索引のアプリ（パッケージ）の数。プロファイルの順
 * @property archived アーカイブ済みのアプリの数。まだ同期が終わっていなければ null
 * @property lastSavedAt 最後に保存できた同期の時刻（ミリ秒）。なければ null
 * @property running 同期を実行中か
 * @property outcome このプロセスの最後の同期の結果。まだなければ null
 * @property mode 最後に最後まで終わった同期の方法。まだなければ null
 * @property profiles 最後に終わった同期のプロファイルごとの結果
 * @property genresChecked 最後に終わった同期があるか（ジャンルの判定の結果を出せるか）
 * @property genreFailure ジャンルの判定の失敗の理由。成功なら null
 * @property issues 読めていない情報。区分の順、同じ区分の中は表示名の順
 */
data class IndexStatus(
    val apps: List<Pair<ProfileRole, Int>> = emptyList(),
    val archived: Int? = null,
    val lastSavedAt: Long? = null,
    val running: Boolean = false,
    val outcome: SyncOutcome? = null,
    val mode: SyncMode? = null,
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
         * 同期の公開の値から作る。
         *
         * @param index 検索に使う索引
         * @param issues 保存した欠けの行（[jp.piu.search.data.IndexSync.materialIssues]）
         * @param lastSavedAt 最後に保存できた同期の時刻
         * @param status 同期の状態
         * @param ownSerial 個人用のシリアル番号
         */
        fun of(
            index: SearchIndex,
            issues: List<MaterialIssue>,
            lastSavedAt: Long?,
            status: IndexSyncStatus,
            ownSerial: Long,
        ): IndexStatus {
            val packages = LinkedHashMap<PackageKey, String>()
            for (entry in index.inNameOrder()) {
                val id = entry.app.id
                packages.putIfAbsent(PackageKey(id.profileSerial, id.packageName), entry.app.label)
            }
            val result = status.lastResult
            val serials =
                packages.keys.map { it.profileSerial } + issues.map { it.key.profileSerial } + result?.profileResults?.keys.orEmpty()
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
            return IndexStatus(
                apps = roles.entries.sortedBy { it.value.workOrdinal }.map { (serial, role) -> role to (counts[serial] ?: 0) },
                archived = result?.archivedPackages,
                lastSavedAt = lastSavedAt,
                running = status.running,
                outcome = status.outcome,
                mode = result?.mode,
                profiles =
                    result
                        ?.profileResults
                        ?.map { (serial, read) -> ProfileLine(roles.getValue(serial), read) }
                        ?.sortedBy { it.role.workOrdinal }
                        .orEmpty(),
                genresChecked = result != null,
                genreFailure = result?.genreFailure,
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
        /** [issues] を区分ごとに数える。区分は保存せず、理由から引く。 */
        fun of(issues: Collection<MaterialIssue>): IssueTally =
            IssueTally(
                bug = issues.count { it.category == IssueCategory.SUSPECTED_BUG },
                transient = issues.count { it.category == IssueCategory.TRANSIENT },
                retry = issues.count { it.category == IssueCategory.TRANSIENT && it.retryOnce },
                deterministic = issues.count { it.category == IssueCategory.DETERMINISTIC },
            )
    }
}
