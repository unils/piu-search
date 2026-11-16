// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Resources
import android.os.Build
import jp.piu.search.data.AppSource
import jp.piu.search.data.IssueCategory
import jp.piu.search.data.MaterialStage
import jp.piu.search.data.PackageKey
import jp.piu.search.data.ProfileReadStatus
import java.time.Instant
import java.time.ZoneOffset

/**
 * 報告用の文に入れる、端末とこのアプリの版。
 *
 * @property versionName このアプリの版の名前
 * @property versionCode このアプリの `versionCode`
 * @property androidRelease `Build.VERSION.RELEASE`
 * @property sdk API の番号
 * @property manufacturer `Build.MANUFACTURER`
 * @property model `Build.MODEL`
 * @property fingerprint `Build.FINGERPRINT`
 * @property locales 端末の言語（BCP 47 をカンマで区切る）
 */
data class DeviceInfo(
    val versionName: String,
    val versionCode: Int,
    val androidRelease: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    val locales: String,
) {
    /** 今の端末の値。 */
    companion object {
        /** 今の端末とこのアプリの値。言語はこのアプリの設定ではなく、端末の設定から読む。 */
        fun current(): DeviceInfo =
            DeviceInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                androidRelease = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                fingerprint = Build.FINGERPRINT,
                locales =
                    Resources
                        .getSystem()
                        .configuration.locales
                        .toLanguageTags(),
            )
    }
}

/**
 * 欠けのある組の、0.1.0 の不具合と同じ型を見分けるための事実（ui.md の「報告用の文」）。読めなかった値は null（文では `?`）。
 * パスは入れない（乱数の部分が端末ごとに違い、識別子に近いため）。
 *
 * @property system system のアプリか
 * @property apex APEX の中の APK か
 * @property splits split の APK の数
 * @property key 変化の検出の鍵があるか（個人用は更新時刻、仕事用は指紋）
 */
data class PackageFacts(
    val system: Boolean? = null,
    val apex: Boolean? = null,
    val splits: Int? = null,
    val key: Boolean? = null,
)

/**
 * 報告用の文（ui.md の「報告用の文」）。画面の言語によらず英語の固定の書式で、見せる文とコピーする文は同じもの。
 *
 * 表示名・例外のメッセージ・時刻（日より細かいもの）・パスは入れない。日は UTC の `YYYY-MM-DD`。
 */
object IndexReport {
    /** 文に書く欠けの行の上限。超えた分は件数だけ書く。 */
    const val MAX_ISSUES: Int = 200

    /**
     * [status] と [device] から文を作る。[facts] は欠けのある組ごとの事実で、ないものは `?` にする。
     */
    fun text(
        status: IndexStatus,
        device: DeviceInfo,
        facts: Map<PackageKey, PackageFacts>,
    ): String =
        buildString {
            appendLine("Piu Search index status")
            appendLine("app: ${device.versionName} (${device.versionCode})")
            appendLine("android: ${device.androidRelease} (API ${device.sdk})")
            appendLine("device: ${device.manufacturer} ${device.model}")
            appendLine("build: ${device.fingerprint}")
            appendLine("locales: ${device.locales}")
            val apps = status.apps.joinToString(" ") { (role, count) -> "${role.reportName}=$count" }
            appendLine("index: $apps archived=${status.archived ?: "?"}")
            appendLine("last saved: ${status.lastSavedAt?.let(::utcDate) ?: "none"}")
            val outcome = status.outcome
            appendLine(if (outcome == null) "last sync: none" else "last sync: mode=${status.mode ?: "-"} result=$outcome")
            val profiles = status.profiles.joinToString(" ") { "${it.role.reportName}=${it.status}" }
            appendLine("profiles: ${profiles.ifEmpty { "none" }}")
            appendLine(
                when {
                    !status.genresChecked -> "genres: none"
                    status.genreFailure == null -> "genres: OK"
                    else -> "genres: FAILED cause=${status.genreFailure}"
                },
            )
            val tally = status.counts
            appendLine("issues: transient=${tally.transient} retry=${tally.retry} deterministic=${tally.deterministic} bug=${tally.bug}")
            // 報告では表示名を使わないので、区分・プロファイル・パッケージ名の順に並べ、同じ端末なら毎回同じ並びにする
            val rows =
                status.issues.sortedWith(
                    compareBy({ it.rank }, { it.role.workOrdinal }, { it.issue.key.packageName }, { it.issue.stage }),
                )
            for (row in rows.take(MAX_ISSUES)) appendIssue(row, facts[row.issue.key] ?: PackageFacts())
            if (rows.size > MAX_ISSUES) appendLine("... ${rows.size - MAX_ISSUES} more")
        }.trimEnd()

    private fun StringBuilder.appendIssue(
        row: IssueRow,
        facts: PackageFacts,
    ) {
        val issue = row.issue
        val category = issue.category
        append("- ${categoryCode(category)} ${issue.stage.code} ${row.role.reportName} ${issue.key.packageName} cause=${issue.cause}")
        if (category == IssueCategory.TRANSIENT) append(" retry=${flag(issue.retryOnce)}")
        append(" system=${flag(facts.system)} apex=${flag(facts.apex)} splits=${facts.splits ?: "?"} key=${flag(facts.key)}")
        val bug = category == IssueCategory.SUSPECTED_BUG
        // 失敗したときに保存した入力の要約を使う。今の索引の表示名は、旧索引の行を保った組では失敗したときの入力と違う
        if (bug && issue.stage == MaterialStage.KEYS) issue.inputSummary?.let { append(" input=$it") }
        if (bug) append(" ver=${issue.versionCode}")
        appendLine(" since=${issue.firstFailedOn} last=${issue.lastFailedOn}")
        if (bug) {
            issue.frames
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.forEach { appendLine("    at $it") }
        }
    }

    private fun categoryCode(category: IssueCategory): String =
        when (category) {
            IssueCategory.TRANSIENT -> "TRANSIENT"
            IssueCategory.DETERMINISTIC -> "DETERMINISTIC"
            IssueCategory.SUSPECTED_BUG -> "BUG"
        }

    private fun flag(value: Boolean?): String =
        when (value) {
            true -> "Y"
            false -> "N"
            null -> "?"
        }

    /** [millis] の UTC の日。端末の時間帯は入れない。 */
    fun utcDate(millis: Long): String =
        Instant
            .ofEpochMilli(millis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toString()
}

/**
 * 欠けのある組の事実（[PackageFacts]）を端末から読む。文を作るときに 1 回だけ、裏のスレッドで呼ぶ。
 *
 * 鍵は data の一覧の観測（[AppSource.stampProfiles]）から取り、同期と同じ式で「あるか」を見る。
 * 一時停止中などで読めないプロファイルの組は、すべて `?` にする。
 */
class PackageFactsReader(
    private val context: Context,
) {
    /** [keys] の組の事実。読めなかった組は値を null にする。 */
    fun read(keys: Collection<PackageKey>): Map<PackageKey, PackageFacts> {
        if (keys.isEmpty()) return emptyMap()
        val source = AppSource(context)
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        // どの段の失敗でも、報告の文が作れないよりは `?` で出す方がよい
        val profiles = orNull { source.profiles().associateBy { it.serial } }.orEmpty()
        val stamps =
            orNull { source.stampProfiles(withUpdateTime = true) }
                .orEmpty()
                .filterValues { it.status == ProfileReadStatus.COMPLETE }
                .values
                .flatMap { it.items }
                .groupBy { PackageKey(it.id.profileSerial, it.id.packageName) }
        return keys.distinct().associateWith { key ->
            val user = profiles[key.profileSerial]?.user
            val info =
                user?.let {
                    orNull { launcherApps.getActivityList(key.packageName, it).firstOrNull()?.applicationInfo }
                }
            val stamp = stamps[key]?.firstOrNull()
            PackageFacts(
                system = info?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 },
                apex = info?.let { it.sourceDir?.startsWith("/apex/") },
                splits = info?.let { it.splitSourceDirs?.size ?: 0 },
                key = stamp?.let { if (it.isCurrentProfile) it.packageUpdatedAt != null else it.apkFingerprint != null },
            )
        }
    }
}

// Error（メモリ不足など）は捕まえない。続けて動かせる状態ではないため
private inline fun <T> orNull(block: () -> T): T? =
    try {
        block()
    } catch (_: RuntimeException) {
        null
    }
