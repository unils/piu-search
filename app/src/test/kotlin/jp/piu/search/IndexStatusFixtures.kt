// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.data.MaterialCauses
import jp.piu.search.data.MaterialIssue
import jp.piu.search.data.MaterialStage
import jp.piu.search.data.PackageKey
import jp.piu.search.data.ProfileReadStatus
import jp.piu.search.data.SyncMode
import jp.piu.search.data.SyncReport

// 「索引の状態」と報告用の文のテストが共有する索引と欠けの行
internal object IndexStatusFixtures {
    const val PERSONAL = 0L
    const val WORK = 10L

    val NOT_FOUND = "android.content.res.Resources\$NotFoundException"

    fun index(vararg apps: Triple<Long, String, String>): SearchIndex =
        SearchIndex(
            apps.map { (serial, packageName, label) ->
                IndexedApp(AppId(packageName, "$packageName.Main", serial), label, emptyList())
            },
        )

    fun issue(
        packageName: String,
        stage: MaterialStage,
        cause: String,
        serial: Long = PERSONAL,
        retryOnce: Boolean = false,
        frames: String? = null,
        inputSummary: String? = null,
        versionCode: Int = 5,
        since: String = "2026-11-14",
        last: String = "2026-11-15",
    ): MaterialIssue =
        MaterialIssue(PackageKey(serial, packageName), stage, cause, frames, inputSummary, versionCode, retryOnce, since, last)

    fun report(
        vararg results: Pair<Long, ProfileReadStatus>,
        archived: Int = 0,
        genreFailure: String? = null,
    ): SyncReport =
        SyncReport(
            mode = SyncMode.CHANGES,
            rebuiltApps = 0,
            removedPackages = 0,
            removedProfiles = 0,
            analyzerLoadMillis = 0,
            totalMillis = 0,
            indexSize = 1,
            genreFailure = genreFailure,
            profileResults = results.toMap(),
            archivedPackages = archived,
        )

    /** 不具合の疑い・一時的（読み直し待ちとそうでないもの）・決定的を 1 つずつ持つ組。 */
    val MIXED_ISSUES: List<MaterialIssue> =
        listOf(
            issue("com.example.maps", MaterialStage.LABEL, NOT_FOUND),
            issue("com.example.mail", MaterialStage.SIGNER, "java.io.IOException", retryOnce = true),
            issue("com.example.camera", MaterialStage.ENTRY, MaterialCauses.VANISHED),
            issue(
                "com.example.work",
                MaterialStage.KEYS,
                "java.lang.IllegalArgumentException",
                serial = WORK,
                frames = "a.b.c(SourceFile:12)\na.b.d(SourceFile:40)",
                inputSummary = "label:len42:kanji,kana",
            ),
        )

    val MIXED_INDEX: SearchIndex =
        index(
            Triple(PERSONAL, "com.example.maps", "地図"),
            Triple(PERSONAL, "com.example.mail", "メール"),
            Triple(PERSONAL, "com.example.camera", "カメラ"),
            Triple(PERSONAL, "com.example.clock", "時計"),
            Triple(WORK, "com.example.work", "仕事の予定"),
        )
}
