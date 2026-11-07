// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

/**
 * 照合と順位の数値（重み・上限・しきい値）。
 *
 * **どの値も仮で、根拠はない。** 評価セット（M1-06b）で動かして決めるため、ここ 1 か所に集め、[SearchEngine] に渡せる形にした。
 * 字数の規則のうち matching.md に書かれたもの（ジャンルの語のかな 2 字・英字 3 字、部分一致と飛ばし一致の 2 字、
 * 段階 5・6 の 20 件）は、設計の例の値をそのまま置いている。
 *
 * @property kindWeights キーの種類ごとの重み。同じ段階・同じ形の中で、重いほど上に並べる。学習と起動の回数の加点（[RankingSignals]）と足して比べる
 * @property maxWeakResults 段階 5（部分一致）と 6（頭文字・飛ばし一致）を合わせた結果の上限。段階 1〜4 と 7（提供元）は上限なし
 * @property minSubstringLength 部分一致（段階 5）を試す、検索語の厳密形の最小の字数
 * @property minSubsequenceLength 頭文字・飛ばし一致（段階 6）を試す、英字の検索語の最小の字数
 * @property learnedPrefixMinPercent 学習のキー（[SearchKeyKind.LEARNED]）に前方一致とみなす、検索語の長さのキーの長さに対する割合（%）。
 *   「やふ」は「やふー」の学習に当たり、「ぺい」は「ぺいぺいふりま」の学習に当たらない（評価セットの「短い語」で決めた）
 * @property learnedRomajiWeight 学習の語をローマ字で比べて当たったときの重み。かなの学習の語（[SearchKeyKind.LEARNED]）が読み（40）より下なのと同じく、
 *   ローマ字（30）より下にする。同じ 30 にすると「shinkansen」で「しんかんせんよやく」を学んだ EX アプリが新幹線時刻表と並び、表示名の順で上に出た（評価セット）
 */
public data class MatchingConfig(
    val kindWeights: Map<SearchKeyKind, Int> = DEFAULT_KIND_WEIGHTS,
    val maxWeakResults: Int = 20,
    val minSubstringLength: Int = 2,
    val minSubsequenceLength: Int = 2,
    val learnedPrefixMinPercent: Int = 50,
    val learnedRomajiWeight: Int = 20,
) {
    init {
        require(maxWeakResults >= 0) { "段階 5・6 の上限は 0 以上: $maxWeakResults" }
        require(learnedPrefixMinPercent in 0..100) { "学習の前方一致の割合は 0〜100: $learnedPrefixMinPercent" }
    }

    /** 定数。 */
    public companion object {
        /**
         * キーの種類の重みの既定値（仮）。
         *
         * 名前を打つ人が多いと見て、名前を上に、読みとローマ字（名前から機械的に作った形）を次に、
         * パッケージ名（開発者の付けた英語で、名前と離れることがある）を下に置いた。
         * 10 刻みにしたのは、M4 で学習の加点を同じ尺度で足すときに、間に入れる余地を残すため。
         */
        public val DEFAULT_KIND_WEIGHTS: Map<SearchKeyKind, Int> =
            mapOf(
                SearchKeyKind.NAME to 50,
                SearchKeyKind.LOCALIZED_NAME to 40,
                SearchKeyKind.READING to 40,
                SearchKeyKind.ROMAJI to 30,
                SearchKeyKind.BRAND to 30,
                SearchKeyKind.PACKAGE_NAME to 20,
                // 提供元はいちばん下の段（MatchStage.PROVIDER）にだけ出るので、重みは同じ段のパッケージ名の語と並べる
                SearchKeyKind.PUBLISHER to 20,
                SearchKeyKind.GENRE to 10,
                // 学習の語の完全一致は段階 1 なので、重みが低くても上に出る。重みが効くのは、学習の語の頭だけを打った前方一致で、
                // 名前と読みの前方一致と並ぶとき。名前と同じ 50 にすると「らくてん」で「らくてんしょうけん」を学んだ iSPEED が
                // 楽天のアプリより上に出た（評価セット）。そこで読み（40）より下にした（仮）
                SearchKeyKind.LEARNED to 30,
            )

        /** 既定の値。[DEFAULT_KIND_WEIGHTS] を使うので、その後に置く（companion の初期化は書いた順）。 */
        public val DEFAULT: MatchingConfig = MatchingConfig()

        /** ジャンルの語と比べる、かな・漢字の検索語の最小の字数（matching.md の段階 4）。1 字では多くのジャンルに当たるため。 */
        internal const val GENRE_MIN_KANA_LENGTH = 2

        /** ジャンルの語と比べる、英字の検索語の最小の字数（matching.md の段階 4）。英字 2 字は短い語の頭として多すぎるため。 */
        internal const val GENRE_MIN_LATIN_LENGTH = 3
    }
}

/**
 * 学習と起動の記録による加点（matching.md の「照合の段階と順位」、learning.md の R3）。
 *
 * 同じ段階の中の順位にだけ効き、段階を越えない。学習の加点は [LearnedMemory.learnedBoost]、起動の加点は [LaunchHistory.usageBoost] が決める。
 * 照合の中で結果のアプリごとに呼ぶので、記録は前もってメモリに読んでおき、ここでは引くだけにする。
 */
public interface RankingSignals {
    /**
     * [query] で [app] を選んだ記録による加点。検索語 q' が記録の q の前の部分のときに効く（learning.md の R3）。上限を置く。
     */
    public fun learnedBoost(
        query: SearchQuery,
        app: AppId,
    ): Int

    /** [app] の起動の回数と新しさによる加点。 */
    public fun usageBoost(app: AppId): Int

    /** 定数。 */
    public companion object {
        /** 加点しない。評価セットの学習のない状態や、テストで使う。 */
        public val NONE: RankingSignals =
            object : RankingSignals {
                override fun learnedBoost(
                    query: SearchQuery,
                    app: AppId,
                ): Int = 0

                override fun usageBoost(app: AppId): Int = 0
            }
    }
}
