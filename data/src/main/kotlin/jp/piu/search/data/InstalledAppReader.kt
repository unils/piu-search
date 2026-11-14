// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import jp.piu.search.core.search.AppEntry

/**
 * 端末のアプリの入口 1 つの、索引の材料とパッケージの最後の更新の時刻。
 *
 * @property entry 索引の材料
 * @property packageUpdatedAt パッケージの最後の更新の時刻（[AppSource.packageUpdatedAt]）
 * @property apkFingerprint base と split の属性。不完全な読み取りでは保存しない
 * @property complete 別言語名と署名を含めて材料が揃ったか
 */
public data class InstalledApp(
    val entry: AppEntry,
    val packageUpdatedAt: Long,
    val apkFingerprint: String? = null,
    val complete: Boolean = true,
)

/**
 * 端末のアプリから、索引の材料（[AppEntry]）を作る。
 *
 * [AppSource] の入口に、[LocalizedLabelReader] の別の言語の名前と [GenreResolver] のジャンルと、[SignerReader] の署名者の DN を足す。
 * アプリの資源を開くので、画面のスレッドでは呼ばない。
 */
public class InstalledAppReader(
    /** 入口の一覧。 */
    public val source: AppSource,
    private val labels: LocalizedLabelReader,
    private val genres: GenreResolver,
    private val signers: SignerReader,
) {
    /** 端末の部品で作る。 */
    public constructor(context: Context) : this(
        AppSource(context),
        LocalizedLabelReader(context),
        GenreResolver(context.packageManager),
        SignerReader(context.packageManager),
    )

    /** すべてのプロファイルの起動できるアプリ。入口ごとに 1 件。 */
    public fun readAll(): List<AppEntry> = read(null).map { it.entry }

    /** [packages] のパッケージの、すべてのプロファイルの入口（null ならすべて）。パッケージの更新の時刻を添える。 */
    public fun read(packages: Set<String>?): List<InstalledApp> {
        val profiles = readProfiles(packages)
        if (profiles.values.any { it.status == ProfileReadStatus.FAILED || it.failedPackages.isNotEmpty() }) throw MaterialReadException()
        val result = profiles.values.flatMap { it.items }
        val genreMap = genres.resolveStamps(source.stamps(withUpdateTime = false))
        return result.map { it.copy(entry = it.entry.copy(genres = genreMap[it.entry.id].orEmpty())) }
    }

    /** 個別の資源の失敗を不完全なパッケージとして残し、ほかの更新を止めない。 */
    public fun readProfiles(packages: Set<String>?): Map<Long, ProfileRead<InstalledApp>> =
        source.launcherProfiles(packages).mapValues { (serial, profile) ->
            if (profile.status != ProfileReadStatus.COMPLETE) {
                ProfileRead(profile.status)
            } else {
                val failed = HashSet<String>()
                val result = ArrayList<InstalledApp>()
                for ((packageName, infos) in profile.items.groupBy { it.componentName.packageName }) {
                    try {
                        val activities = infos.map { source.launchable(it, serial) }
                        result += readPackage(activities)
                    } catch (_: RuntimeException) {
                        failed += packageName
                    }
                }
                val status = source.profileReadStatus(serial)
                if (status == ProfileReadStatus.COMPLETE) ProfileRead(status, result, failed) else ProfileRead(status)
            }
        }

    internal fun readPackage(
        activities: List<LaunchableActivity>,
        readLabels: (LaunchableActivity) -> List<String> = labels::read,
        readSigners: (LaunchableActivity) -> List<String> = signers::read,
    ): List<InstalledApp> {
        val fingerprintBefore = apkFingerprint(activities.first().applicationInfo)
        var complete = true
        val signerNames =
            try {
                readSigners(activities.first())
            } catch (_: RuntimeException) {
                complete = false
                emptyList()
            }
        val entries =
            activities.map { activity ->
                val localized =
                    try {
                        readLabels(activity)
                    } catch (_: Exception) {
                        complete = false
                        emptyList()
                    }
                AppEntry(activity.id, activity.label, localizedLabels = localized, signerNames = signerNames)
            }
        val first = activities.first()
        val time = source.packageUpdatedAt(first)
        val fingerprintAfter = apkFingerprint(first.applicationInfo)
        complete = complete && fingerprintBefore != null && fingerprintBefore == fingerprintAfter
        return entries.map { InstalledApp(it, time, if (complete) fingerprintAfter else null, complete) }
    }
}
