// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId

/**
 * アプリが属するジャンルを OS の情報から決める（matching.md の「intent filter とカテゴリによるジャンル」）。
 *
 * 手がかりは 2 つ:
 * - intent の問い合わせ（[GENRE_RULES]）。受けた Activity がそのアプリの起動の入口なら、その入口だけに付ける
 *   （1 つのパッケージに「電話」と「連絡先」の入口がある機種のため）。入口でない Activity（メールの作成の画面など）なら、
 *   パッケージのすべての入口に付ける
 * - `ApplicationInfo.category`（[APP_CATEGORY_GENRES]）。アプリの単位なので、すべての入口に付ける
 *
 * `PackageManager` の問い合わせは自分のプロファイルの中しか見えない。仕事用プロファイルのアプリには、
 * 同じパッケージが主プロファイルにあればその結果を使う（同じ APK の見込み）。なければ `category` だけで決まる。
 *
 * @param sdkInt 端末の API レベル。版で分ける行（[GenreRule.minSdk]）を選ぶ。テストで差し替える
 */
public class GenreResolver internal constructor(
    private val packageManager: PackageManager,
    private val sdkInt: Int,
) {
    /** 端末の API レベルで判定する。 */
    public constructor(packageManager: PackageManager) : this(packageManager, Build.VERSION.SDK_INT)

    /**
     * [activities] の各入口のジャンル。ジャンルのない入口は結果に入れない。
     * ジャンルは matching.md の表の順に並べる。
     */
    public fun resolve(activities: List<LaunchableActivity>): Map<AppId, List<GenreId>> =
        resolveEntries(activities.map { GenreEntry(it.id, it.applicationInfo.category) })

    /** 名前や署名を読み直さず、毎回の一覧からジャンルだけを判定する。 */
    public fun resolveStamps(stamps: List<ActivityStamp>): Map<AppId, List<GenreId>> =
        resolveEntries(stamps.map { GenreEntry(it.id, it.category) })

    private data class GenreEntry(
        val id: AppId,
        val category: Int,
    )

    private fun resolveEntries(activities: List<GenreEntry>): Map<AppId, List<GenreId>> {
        val handlers = queryHandlers(activities.mapTo(LinkedHashSet()) { it.id.packageName })
        val result = LinkedHashMap<AppId, List<GenreId>>()
        for ((_, entries) in activities.groupBy { it.id.packageName to it.id.profileSerial }) {
            val packageName = entries.first().id.packageName
            val entryNames = entries.mapTo(HashSet()) { it.id.activityName }
            val packageWide = HashSet<GenreId>()
            val perEntry = HashMap<String, MutableSet<GenreId>>()
            for ((genre, components) in handlers) {
                for (component in components) {
                    if (component.packageName != packageName) continue
                    if (component.className in entryNames) {
                        perEntry.getOrPut(component.className) { HashSet() } += genre
                    } else {
                        packageWide += genre
                    }
                }
            }
            for (entry in entries) {
                APP_CATEGORY_GENRES[entry.category]?.let { packageWide += it }
                val genres = packageWide + perEntry[entry.id.activityName].orEmpty()
                if (genres.isNotEmpty()) result[entry.id] = Genres.ORDER.filter { it in genres }
            }
        }
        return result
    }

    /** 全体への問い合わせを基本とし、カメラだけは重複を除いたパッケージごとに問う。 */
    private fun queryHandlers(packages: Set<String>): List<Pair<GenreId, Set<ComponentName>>> =
        GENRE_RULES
            .filter { sdkInt >= it.minSdk }
            .map { rule ->
                val intents =
                    if (rule.perPackage) {
                        packages.map { rule.intent.toIntent().setPackage(it) }
                    } else {
                        listOf(rule.intent.toIntent())
                    }
                rule.genre to
                    intents
                        .flatMap { query(it, rule.queryFlags) }
                        .mapTo(HashSet()) { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            }

    // MATCH_DEFAULT_ONLY は付けない。CATEGORY_DEFAULT のない起動の入口も、宣言していればジャンルの材料になる。
    // ブラウザは MATCH_ALL で既定以外も含める（AOSP CrossProfileIntentResolverEngine、android14-release）
    private fun query(
        intent: Intent,
        flags: Int,
    ): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, flags)
        }
}
