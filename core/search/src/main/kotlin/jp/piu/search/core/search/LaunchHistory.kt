// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

/**
 * アプリ 1 つの起動の記録（architecture.md の `launch_stat`）。
 *
 * @property count 起動した回数
 * @property lastLaunchedAt 最後に起動した時刻（エポックからのミリ秒）
 */
public data class LaunchRecord(
    val count: Int,
    val lastLaunchedAt: Long,
)

/**
 * このアプリから起動したアプリの記録。照合の加点（[asRankingSignals]）と、空の検索語で出す一覧（[suggestions]）に使う。
 *
 * 変更しない。起動したら [recorded] で新しいものを作って差し替える（画面のスレッドと記録のスレッドで共有するため）。
 * 記録は索引の表とつながず、アプリを識別子の 3 つの組で持つので、索引にない（消えた）アプリの記録も残る。
 * 引く側（[suggestions]）は索引にあるアプリだけを返す。
 *
 * @property records アプリ → 起動の記録
 */
public class LaunchHistory(
    public val records: Map<AppId, LaunchRecord>,
) {
    /**
     * [app] を時刻 [at] に起動した記録を足したもの。
     */
    public fun recorded(
        app: AppId,
        at: Long,
    ): LaunchHistory {
        val previous = records[app]
        val next = LaunchRecord(count = (previous?.count ?: 0) + 1, lastLaunchedAt = maxOf(at, previous?.lastLaunchedAt ?: at))
        return LaunchHistory(records + (app to next))
    }

    /**
     * [app] の起動の回数と新しさによる加点（仮の値。[USAGE_POINTS_PER_LAUNCH] など）。時刻 [now] から見た新しさで決める。
     *
     * 回数の加点に上限を置き、何十回開いたアプリでも同じ段階の中で少し上がるだけにする。
     * 段階をまたがないのは [SearchEngine] の並べ方が保つ。
     */
    public fun usageBoost(
        app: AppId,
        now: Long,
    ): Int {
        val record = records[app] ?: return 0
        val countPoints = minOf(record.count, USAGE_MAX_COUNTED_LAUNCHES) * USAGE_POINTS_PER_LAUNCH
        val age = now - record.lastLaunchedAt
        val recencyPoints =
            when {
                age < DAY_MILLIS -> RECENT_DAY_POINTS
                age < WEEK_MILLIS -> RECENT_WEEK_POINTS
                else -> 0
            }
        return countPoints + recencyPoints
    }

    /**
     * 照合に渡す加点。時刻 [now] で新しさを測り、学習の加点は [learning] から引く（learning.md の R3）。
     */
    public fun asRankingSignals(
        now: Long,
        learning: LearnedMemory = LearnedMemory.EMPTY,
    ): RankingSignals =
        object : RankingSignals {
            override fun learnedBoost(
                query: SearchQuery,
                app: AppId,
            ): Int = learning.learnedBoost(query, app)

            override fun usageBoost(app: AppId): Int = this@LaunchHistory.usageBoost(app, now)
        }

    /**
     * 空の検索語で出す一覧（ui.md の「検索画面」）。[index] にあるアプリだけを返す。
     *
     * 最近使ったアプリ（最後の起動の新しい順に [recentLimit] 件）と、よく使うアプリ（回数の多い順に、最近の分を除いて [frequentLimit] 件）。
     * 同じ回数なら新しいほうを、同じ時刻なら識別子の順を先にして、毎回同じ順にする。
     */
    public fun suggestions(
        index: SearchIndex,
        recentLimit: Int = DEFAULT_RECENT_LIMIT,
        frequentLimit: Int = DEFAULT_FREQUENT_LIMIT,
    ): Suggestions {
        val apps = index.apps.associateBy { it.id }
        val known = records.entries.filter { it.key in apps }
        val byRecency =
            compareByDescending<Map.Entry<AppId, LaunchRecord>> { it.value.lastLaunchedAt }.thenBy(ID_ORDER) { it.key }
        val recent = known.sortedWith(byRecency).take(recentLimit).map { it.key }
        val recentSet = recent.toSet()
        val frequent =
            known
                .filter { it.key !in recentSet }
                .sortedWith(compareByDescending<Map.Entry<AppId, LaunchRecord>> { it.value.count }.then(byRecency))
                .take(frequentLimit)
                .map { it.key }
        return Suggestions(recent.map(apps::getValue), frequent.map(apps::getValue))
    }

    /** 定数。 */
    public companion object {
        /** 記録のない状態。 */
        public val EMPTY: LaunchHistory = LaunchHistory(emptyMap())

        /** 起動 1 回あたりの加点（仮）。キーの種類の重み（10 刻み）より小さくし、数回で種類の差を越えるようにした。 */
        public const val USAGE_POINTS_PER_LAUNCH: Int = 3

        /** 回数の加点に数える起動の上限（仮）。 */
        public const val USAGE_MAX_COUNTED_LAUNCHES: Int = 10

        /** 最後の起動が 1 日以内のときの加点（仮）。 */
        public const val RECENT_DAY_POINTS: Int = 10

        /** 最後の起動が 7 日以内のときの加点（仮）。 */
        public const val RECENT_WEEK_POINTS: Int = 5

        /** 空の検索語で出す、最近使ったアプリの数の既定値。 */
        public const val DEFAULT_RECENT_LIMIT: Int = 4

        /** 空の検索語で出す、よく使うアプリの数の既定値。 */
        public const val DEFAULT_FREQUENT_LIMIT: Int = 8

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val WEEK_MILLIS = 7 * DAY_MILLIS
        private val ID_ORDER = compareBy<AppId>({ it.profileSerial }, { it.packageName }, { it.activityName })
    }
}

/**
 * 空の検索語で出す一覧（[LaunchHistory.suggestions]）。どちらも先頭が最も上位。
 *
 * @property recent 最近使ったアプリ
 * @property frequent よく使うアプリ（[recent] にあるものは除く）
 */
public data class Suggestions(
    val recent: List<IndexedApp>,
    val frequent: List<IndexedApp>,
) {
    /** どちらも空か。 */
    public val isEmpty: Boolean get() = recent.isEmpty() && frequent.isEmpty()
}
