// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText

/**
 * 検索語とメモリ上の索引を照らし、段階と順位を付けた結果を返す（matching.md の「照合の段階と順位」）。
 *
 * 並べ方:
 *
 * 1. 段階（[MatchStage]。完全一致 → 前方一致 → 語の頭からの一致 → ジャンル → 部分一致 → 頭文字・飛ばし一致 → 提供元）
 * 2. 同じ段階の中で、厳密形の一致を先に、緩い形の一致を直後に
 * 3. 点（キーの種類の重み ＋ 学習の加点 ＋ 起動の回数と新しさの加点）の高い順
 * 4. 表示名の順（同じ点でも毎回同じ順になるように）
 *
 * アプリ 1 つに複数のキーが当たったら、最も上に並ぶ当たり方を 1 つ選び、それを出た理由にする。
 *
 * 照合は入力 1 回ごとに呼ぶ。形態素解析・正規表現・検索語の変換はここでは行わない（[SearchQuery] と [SearchIndex] が前もって済ませる）。
 * キーを順になめる。アプリ 500 個（キー 4 千個ほど）で 1 回 0.2 ms ほど（JVM。`SearchEnginePerformanceTest`）で済み、
 * 部分一致（段階 5）はどのみち全部のキーを見るので、前方一致のための並べ替えた索引は作っていない。
 * 状態を持たないので、複数のスレッドから同時に呼んでよい。
 *
 * @property index 照らす索引
 * @property vocabulary ジャンルの語彙
 * @property signals 学習と起動の記録による加点（[LaunchHistory.asRankingSignals]）。学習のキーと印は [index] に重ねる（[SearchIndex.withLearning]）
 * @property config 重みと上限（仮の値）
 */
public class SearchEngine(
    private val index: SearchIndex,
    private val vocabulary: GenreVocabulary = GenreVocabulary.DEFAULT,
    private val signals: RankingSignals = RankingSignals.NONE,
    private val config: MatchingConfig = MatchingConfig.DEFAULT,
) {
    // 種類の重みを配列にしておき、照合の中で Map を引かない
    private val kindWeight = IntArray(SearchKeyKind.entries.size) { config.kindWeights[SearchKeyKind.entries[it]] ?: 0 }

    /**
     * [query] に当たるアプリを、並べた順に返す。空の検索語には空の一覧を返す（最近と頻繁に使ったアプリの表示は画面の側で行う）。
     *
     * 段階 1〜4 と 7（提供元）の結果はすべて、段階 5・6 の結果は合わせて [MatchingConfig.maxWeakResults] 件までを返す。
     * 漢字を含む検索語で読みを付ける前（[SearchQuery.needsReading]）は、検索語の表記のままで比べる。
     */
    public fun search(query: SearchQuery): List<SearchResult> {
        if (query.isEmpty || index.size == 0) return emptyList()
        val best = Best(index.size)
        for (forms in query.formSets) {
            matchText(forms, query, best)
            matchRomaji(forms, query, best)
        }
        matchGenres(query, best)
        if (query.isLatin && query.length >= config.minSubsequenceLength) matchSubsequence(query.strict.value, best)
        return rank(query, best)
    }

    /** かなと英字の検索語の形を、名前・読み・パッケージ名のキーと比べる。 */
    private fun matchText(
        forms: QueryForms,
        query: SearchQuery,
        best: Best,
    ) {
        val table = index.textKeys
        val allowSubstring = query.length >= config.minSubstringLength
        val looseDiffers = forms.loose != forms.strict
        val providerPrefixAllowed = query.isLongEnoughForGenre
        for (i in 0 until table.size) {
            val key = table.text[i]
            val starts = table.wordStarts[i]
            val weight = kindWeight[table.kind[i].ordinal]
            if (table.providerTier[i]) {
                offerProvider(table, i, forms, providerPrefixAllowed, best)
                continue
            }
            val learned = table.kind[i] == SearchKeyKind.LEARNED
            val kindAllowsSubstring = allowSubstring && !learned
            if (table.form[i] == KeyForm.STRICT) {
                var stage = capPackageStage(stageOf(key, starts, forms.strict, forms.strict, kindAllowsSubstring), table.kind[i])
                if (learned) stage = capLearnedStage(stage, key, forms.strict)
                if (stage >= 0) best.offer(table.app[i], stage, KeyForm.STRICT, weight, table.kind[i], key, null, forms.fromReading)
                if (!looseDiffers) continue
            }
            // 緩い形のキーと、検索語の緩い形が厳密形と違うときの厳密形のキーは、検索語の緩い形と比べる
            var stage = capPackageStage(stageOf(key, starts, forms.loose, forms.loose, kindAllowsSubstring), table.kind[i])
            if (learned) stage = capLearnedStage(stage, key, forms.loose)
            if (stage >= 0) best.offer(table.app[i], stage, KeyForm.LOOSE, weight, table.kind[i], key, null, forms.fromReading)
        }
    }

    /**
     * [table] の提供元の段のキー（[KeyTable.providerTier]）の i 番目を比べる。完全一致と前方一致だけを見る。
     *
     * 語の途中の一致は見ない（`ten` で rakuten の会社のアプリが並ぶと、名前の部分一致より役に立たない語が増える）。
     * 前方一致は、検索語がジャンルと同じ字数（かな 2 字・英字 3 字）以上のときだけ（[prefixAllowed]）。短い語の頭で多くの会社のアプリが並ばないように。
     */
    private fun offerProvider(
        table: KeyTable,
        i: Int,
        forms: QueryForms,
        prefixAllowed: Boolean,
        best: Best,
        romaji: Boolean = false,
    ) {
        val key = table.text[i]
        val prefix = if (romaji) forms.romajiPrefix.orEmpty() else forms.strict
        val complete = if (romaji) forms.romajiComplete.orEmpty() else forms.strict
        val loosePrefix = if (romaji) forms.romajiLoose.orEmpty() else forms.loose
        val looseComplete = if (romaji) forms.romajiLooseComplete.orEmpty() else forms.loose
        val strictForm = if (romaji && !forms.romajiIsStrict) KeyForm.LOOSE else KeyForm.STRICT

        // 入力途中の sh → s が短い語への完全一致にならないよう、完結した形と前方一致の形を別々に見る
        fun hits(
            prefixQuery: String,
            completeQuery: String,
        ) = (completeQuery.isNotEmpty() && key == completeQuery) ||
            (prefixAllowed && prefixQuery.isNotEmpty() && key.startsWith(prefixQuery))
        val form =
            when {
                table.form[i] == KeyForm.STRICT && hits(prefix, complete) -> strictForm
                hits(loosePrefix, looseComplete) -> KeyForm.LOOSE
                else -> return
            }
        val stage = MatchStage.PROVIDER.ordinal
        best.offer(table.app[i], stage, form, kindWeight[table.kind[i].ordinal], table.kind[i], table.shown[i], null, forms.fromReading)
    }

    /** 検索語の標準形のローマ字を、ローマ字のキーと比べる。ローマ字の形がない検索語（漢字が残る・途中で空になる）では何もしない。 */
    private fun matchRomaji(
        forms: QueryForms,
        query: SearchQuery,
        best: Best,
    ) {
        val prefix = forms.romajiPrefix ?: return
        val loose = forms.romajiLoose ?: return
        // 完全一致は入力が完結したとみなした形で比べる（途中の形 sh → s が、キー s と等しくても完全一致にしない）
        val complete = forms.romajiComplete ?: ""
        val looseComplete = forms.romajiLooseComplete ?: ""
        val table = index.romajiKeys
        val allowSubstring = query.length >= config.minSubstringLength
        val looseDiffers = loose != prefix || looseComplete != complete
        val strictForm = if (forms.romajiIsStrict) KeyForm.STRICT else KeyForm.LOOSE
        for (i in 0 until table.size) {
            if (table.providerTier[i]) {
                offerProvider(table, i, forms, query.isLongEnoughForGenre, best, romaji = true)
                continue
            }
            val key = table.text[i]
            val starts = table.wordStarts[i]
            val learned = table.kind[i] == SearchKeyKind.LEARNED
            val weight = if (learned) config.learnedRomajiWeight else kindWeight[table.kind[i].ordinal]
            // 学習の語のローマ字は、英字を含む検索語（okane・おかn）にだけ使う。かなだけの検索語は、かなの学習の語と比べれば足り、
            // ローマ字で比べると前方一致の長さの制限（capLearnedStage）がかなとローマ字で食い違うため
            if (learned && !forms.romajiIsStrict) continue
            val shown = table.shown[i]
            val kindAllowsSubstring = allowSubstring && !learned
            if (table.form[i] == KeyForm.STRICT) {
                var stage = stageOf(key, starts, prefix, complete, kindAllowsSubstring)
                if (learned) stage = capLearnedRomajiStage(stage, table.prefixLengths[i], prefix)
                if (stage >= 0) best.offer(table.app[i], stage, strictForm, weight, table.kind[i], shown, null, forms.fromReading)
                if (!looseDiffers) continue
            }
            var stage = stageOf(key, starts, loose, looseComplete, kindAllowsSubstring)
            if (learned) stage = capLearnedRomajiStage(stage, table.prefixLengths[i], loose)
            if (stage >= 0) best.offer(table.app[i], stage, KeyForm.LOOSE, weight, table.kind[i], shown, null, forms.fromReading)
        }
    }

    private fun matchGenres(
        query: SearchQuery,
        best: Best,
    ) {
        val weight = kindWeight[SearchKeyKind.GENRE.ordinal]
        for (hit in vocabulary.match(query)) {
            val apps = index.appsByGenre[hit.genre] ?: continue
            for (app in apps) {
                best.offer(app, MatchStage.GENRE.ordinal, hit.form, weight, SearchKeyKind.GENRE, hit.word.value, hit.genre, false)
            }
        }
    }

    /**
     * 頭文字・飛ばし一致（段階 6）: 英字の検索語 [query] の字を、英字のキーが順に含む（`gmp` → `googlemaps`）。
     *
     * 1 字目はキーの頭か語の頭に当てる。どこからでも拾うと、短い英字の検索語がほとんどのキーに当たるため。
     */
    private fun matchSubsequence(
        query: String,
        best: Best,
    ) {
        val table = index.textKeys
        for (i in 0 until table.size) {
            // 学習のキーは段階 1〜3 にだけ加わる（matching.md）
            if (!table.latin[i] || table.kind[i] == SearchKeyKind.LEARNED || table.providerTier[i] ||
                best.stage[table.app[i]] <= MatchStage.SUBSEQUENCE.ordinal
            ) {
                continue
            }
            val key = table.text[i]
            if (isAnchoredSubsequence(key, table.wordStarts[i], query)) {
                best.offer(
                    table.app[i],
                    MatchStage.SUBSEQUENCE.ordinal,
                    table.form[i],
                    kindWeight[table.kind[i].ordinal],
                    table.kind[i],
                    key,
                    null,
                    false,
                )
            }
        }
    }

    private fun rank(
        query: SearchQuery,
        best: Best,
    ): List<SearchResult> {
        val matched = ArrayList<Int>()
        // 印のある組は、名前で一致しても、その検索語では出さない（learning.md の R4）
        val suppressed = index.suppressedFor(query.strict.value)
        for (app in 0 until index.size) if (best.stage[app] != NO_MATCH && app !in suppressed) matched.add(app)
        val score = IntArray(index.size)
        for (app in matched) {
            val id = index.apps[app].id
            score[app] = best.weight[app] + signals.learnedBoost(query, id) + signals.usageBoost(id)
        }
        matched.sortWith(
            compareBy<Int> { best.stage[it] }
                .thenBy { best.form[it] }
                .thenByDescending { score[it] }
                .thenBy { index.labelRank[it] },
        )
        val results = ArrayList<SearchResult>(matched.size)
        var weak = 0
        for (app in matched) {
            // 提供元の段は上限に数えない（会社の名前で引いたアプリが、部分一致で切られないように）
            if (best.stage[app] == MatchStage.SUBSTRING.ordinal || best.stage[app] == MatchStage.SUBSEQUENCE.ordinal) {
                if (weak >= config.maxWeakResults) continue
                weak++
            }
            val entry = index.apps[app]
            val reason =
                MatchReason(
                    stage = MatchStage.entries[best.stage[app]],
                    keyKind = requireNotNull(best.kind[app]),
                    form = KeyForm.entries[best.form[app]],
                    matchedText = NormalizedText(requireNotNull(best.text[app])),
                    genre = best.genre[app],
                    viaQueryReading = best.viaReading[app],
                )
            results.add(SearchResult(entry.id, entry.label, reason, score[app]))
        }
        return results
    }

    /**
     * アプリごとの最もよい当たり方。配列はアプリの数だけで、照合 1 回ごとに作る（500 個で数 KB）。
     */
    private class Best(
        size: Int,
    ) {
        val stage = IntArray(size) { NO_MATCH }
        val form = IntArray(size)
        val weight = IntArray(size)
        val kind = arrayOfNulls<SearchKeyKind>(size)
        val text = arrayOfNulls<String>(size)
        val genre = arrayOfNulls<GenreId>(size)
        val viaReading = BooleanArray(size)

        /** 今の当たり方より上に並ぶなら置き換える。同じなら先に来たもの（検索語そのものの形・先のキー）を残す。 */
        @Suppress("LongParameterList")
        fun offer(
            app: Int,
            stage: Int,
            form: KeyForm,
            weight: Int,
            kind: SearchKeyKind,
            text: String,
            genre: GenreId?,
            viaReading: Boolean,
        ) {
            val current = this.stage[app]
            val better =
                stage < current ||
                    (stage == current && form.ordinal < this.form[app]) ||
                    (stage == current && form.ordinal == this.form[app] && weight > this.weight[app])
            if (!better) return
            this.stage[app] = stage
            this.form[app] = form.ordinal
            this.weight[app] = weight
            this.kind[app] = kind
            this.text[app] = text
            this.genre[app] = genre
            this.viaReading[app] = viaReading
        }
    }

    /**
     * 学習のキーの前方一致は、検索語がキーの [MatchingConfig.learnedPrefixMinPercent] 以上の長さのときだけ認める。
     *
     * 学習の語は長い（「ぺいぺいふりま」）ことがあり、頭の 2 字（「ぺい」）だけで前方一致にすると、
     * その 2 字を名前に持つアプリより上（段階 2）に出てしまうため（評価セットの「短い語」）。
     */
    private fun capLearnedStage(
        stage: Int,
        key: String,
        query: String,
    ): Int = if (stage == MatchStage.PREFIX.ordinal && query.length * PERCENT < key.length * config.learnedPrefixMinPercent) -1 else stage

    /**
     * ローマ字の学習のキーの前方一致の制限（[capLearnedStage] と同じ割合）。長さはローマ字の字数でなく、学習した語の字数で測る:
     * 検索語のローマ字が、学習した語の頭から必要な字数をローマ字にした長さ（[prefixLengths]）以上のときだけ認める。
     */
    private fun capLearnedRomajiStage(
        stage: Int,
        prefixLengths: IntArray,
        query: String,
    ): Int {
        if (stage != MatchStage.PREFIX.ordinal || prefixLengths.isEmpty()) return stage
        val needed = (prefixLengths.size * config.learnedPrefixMinPercent + PERCENT - 1) / PERCENT
        return if (needed > 0 && query.length < prefixLengths[needed - 1]) -1 else stage
    }

    private companion object {
        const val NO_MATCH = Int.MAX_VALUE
        const val PERCENT = 100

        /**
         * パッケージ名の語の完全一致を前方一致（段階 2）として扱う。
         *
         * パッケージ名の語には会社名（`google`）が多くのアプリに共通して入り、完全一致のままだと、
         * 名前が「Google …」のアプリ（前方一致）より、名前に Google を持たないアプリ（Files・Gmail）が上に並ぶため。
         */
        fun capPackageStage(
            stage: Int,
            kind: SearchKeyKind,
        ): Int = if (kind == SearchKeyKind.PACKAGE_NAME && stage == MatchStage.EXACT.ordinal) MatchStage.PREFIX.ordinal else stage
    }
}

/**
 * キー [key] と検索語の当たり方（[MatchStage] の ordinal）。当たらなければ -1。
 *
 * [prefixQuery] で前方・語の頭・部分の一致を、[completeQuery] で完全一致を見る（ローマ字では途中の形と完結した形が違うことがある）。
 * 段階 5（部分一致）は [allowSubstring] のときだけ見る。
 */
internal fun stageOf(
    key: String,
    wordStarts: IntArray,
    prefixQuery: String,
    completeQuery: String,
    allowSubstring: Boolean,
): Int {
    if (prefixQuery.isEmpty() || key.length < prefixQuery.length) return -1
    if (key == completeQuery) return MatchStage.EXACT.ordinal
    if (key.startsWith(prefixQuery)) return MatchStage.PREFIX.ordinal
    for (start in wordStarts) {
        if (key.regionMatches(start, prefixQuery, 0, prefixQuery.length)) return MatchStage.WORD_PREFIX.ordinal
    }
    if (allowSubstring && key.indexOf(prefixQuery, 1) >= 0) return MatchStage.SUBSTRING.ordinal
    return -1
}

/** [query] の 1 字目が [key] の頭か語の頭（[wordStarts]）にあり、残りの字がその後に順に現れるか。 */
internal fun isAnchoredSubsequence(
    key: String,
    wordStarts: IntArray,
    query: String,
): Boolean {
    if (query.isEmpty()) return false
    val anchor =
        if (key.isNotEmpty() && key[0] == query[0]) {
            0
        } else {
            wordStarts.firstOrNull { it < key.length && key[it] == query[0] } ?: return false
        }
    var q = 1
    var k = anchor + 1
    while (q < query.length && k < key.length) {
        if (key[k] == query[q]) q++
        k++
    }
    return q == query.length
}
