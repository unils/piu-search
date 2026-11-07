// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

/**
 * 学習の判定と効き方の数値（learning.md の R2・R3・R5）。
 *
 * **どの値も仮で、根拠は設計の例の値か、評価セットの「学習」の分類で試した結果だけ。** 実機で使ってから動かすため、ここ 1 か所に集めた。
 * 学習のキーの種類の重み（[SearchKeyKind.LEARNED]）だけは、ほかの種類と並べて比べるので [MatchingConfig.kindWeights] に置く。
 *
 * @property minVisibleResults 「見えていた」とみなす結果の上位の件数の下限（learning.md の K。画面に収まる件数がこれより少なくても、この件数までは見えたとみなす）
 * @property rescueWindowMillis 救済の試行の終わりから、アプリを開くまでの時間の上限（ミリ秒。R2 の 60 秒）
 * @property minRescueLength 救済にする検索語の厳密形の最小の字数（コードポイント。R2 の 2 字）
 * @property boostPerUse 学習の記録 1 回あたりの加点（R3）
 * @property maxBoost 学習の加点の上限（R3。何十回開いても同じ段階の中で少し上がるだけにする）
 * @property forgetAfterMillis 最後に使ってから記録を消すまでの時間（ミリ秒。R5 の 180 日）
 */
public data class LearningConfig(
    val minVisibleResults: Int = 5,
    val rescueWindowMillis: Long = 60_000,
    val minRescueLength: Int = 2,
    val boostPerUse: Int = 5,
    val maxBoost: Int = 20,
    val forgetAfterMillis: Long = 180 * DAY_MILLIS,
) {
    init {
        require(minVisibleResults >= 1) { "見えていたとみなす件数は 1 以上: $minVisibleResults" }
        require(boostPerUse >= 0 && maxBoost >= 0) { "加点は 0 以上: $boostPerUse, $maxBoost" }
    }

    /**
     * 画面に [fitOnScreen] 件の結果が収まるときに「見えていた」とみなす件数。記録する側（M4-02）が、結果の上位をこの件数だけ渡す。
     */
    public fun visibleCount(fitOnScreen: Int): Int = maxOf(fitOnScreen, minVisibleResults)

    /** 定数。 */
    public companion object {
        /** 既定の値。 */
        public val DEFAULT: LearningConfig = LearningConfig()

        /** 1 日のミリ秒。 */
        public const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
