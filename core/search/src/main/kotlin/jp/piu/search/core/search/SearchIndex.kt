// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.RomajiConverter
import jp.piu.search.core.text.RomajiMode
import jp.piu.search.core.text.TextNormalizer

/**
 * 索引に入れるアプリ 1 つ: 識別子・表示名・検索キー。
 *
 * キーは [SearchKeyGenerator] で作ったもの（M2 からはデータベースから読み戻したもの）。
 */
public data class IndexedApp(
    /** アプリの識別子。 */
    val id: AppId,
    /** 表示名。結果の表示と、同じ点のときの並びに使う。 */
    val label: String,
    /** 検索キー。 */
    val keys: List<SearchKey>,
)

/**
 * 照合に使う、メモリ上の索引（変更しない）。
 *
 * 照合は入力 1 回ごとに走るので、キーを比べる相手（かなと英字・ローマ字・ジャンル）ごとに平たい配列へ並べ直しておき、
 * 照合の中ではオブジェクトをたどらず、変換もしない。表示名の順も作るときに求めておく。
 * 索引を作り直したら、新しいインスタンスに差し替える（architecture.md の「索引の作成と更新」の 5）。
 * 学習（[withLearning]）を変えたときも、学習を重ねた新しいインスタンスに差し替える。
 *
 * @param apps アプリとキー
 * @param suppressed 「この語では出さない」の印のある組（learning.md の R4）。検索語の厳密形がその組の検索語と等しいとき、そのアプリを結果から除く
 */
public class SearchIndex(
    apps: List<IndexedApp>,
    suppressed: Collection<LearnedPair> = emptyList(),
) {
    internal val apps: List<IndexedApp> = apps.toList()

    /** 印のある組（[restrictedTo] と [withLearning] で引き継ぐ）。 */
    private val suppressedPairs: List<LearnedPair> = suppressed.toList()

    /** 検索語の厳密形 → その検索語で出さないアプリの番号（[apps] の添え字）。 */
    private val suppressedApps: Map<String, Set<Int>>

    /** 表示名・読みの文字列のキー。検索語の厳密形・緩い形と比べる。 */
    internal val textKeys: KeyTable

    /** 標準形のローマ字のキー。検索語のローマ字と比べる。 */
    internal val romajiKeys: KeyTable

    /** ジャンルの id → そのジャンルに属するアプリの番号（[apps] の添え字）。 */
    internal val appsByGenre: Map<GenreId, IntArray>

    /** アプリの番号 → 表示名の順の順位。同じ点のときの並びを毎回同じにするため。 */
    internal val labelRank: IntArray

    init {
        val text = KeyTable.Builder()
        val romaji = KeyTable.Builder()
        val genres = LinkedHashMap<GenreId, MutableList<Int>>()
        val commonPackageWords = commonPackageWords(this.apps)
        this.apps.forEachIndexed { appIndex, app ->
            for (key in app.keys) {
                when (key.kind) {
                    SearchKeyKind.ROMAJI -> {
                        romaji.add(appIndex, key)
                    }

                    SearchKeyKind.GENRE -> {
                        genres.getOrPut(GenreId(key.text.value)) { ArrayList() }.add(appIndex)
                    }

                    SearchKeyKind.LEARNED -> {
                        text.add(appIndex, key)
                        // ローマ字で打っても学習が効くよう、かなの学習の語はローマ字の表にも置く（「おかね」を学んだら okane でも出す）。
                        // 出た理由には学習した語そのものを出す
                        learnedRomaji(key)?.let { (romajiKey, prefixLengths) ->
                            romaji.add(appIndex, romajiKey, shown = key.text.value, prefixLengths = prefixLengths)
                        }
                    }

                    SearchKeyKind.PUBLISHER -> {
                        text.add(appIndex, key, providerTier = true)
                    }

                    SearchKeyKind.PACKAGE_NAME -> {
                        // 語の読み（かな。ブランド表から）も提供元の段に置く。表には一般の英語（map・weather）もあり、名前の段に置くと
                        // 「まっぷ」でパッケージ名に map を持つアプリが、名前の語の頭で当たるアプリより上に出るため
                        val reading = !key.text.value.isRomajiOnly()
                        text.add(appIndex, key, providerTier = reading || key.text.value in commonPackageWords)
                    }

                    else -> {
                        text.add(appIndex, key)
                    }
                }
            }
        }
        textKeys = text.build()
        romajiKeys = romaji.build()
        appsByGenre = genres.mapValues { (_, list) -> list.distinct().toIntArray() }
        labelRank = rankByLabel(this.apps)
        val position = HashMap<AppId, Int>()
        this.apps.forEachIndexed { i, app -> position.putIfAbsent(app.id, i) }
        suppressedApps =
            suppressedPairs
                .mapNotNull { pair -> position[pair.app]?.let { pair.query.value to it } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, list) -> list.toSet() }
    }

    /** 検索語の厳密形 [query] で出さないアプリの番号。 */
    internal fun suppressedFor(query: String): Set<Int> = suppressedApps[query] ?: emptySet()

    /** 索引のアプリの数。 */
    public val size: Int get() = apps.size

    // 学習の一覧で、記録のアプリの表示名を引くときだけ作る
    private val labels: Map<AppId, String> by lazy { apps.associate { it.id to it.label } }

    /** [app] の表示名。索引にない（消えた）アプリなら null。 */
    public fun labelOf(app: AppId): String? = labels[app]

    /**
     * プロファイルが [profiles] に入るか入らないか（[exclude]）で絞った索引（ui.md の「仕事用プロファイル」。検索は選んでいるプロファイルの中だけ）。
     *
     * 絞った中で作り直すので、多くのアプリに共通するパッケージ名の語（[COMMON_PACKAGE_WORD_MIN_APPS]）もそのプロファイルのアプリで数える。
     */
    public fun restrictedTo(
        profiles: Set<Long>,
        exclude: Boolean = false,
    ): SearchIndex {
        val kept = apps.filter { (it.id.profileSerial in profiles) != exclude }
        return if (kept.size == apps.size) this else SearchIndex(kept, suppressedPairs)
    }

    /**
     * 学習 [memory] を重ねた索引（learning.md の R3・R4）。前に重ねた学習は捨てて、[memory] で置き換える。
     *
     * - 救済になったことがある組（[LearnedRecord.rescued]）の検索語を、アプリの [SearchKeyKind.LEARNED] のキーにする
     * - 印のある組は、キーにせず、その検索語で出さない組にする
     *
     * 索引にないアプリの記録は使わない（消えたアプリの記録は [memory] に残る）。加点は [LearnedMemory.learnedBoost] が別に受け持つ。
     */
    public fun withLearning(memory: LearnedMemory): SearchIndex {
        val keysByApp =
            memory.records.values
                .filter { it.rescued && !it.suppressed }
                .groupBy({ it.app }, { SearchKey(it.query, SearchKeyKind.LEARNED) })
        val learnedApps =
            apps.map { app ->
                val base = app.keys.filterNot { it.kind == SearchKeyKind.LEARNED }
                val learned = keysByApp[app.id].orEmpty()
                if (learned.isEmpty() && base.size == app.keys.size) app else app.copy(keys = base + learned)
            }
        return SearchIndex(
            learnedApps,
            memory.records.values
                .filter { it.suppressed }
                .map { it.pair },
        )
    }

    /** 「すべてのアプリ」の順に並べたアプリ（[AppNameOrder]）。 */
    public fun inNameOrder(): List<NameOrderedApp> = AppNameOrder.sort(apps)

    /** 定数と作り方。 */
    public companion object {
        /** 空の索引。索引を読み込む前に使う。 */
        public val EMPTY: SearchIndex = SearchIndex(emptyList())

        /** [entries] から [generator] でキーを作って索引にする。形態素解析器を使うので裏のスレッドで呼ぶ。 */
        public fun build(
            entries: List<AppEntry>,
            generator: SearchKeyGenerator,
        ): SearchIndex = SearchIndex(entries.map { IndexedApp(it.id, it.label, generator.generate(it)) })
    }
}

/**
 * 学習の語 [key] の標準形のローマ字のキーと、語の頭から i + 1 字（コードポイント）をローマ字にした長さの列。
 * 英字だけの語（そのまま英字の検索語と比べる）と、ローマ字にできない語（漢字が残る）は null。
 *
 * 長さの列は、学習のキーの前方一致の長さの制限を、ローマ字でもかなの字数で測るために使う
 * （ローマ字の字数で測ると「hot」が「ほてる」の半分になり、かなの「ほ」1 字で当たるのと同じになるため）。
 */
private fun learnedRomaji(key: SearchKey): Pair<SearchKey, IntArray>? {
    val text = key.text.value
    if (text.isRomajiOnly()) return null
    val romaji = RomajiConverter.toStandard(key.text, RomajiMode.COMPLETE).value
    if (!romaji.isRomajiOnly()) return null
    val count = text.codePointCount(0, text.length)
    val lengths =
        IntArray(count) { i ->
            val end = text.offsetByCodePoints(0, i + 1)
            RomajiConverter.toStandard(NormalizedText(text.substring(0, end)), RomajiMode.COMPLETE).value.length
        }
    return SearchKey(NormalizedText(romaji), SearchKeyKind.LEARNED) to lengths
}

/**
 * 比べる相手が同じキーを並べた平たい表。添え字 i が 1 つのキー。
 *
 * @property shown 出た理由に出す文字列。ふつうは [text] と同じで、ローマ字の表の学習のキーだけ、学習した語（かな）にする
 * @property prefixLengths ローマ字の表の学習のキーだけ: 学習した語の頭から i + 1 字をローマ字にした長さ。ほかのキーは空
 * @property latin 英字と数字だけのキーか（頭文字・飛ばし一致の相手。ローマ字の表では使わない）
 * @property providerTier 提供元の段（[MatchStage.PROVIDER]）にだけ当てるキーか。提供元のキーと、多くのアプリに共通するパッケージ名の語
 */
internal class KeyTable(
    val text: Array<String>,
    val shown: Array<String>,
    val prefixLengths: Array<IntArray>,
    val app: IntArray,
    val kind: Array<SearchKeyKind>,
    val form: Array<KeyForm>,
    val wordStarts: Array<IntArray>,
    val latin: BooleanArray,
    val providerTier: BooleanArray,
) {
    val size: Int get() = text.size

    class Builder {
        private val keys = ArrayList<Pair<Int, SearchKey>>()
        private val shown = ArrayList<String>()
        private val prefixLengths = ArrayList<IntArray>()
        private val providerTier = ArrayList<Boolean>()

        fun add(
            appIndex: Int,
            key: SearchKey,
            shown: String = key.text.value,
            prefixLengths: IntArray = NO_LENGTHS,
            providerTier: Boolean = false,
        ) {
            if (key.text.isEmpty) return
            keys.add(appIndex to key)
            this.shown.add(shown)
            this.prefixLengths.add(prefixLengths)
            this.providerTier.add(providerTier)
        }

        fun build(): KeyTable =
            KeyTable(
                text = Array(keys.size) { keys[it].second.text.value },
                shown = shown.toTypedArray(),
                prefixLengths = prefixLengths.toTypedArray(),
                app = IntArray(keys.size) { keys[it].first },
                kind = Array(keys.size) { keys[it].second.kind },
                form = Array(keys.size) { keys[it].second.form },
                wordStarts = Array(keys.size) { keys[it].second.wordStarts.toIntArray() },
                latin =
                    BooleanArray(keys.size) {
                        keys[it]
                            .second.text.value
                            .isRomajiOnly()
                    },
                providerTier = providerTier.toBooleanArray(),
            )

        private companion object {
            val NO_LENGTHS = IntArray(0)
        }
    }
}

/**
 * パッケージ名の語を提供元の段（[MatchStage.PROVIDER]）に下げる、その語を持つアプリの数の下限（仮。M1-06b で 3 にした）。
 *
 * 多くのアプリに共通するパッケージ名の語は会社名（`google`・`rakuten`）や分野の語（`music`）で、アプリを区別しない。
 * 名前の段に置くと、名前にその語を持たないアプリ（`google` → Files）が名前で当たるアプリと並ぶ。
 * M1-06b では照合から外したが、会社の名前で引きたい（「rakuten」で iSPEED）ので、外さず下の段に並べる（decisions.md の 2026-11-06）。
 * 読み（`らくてん`）も同じ文字列で数えるので、語と同じく下がる。
 */
internal const val COMMON_PACKAGE_WORD_MIN_APPS = 3

/** 索引の中で [COMMON_PACKAGE_WORD_MIN_APPS] 個以上のアプリが持つパッケージ名の語（と読み）。端末のアプリの組み合わせで決まる。 */
private fun commonPackageWords(apps: List<IndexedApp>): Set<String> {
    val count = HashMap<String, Int>()
    for (app in apps) {
        app.keys
            .filter { it.kind == SearchKeyKind.PACKAGE_NAME }
            .mapTo(HashSet()) { it.text.value }
            .forEach { count.merge(it, 1, Int::plus) }
    }
    return count.filterValues { it >= COMMON_PACKAGE_WORD_MIN_APPS }.keys
}

/**
 * 表示名の順の順位。表示名の厳密形（ひらがな・小文字）の符号位置の順で並べ、同じならもとの表示名・パッケージ名・Activity 名・プロファイルの順にして、順位を一意にする。
 *
 * かなは符号位置の順がほぼ五十音順になる。`Collator`（日本語）はカタカナとひらがなを別の字として並べ（ヤフーD が やふーA より先）、
 * 端末の JDK の版で順が変わりうるので使わない。
 */
private fun rankByLabel(apps: List<IndexedApp>): IntArray {
    val sortKeys = apps.map { TextNormalizer.strict(it.label).value }
    val order =
        apps.indices.sortedWith(
            compareBy<Int> { sortKeys[it] }
                .thenBy { apps[it].label }
                .thenBy { apps[it].id.packageName }
                .thenBy { apps[it].id.activityName }
                .thenBy { apps[it].id.profileSerial },
        )
    val rank = IntArray(apps.size)
    order.forEachIndexed { position, appIndex -> rank[appIndex] = position }
    return rank
}
