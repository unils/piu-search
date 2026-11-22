// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Process
import android.os.UserManager
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.PackageKey
import jp.piu.search.data.DatabaseWriter
import jp.piu.search.data.IndexSync
import jp.piu.search.data.LocalDatabase
import jp.piu.search.data.MaterialCauses
import jp.piu.search.data.MaterialIssue
import jp.piu.search.data.MaterialStage
import jp.piu.search.data.ProfileReadStatus
import jp.piu.search.data.StoredIndex
import jp.piu.search.data.SyncReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/** 0.2.0 との差のうち、許すもの。 */
internal enum class Allowance(
    val code: String,
) {
    /** どちらかで本当の失敗（一時的・不具合の疑い・CHANGED_DURING_READ）があった組のキーと表示名。 */
    REAL_FAILURE("a"),

    /** 0.2.0 が欠けとして記録し、新しい同期が「成功した空」とするもの（名前の資源がない・署名がない・API 30 以下の仕事用にしかないアプリの別の言語の名前）の欠けの行。 */
    SUCCESS_EMPTY("b"),

    /** 欠けの行の日付・版・例外の文字列。 */
    ISSUE_DETAIL("c"),
}

/**
 * 差 1 つ。
 *
 * @property key 組
 * @property entry 入口。組の差なら null
 * @property what 何の差か（pair・entries・issue・label・keys・genres）
 * @property old 0.2.0 の値
 * @property new 新しい同期の値
 * @property allowance 許す理由。null は許さない差
 */
internal data class IndexDiff(
    val key: PackageKey,
    val entry: String?,
    val what: String,
    val old: String,
    val new: String,
    val allowance: Allowance?,
)

/**
 * 比べた結果。
 *
 * @property failedProfiles どちらかで失敗した問い合わせ（プロファイルなら "old:番号" か "new:番号"、ジャンルなら "old:genres:理由" か
 *   "new:genres:理由"）。あれば、その組やジャンルが両方で古いまま・抜けたままでも差に出ないので、比較は成り立たない
 */
internal class ComparisonReport(
    val pairs: Int,
    val diffs: List<IndexDiff>,
    val failedProfiles: List<String> = emptyList(),
) {
    /** 比較が成り立ったか。 */
    val comparable: Boolean get() = failedProfiles.isEmpty()

    /** 許す差の外の差。 */
    val disallowed: List<IndexDiff> get() = diffs.filter { it.allowance == null }

    fun render(): String =
        buildString {
            if (!comparable) appendLine("NOT COMPARABLE: failed $failedProfiles")
            appendLine("pairs=$pairs diffs=${diffs.size} disallowed=${if (comparable) disallowed.size.toString() else "unknown"}")
            Allowance.entries.forEach { a -> appendLine("allowed(${a.code})=${diffs.count { it.allowance == a }}") }
            (disallowed + diffs.filter { it.allowance != null }).forEach { d ->
                appendLine(
                    "${d.allowance?.code ?: "NG"} ${d.key.profileSerial}/${d.key.packageName}${d.entry?.let { "/$it" }.orEmpty()} " +
                        "${d.what} old=${d.old} new=${d.new}",
                )
            }
        }
}

/**
 * 0.2.0 の同期（`IndexSync`）と新しい同期（[IndexActor]）の結果を突き合わせる道具（debug 版だけ）。
 * どちらも空の DB から始めた 1 回目の同期を、同じ端末で続けて動かして比べる。
 */
internal object IndexComparison {
    /** 0.2.0 の同期の DB。製品の `piu.db` と別の名前にする。 */
    const val OLD_DB = "compare-v020.db"

    /** 新しい同期の DB。製品の index.db と別の名前にする。 */
    const val NEW_DB = "compare-v03-index.db"

    private val NOT_FOUND: String = Resources.NotFoundException::class.java.name
    private val NAME_NOT_FOUND: String = PackageManager.NameNotFoundException::class.java.name

    /** 端末で 2 つの同期を続けて動かし、比べる。終わったら 2 つの DB を消す。重いので画面のスレッドで呼ばない。 */
    fun runOnDevice(context: Context): ComparisonReport {
        @Suppress("DEPRECATION")
        val version =
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode
                .toInt()
        listOf(OLD_DB, NEW_DB).forEach(context::deleteDatabase)
        val analyzer = KuromojiReadingAnalyzer()
        try {
            val old = LocalDatabase.open(context, OLD_DB)
            val writer = DatabaseWriter()
            val oldReport: SyncReport
            val oldIndex =
                try {
                    oldReport = IndexSync(context, old, analyzer, writer, appVersionCode = version).sync()
                    old.index.load()
                } finally {
                    writer.close()
                    old.close()
                }
            val (newSnapshot, newGenreFailure) =
                runBlocking {
                    val job = SupervisorJob()
                    try {
                        val actor = IndexActor.openNamed(context, NEW_DB, CoroutineScope(job + Dispatchers.IO), version, analyzer)
                        actor.requestSync().await()
                        check(actor.status.value.syncFailure == null) { "新しい同期が失敗した: ${actor.status.value.syncFailure}" }
                        actor.snapshot.value to actor.status.value.genreFailure
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            val own = context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
            return compare(
                oldIndex,
                oldReport.profileResults,
                oldReport.genreFailure,
                newSnapshot,
                newGenreFailure,
                Build.VERSION.SDK_INT,
                own,
            )
        } finally {
            listOf(OLD_DB, NEW_DB).forEach(context::deleteDatabase)
        }
    }

    /**
     * 0.2.0 の保存した索引 [old]（同期のプロファイルごとの結果 [oldProfiles]・ジャンルの失敗 [oldGenreFailure]）と、
     * 新しい写し [new]（ジャンルの失敗 [newGenreFailure]）を比べる。
     * [sdkInt] と [ownSerial] は、API 30 以下で仕事用にしかないアプリの別の言語の名前を「成功した空」と分けるのに使う。
     */
    fun compare(
        old: StoredIndex,
        oldProfiles: Map<Long, ProfileReadStatus>,
        oldGenreFailure: String?,
        new: IndexSnapshot,
        newGenreFailure: String?,
        sdkInt: Int,
        ownSerial: Long,
    ): ComparisonReport {
        val failed =
            oldProfiles.filterValues { it == ProfileReadStatus.FAILED }.keys.map { "old:$it" } +
                new.listings
                    .filterValues { it == ListingStatus.FAILED }
                    .keys
                    .map { "new:$it" } +
                listOfNotNull(oldGenreFailure?.let { "old:genres:$it" }, newGenreFailure?.let { "new:genres:$it" })
        val isSuccessEmpty = { issue: MaterialIssue ->
            (issue.stage == MaterialStage.LABEL && issue.cause == NOT_FOUND) ||
                (issue.stage == MaterialStage.SIGNER && issue.cause == MaterialCauses.NO_SIGNERS) ||
                (
                    sdkInt < Build.VERSION_CODES.S && issue.stage == MaterialStage.LABEL && issue.cause == NAME_NOT_FOUND &&
                        issue.key.profileSerial != ownSerial
                )
        }
        val oldApps = old.apps.groupBy { PackageKey(it.app.id.profileSerial, it.app.id.packageName) }
        val oldIssues = old.issues.groupBy { PackageKey(it.key.profileSerial, it.key.packageName) }
        val newApps = new.apps().groupBy { PackageKey(it.id.profileSerial, it.id.packageName) }
        val keys = (oldApps.keys + new.packages.keys).sortedWith(compareBy({ it.profileSerial }, { it.packageName }))
        val diffs = ArrayList<IndexDiff>()
        for (key in keys) {
            val o = oldApps[key]
            val n = new.packages[key]
            if (o == null || n == null) {
                diffs += IndexDiff(key, null, "pair", (o != null).toString(), (n != null).toString(), null)
                continue
            }
            val oldEntries = o.mapTo(HashSet()) { it.app.id.activityName }
            if (oldEntries != n.observed.entries) {
                diffs +=
                    IndexDiff(
                        key,
                        null,
                        "entries",
                        oldEntries.sorted().toString(),
                        n.observed.entries
                            .sorted()
                            .toString(),
                        null,
                    )
            }
            val (emptyRows, realRows) = oldIssues[key].orEmpty().partition(isSuccessEmpty)
            emptyRows.forEach { diffs += IndexDiff(key, null, "issue", "${it.stage}:${it.cause}", "-", Allowance.SUCCESS_EMPTY) }
            val issue = n.attempt.issue
            val realFailure = realRows.isNotEmpty() || issue != null
            if (realFailure) {
                val newText = issue?.let { "${it.stage}:${it.failure.cause}" } ?: "-"
                val oldText = realRows.joinToString(",") { "${it.stage}:${it.cause}" }.ifEmpty { "-" }
                val same = realRows.firstOrNull { issue != null && it.stage.name == issue.stage.name }
                when {
                    same == null -> diffs += IndexDiff(key, null, "issue", oldText, newText, null)
                    same.cause != issue?.failure?.cause -> diffs += IndexDiff(key, null, "issue", oldText, newText, Allowance.ISSUE_DETAIL)
                }
            }
            val oldById = o.associate { it.app.id.activityName to it.app }
            val newById = newApps[key].orEmpty().associateBy { it.id.activityName }
            for (entry in (oldEntries intersect n.observed.entries).sorted()) {
                diffs += entryDiffs(key, entry, oldById.getValue(entry), newById.getValue(entry), realFailure)
            }
        }
        return ComparisonReport(keys.size, diffs, failed.sorted())
    }

    private fun entryDiffs(
        key: PackageKey,
        entry: String,
        old: IndexedApp,
        new: IndexedApp,
        realFailure: Boolean,
    ): List<IndexDiff> {
        val diffs = ArrayList<IndexDiff>()
        val allowance = if (realFailure) Allowance.REAL_FAILURE else null
        if (old.label != new.label) diffs += IndexDiff(key, entry, "label", old.label, new.label, allowance)
        val (oldGenres, oldKeys) = old.keys.toSet().partition { it.kind == SearchKeyKind.GENRE }
        val (newGenres, newKeys) = new.keys.toSet().partition { it.kind == SearchKeyKind.GENRE }
        if (oldKeys.toSet() != newKeys.toSet()) {
            diffs += IndexDiff(key, entry, "keys", text(oldKeys - newKeys.toSet()), text(newKeys - oldKeys.toSet()), allowance)
        }
        if (oldGenres.toSet() != newGenres.toSet()) diffs += IndexDiff(key, entry, "genres", text(oldGenres), text(newGenres), null)
        return diffs
    }

    private fun text(keys: Collection<SearchKey>): String = keys.map { "${it.kind}:${it.form}:${it.text.value}" }.sorted().toString()
}
