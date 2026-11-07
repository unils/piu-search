// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import jp.piu.search.core.search.AppEntry

/**
 * 端末のアプリの入口 1 つの、索引の材料とパッケージの最後の更新の時刻。
 *
 * @property entry 索引の材料
 * @property packageUpdatedAt パッケージの最後の更新の時刻（[AppSource.packageUpdatedAt]）
 */
public data class InstalledApp(
    val entry: AppEntry,
    val packageUpdatedAt: Long,
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
        val activities = source.activities(packages)
        val genreMap = genres.resolve(activities)
        val times = HashMap<Pair<Long, String>, Long>()
        // 署名はパッケージの単位なので、入口が 2 つあっても 1 回だけ読む
        val signerNames = HashMap<Pair<Long, String>, List<String>>()
        return activities.map { activity ->
            val entry =
                AppEntry(
                    id = activity.id,
                    label = activity.label,
                    localizedLabels = labels.read(activity),
                    genres = genreMap[activity.id].orEmpty(),
                    signerNames = signerNames.getOrPut(activity.id.profileSerial to activity.id.packageName) { signers.read(activity) },
                )
            val time = times.getOrPut(activity.id.profileSerial to activity.id.packageName) { source.packageUpdatedAt(activity) }
            InstalledApp(entry, time)
        }
    }
}
