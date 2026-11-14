// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.RomajiConverter
import jp.piu.search.core.text.RomajiMode
import jp.piu.search.core.text.containsLatin

/**
 * 検索語 1 件の評価の結果。
 *
 * @property ranks 正解ごとの順位（1 始まり。[EvalQuery.answers] と同じ順）。結果に出なければ null
 * @property top 結果の上位の表示名（報告に出す分だけ）
 * @property taught 学習の分類でこの検索語そのもの（その読み・ローマ字と、打ちかけの形を含む）を教えたか。教えたアプリが上に来るのは狙いどおりなので、
 *   学習の後の比べから外す。学習の語の完全一致（[SearchKeyKind.LEARNED] の段階 1）が結果にあれば真。英字を含む検索語では、
 *   学習の語の前方一致（段階 2）のうち、教えた語の最後の 1 音までを打ったものも数える
 */
internal class QueryOutcome(
    val query: EvalQuery,
    val ranks: List<Int?>,
    val top: List<String>,
    val taught: Boolean = false,
) {
    private val found: List<Int> = ranks.filterNotNull()
    private val allFound: Boolean = found.size == ranks.size

    /**
     * 指標に使う順位。1 なら最もよい。当たらなければ null。
     *
     * - any: 最も上の正解の順位
     * - all: 最も下の正解の順位から、ほかの正解の数を引いたもの。正解が上から隙間なく並べば 1 になる
     *   （「やふー」の 9 件が 1〜9 位なら 1、1 件だけ 10 位に落ちれば 2）
     */
    val effectiveRank: Int? =
        when (query.judge) {
            Judge.ANY -> found.minOrNull()
            Judge.ALL -> if (allFound) found.max() - (ranks.size - 1) else null
        }

    /** 1 位正解か。all は、正解がすべて上から正解の数の位以内に並ぶこと。 */
    val isTop1: Boolean = effectiveRank == 1

    /**
     * 3 位以内の正解か。any は最も上の正解が 3 位以内。all は、正解がすべて max(3, 正解の数) 位以内（M1 のタスクカードの叩き台）。
     */
    val isTop3: Boolean =
        when (query.judge) {
            Judge.ANY -> (effectiveRank ?: Int.MAX_VALUE) <= TOP_N
            Judge.ALL -> allFound && found.max() <= maxOf(TOP_N, ranks.size)
        }

    /** 逆順位。当たらなければ 0。 */
    val reciprocalRank: Double = effectiveRank?.let { 1.0 / it } ?: 0.0

    companion object {
        const val TOP_N = 3
    }
}

/**
 * 評価セットの検索語を、評価セットのアプリの索引に照らす。
 *
 * 端末での使い方をまねる: 漢字を含む検索語は、名前との一致だけの結果を出した後に読みを付けて照らし直すので、
 * 読みを付けた検索語（[SearchQuery.withReading]）の結果で測る。
 *
 * @property generator キーの作り方（解析器やブランド表を変えて比べるときに差し替える）
 * @property queryAnalyzer 検索語に読みを付ける解析器
 * @property extraKeys アプリの表示名 → 足すキー。愛称の辞書の効果を、取り込まずに試算するため
 * @property learning 索引に重ね、加点に使う学習の記録（[learnFrom] で作る）。既定は学習のない状態
 */
internal class EvalRunner(
    private val apps: List<EvalApp>,
    private val generator: SearchKeyGenerator,
    private val queryAnalyzer: ReadingAnalyzer,
    private val vocabulary: GenreVocabulary = GenreVocabulary.DEFAULT,
    private val config: MatchingConfig = MatchingConfig.DEFAULT,
    private val extraKeys: (EvalApp) -> List<SearchKey> = { emptyList() },
    private val learning: LearnedMemory = LearnedMemory.EMPTY,
) {
    private val index: SearchIndex by lazy {
        val indexed =
            apps.map { app ->
                val entry = entryOf(app)
                IndexedApp(entry.id, entry.label, generator.generate(entry) + extraKeys(app))
            }
        SearchIndex(indexed)
    }

    private val engine: SearchEngine by lazy {
        val signals = if (learning.records.isEmpty()) RankingSignals.NONE else LaunchHistory.EMPTY.asRankingSignals(0, learning)
        SearchEngine(index.withLearning(learning), vocabulary, signals, config)
    }

    /** [queries] を順に照らす。 */
    fun run(queries: List<EvalQuery>): List<QueryOutcome> = queries.map(::evaluate)

    /** 検索語 [raw] の結果の表示名。 */
    fun labels(raw: String): List<String> = results(raw).map { it.label }

    private fun results(raw: String): List<SearchResult> {
        val query = SearchQuery.of(raw).let { if (it.needsReading) it.withReading(queryAnalyzer) else it }
        return engine.search(query)
    }

    /**
     * [queries] の検索語ごとに「正解のアプリをすべてのアプリの一覧から 1 回開いた」セッションを [policy] に通し、記録をまとめて返す（M4-01）。
     *
     * 見えていた結果は、この runner（ふつうは学習のない状態）の結果の上位 [LearningConfig.minVisibleResults] 件。
     * 開くアプリは、any なら最初の正解、all なら正解ごとに 1 セッション（すべて出るには、すべてを 1 回ずつ開く必要がある）。
     * 検索語ごとに別のセッションとし、記録は 1 つにまとめる（端末で学習が積み重なった状態をまねる）。
     */
    fun learnFrom(
        queries: List<EvalQuery>,
        policy: LearningPolicy = LearningPolicy(),
        visibleCount: Int = LearningConfig.DEFAULT.minVisibleResults,
    ): LearnedMemory {
        val ids = apps.associate { it.name to entryOf(it).id }
        var memory = learning
        var time = 0L
        for (query in queries) {
            val visible = results(query.query).take(visibleCount).map { it.app }
            val targets = if (query.judge == Judge.ALL) query.answers else query.answers.take(1)
            for (answer in targets) {
                val events =
                    listOf(
                        SessionEvent.QueryChanged(time, query.query, visible),
                        SessionEvent.AllAppsShown(time + SECOND),
                        SessionEvent.AppOpened(time + SESSION_MILLIS, ids.getValue(answer), LaunchRoute.ALL_APPS, query.query),
                    )
                memory = memory.recorded(policy.decide(events), time)
                time += SESSION_MILLIS
            }
        }
        return memory
    }

    private fun evaluate(query: EvalQuery): QueryOutcome {
        val results = results(query.query)
        val labels = results.map { it.label }
        val position = labels.withIndex().associate { (i, label) -> label to i + 1 }
        // 英字を含む検索語は、教えた語をローマ字で打ちかけた形も教えたものに数える（M4-02）。「ginko」「ぎんk」は教えた「ぎんこう」の
        // 打ちかけで、教えたアプリが上に来るのは狙いどおり。ただし教えた語の頭にあたる別の語（「しんかんせんよやく」に対する
        // 「shinkansen」）は数えない。学習の副作用を見たい行なので
        val strict = SearchQuery.of(query.query).strict
        val typed =
            if (strict.value.containsLatin()) {
                RomajiConverter.toStandard(strict, RomajiMode.PREFIX).value
            } else {
                null
            }
        val taught =
            results.any {
                it.reason.keyKind == SearchKeyKind.LEARNED &&
                    (
                        it.reason.stage == MatchStage.EXACT ||
                            (
                                typed != null && it.reason.stage == MatchStage.PREFIX &&
                                    untypedLength(it.reason.matchedText, typed) <= MAX_UNTYPED
                            )
                    )
            }
        return QueryOutcome(query, query.answers.map { position[it] }, labels.take(TOP_SHOWN), taught)
    }

    // 教えた語の標準形のローマ字のうち、打っていない末尾の字数
    private fun untypedLength(
        taught: NormalizedText,
        typed: String,
    ): Int = RomajiConverter.toStandard(taught, RomajiMode.COMPLETE).value.length - typed.length

    companion object {
        /**
         * 教えた語の打ちかけとみなす、打っていない末尾の字数（標準形のローマ字）の上限。
         * 最後の 1 音の途中（「ぎんk」→ gink、残り ou）と、長音の省き（「ginko」、残り u）までを同じ語とする。
         * 1 音を丸ごと残す形は別の語の頭とみなす（「shinkansen」は残り yoyaku）
         */
        private const val MAX_UNTYPED = 2

        /** 報告に出す上位の数。 */
        const val TOP_SHOWN = 5

        private const val SECOND = 1_000L

        /** 学習のセッション 1 つの長さ。一覧から探して開くまで。救済の 60 秒以内に収める。 */
        private const val SESSION_MILLIS = 10 * SECOND

        /** 評価セットのアプリを、キーを作る入力にする。Activity とプロファイルは 1 つに決め打つ。 */
        fun entryOf(app: EvalApp): AppEntry =
            AppEntry(
                AppId(app.packageName, "Main", 0),
                app.name,
                listOfNotNull(app.englishName.ifEmpty { null }),
                app.genres.map(::GenreId),
            )
    }
}
