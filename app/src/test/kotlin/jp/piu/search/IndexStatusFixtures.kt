// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.state.Attempt
import jp.piu.search.core.state.Current
import jp.piu.search.core.state.DerivedKey
import jp.piu.search.core.state.Environment
import jp.piu.search.core.state.Failure
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.Issue
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.MaterialKey
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.PackageObservation
import jp.piu.search.core.state.PackageState
import jp.piu.search.core.state.Stage
import jp.piu.search.data.index.SyncSummary
import jp.piu.search.data.index.IndexStatus as IndexWriterStatus

// 「索引の状態」・索引の知らせ・報告用の文のテストが共有する、索引の写しと書き手の状態
internal object IndexStatusFixtures {
    const val PERSONAL = 0L
    const val WORK = 10L

    val NOT_FOUND = "android.content.res.Resources\$NotFoundException"

    private val ENV = Environment("ja-JP", "build-1", 1, 1, "brand-1", 5, 1)

    /** 欠けの行 1 つ。区分は読み手が理由から決める（ここでは 0.2.0 と同じ分け方を写す）。 */
    fun issue(
        packageName: String,
        stage: Stage,
        cause: String,
        serial: Long = PERSONAL,
        retryOnce: Boolean = false,
        frames: String? = null,
        inputSummary: String? = null,
        versionCode: Int = 5,
        since: String = "2026-11-14",
        last: String = "2026-11-15",
    ): IndexIssue =
        IndexIssue(
            PackageKey(serial, packageName),
            stage,
            cause,
            categoryOf(stage, cause),
            frames,
            inputSummary,
            versionCode,
            retryOnce,
            since,
            last,
        )

    /** 入口 1 つ（`<パッケージ名>.Main`）の組。[issue] があれば最後の試みの欠けにする。 */
    fun pkg(
        serial: Long,
        packageName: String,
        label: String,
        issue: IndexIssue? = null,
        archived: Boolean = false,
        entries: List<String> = listOf("$packageName.Main"),
    ): PackageState {
        val observed = PackageObservation(entries.toSet(), archived, "apk-$packageName", 1L)
        val materialKey = MaterialKey.of(observed, ENV)
        val derivedKey = DerivedKey.of(ENV)
        val current =
            Current(
                materialKey,
                derivedKey,
                entries.associateWith { label },
                emptyMap(),
                emptyList(),
                entries.associateWith { emptyList() },
            )
        val failed =
            issue?.let {
                Issue(
                    it.stage,
                    Failure(it.cause, it.category, it.frames, it.inputSummary),
                    it.versionCode,
                    it.firstFailedOn,
                    it.lastFailedOn,
                )
            }
        return PackageState(
            PackageKey(serial, packageName),
            observed,
            current,
            emptyMap(),
            Attempt(materialKey, derivedKey, 1, failed, issue?.retryOnce ?: false),
            emptyMap(),
        )
    }

    /**
     * [apps]（プロファイル・パッケージ名・表示名）と [issues] の写し。欠けの組が [apps] になければ、パッケージ名を表示名にして足す。
     * 先頭から [archived] 個の組をアーカイブ済みにする。
     */
    fun snapshot(
        apps: List<Triple<Long, String, String>> = emptyList(),
        issues: List<IndexIssue> = emptyList(),
        listings: Map<Long, ListingStatus> = emptyMap(),
        archived: Int = 0,
    ): IndexSnapshot {
        val byKey = issues.associateBy { it.key }
        val labels = LinkedHashMap<PackageKey, String>()
        apps.forEach { (serial, name, label) -> labels[PackageKey(serial, name)] = label }
        issues.forEach { labels.putIfAbsent(it.key, it.key.packageName) }
        val packages =
            labels.entries.mapIndexed { i, (key, label) ->
                pkg(
                    key.profileSerial,
                    key.packageName,
                    label,
                    byKey[key],
                    archived =
                        i < archived,
                )
            }
        return IndexSnapshot.restore(packages, listings)
    }

    /** 書き手の状態。[synced] ならこのプロセスで同期が 1 回終わっている。 */
    fun writer(
        synced: Boolean = true,
        syncFailure: String? = null,
        genreFailure: String? = null,
        lastSavedAt: Long? = null,
    ): IndexWriterStatus =
        IndexWriterStatus(
            loaded = true,
            syncFailure = syncFailure,
            genreFailure = genreFailure,
            lastSavedAt = lastSavedAt,
            lastSync = if (synced) SyncSummary(0, 0, 0, 0, 0, failed = syncFailure != null) else null,
        )

    /** 不具合の疑い・一時的（読み直し待ちとそうでないもの）・決定的を 1 つずつ持つ組。 */
    val MIXED_ISSUES: List<IndexIssue> =
        listOf(
            issue("com.example.maps", Stage.LABEL, NOT_FOUND),
            issue("com.example.mail", Stage.SIGNER, "java.io.IOException", retryOnce = true),
            issue("com.example.camera", Stage.ENTRY, "VANISHED"),
            issue(
                "com.example.work",
                Stage.KEYS,
                "java.lang.IllegalArgumentException",
                serial = WORK,
                frames = "a.b.c(SourceFile:12)\na.b.d(SourceFile:40)",
                inputSummary = "label:len42:kanji,kana",
            ),
        )

    val MIXED_APPS: List<Triple<Long, String, String>> =
        listOf(
            Triple(PERSONAL, "com.example.maps", "地図"),
            Triple(PERSONAL, "com.example.mail", "メール"),
            Triple(PERSONAL, "com.example.camera", "カメラ"),
            Triple(PERSONAL, "com.example.clock", "時計"),
            Triple(WORK, "com.example.work", "仕事の予定"),
        )

    // 0.2.0 の MaterialCauses と同じ分け方（v0.3 では読み手が区分を決めて渡す）
    private fun categoryOf(
        stage: Stage,
        cause: String,
    ): IssueCategory =
        when {
            stage == Stage.KEYS -> IssueCategory.SUSPECTED_BUG
            cause == NOT_FOUND -> IssueCategory.DETERMINISTIC
            cause == "java.io.IOException" || cause == "VANISHED" -> IssueCategory.TRANSIENT
            else -> IssueCategory.SUSPECTED_BUG
        }
}
