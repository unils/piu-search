// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.RomajiConverter
import jp.piu.search.core.text.RomajiMode
import jp.piu.search.core.text.TextNormalizer
import java.text.Normalizer

/**
 * アプリ 1 つの情報から検索キーの一覧を作る（matching.md の「検索キーの生成」）。
 *
 * 索引を作り直すときに裏のスレッドで呼ぶ。形態素解析器を使うので、入力のたびの照合の中では呼ばない。
 * 状態を持たないので、[analyzer] が複数のスレッドに対応していれば同時に呼んでよい。
 *
 * 作るキー（表示名と、別の言語の名前のそれぞれについて 1〜3 を作る）:
 *
 * 1. 名前（[SearchKeyKind.NAME]・[SearchKeyKind.LOCALIZED_NAME]）: 名前の厳密形
 * 2. 読み（[SearchKeyKind.READING]）: 語ごとの読みをつないだもの。1 語に読みが複数あれば（ブランド表の `x` → えっくす・ついったー）
 *    組み合わせを作り、[maxReadingCombinations] 個で止める。どの語も先頭の読み（最も一般的な読み）を使う組み合わせが最初に来る
 * 3. ローマ字（[SearchKeyKind.ROMAJI]）: 2 の各々の標準形のローマ字と、英字の語を綴りのまま残した形の標準形のローマ字
 * 4. パッケージ名の語（[SearchKeyKind.PACKAGE_NAME]）と、そのブランド表の読み（`yahoo` → `やふー`）
 * 5. 提供元（[SearchKeyKind.PUBLISHER]）: 署名の証明書の組織名の語と、その読み（[PublisherWords]）
 * 6. ジャンル（[SearchKeyKind.GENRE]）
 *
 * 1〜5 には緩い形（[KeyForm.LOOSE]）も、厳密形と違うときだけ作る。
 * パッケージ名と提供元の読みのローマ字は、段階と出た理由を引き継いで [SearchIndex] が組み立てる。
 * 同じ文字列のキーは、比べる相手（検索語のローマ字・かなと英字・ジャンル）が同じなら 1 つにし、先に作った種類を残す
 * （名前 → 別の言語の名前 → 読み → ローマ字 → パッケージ名 → 提供元 → ジャンルの順。厳密形が先）。
 *
 * 表示名の頭の「Y!」（Yahoo! の略。「Y!乗換案内」）は、やふー と読み、綴りの形では `yahoo` とも書く（1 字の読み わい も残す）。
 *
 * [ReadingAnalyzer.analyze] には、M1-02 の約束のとおり NFKC だけをかけた名前を渡す。
 *
 * @property analyzer かな・漢字の語の区切りと読み。辞書を読み込む前は [jp.piu.search.core.reading.NoReadingAnalyzer] でよい
 * @property latinReader 英字の語の読み
 * @property maxReadingCombinations 名前 1 つあたりの読みの組み合わせの上限。1 以上
 */
public class SearchKeyGenerator(
    private val analyzer: ReadingAnalyzer,
    private val latinReader: LatinWordReader,
    private val maxReadingCombinations: Int = DEFAULT_MAX_READING_COMBINATIONS,
) {
    init {
        require(maxReadingCombinations >= 1) { "読みの組み合わせの上限は 1 以上: $maxReadingCombinations" }
    }

    /** [app] の検索キーの一覧。厳密形のキーが先、緩い形のキーが後に並ぶ。中の順は毎回同じ。 */
    public fun generate(app: AppEntry): List<SearchKey> {
        val keys = KeyCollector()
        val seenNames = HashSet<String>()
        (listOf(app.label) + app.localizedLabels).forEach { raw ->
            val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            val strict = TextNormalizer.strict(nfkc)
            // 別の言語の名前は、表示名か先の名前と同じなら作らない
            if (strict.isEmpty || !seenNames.add(strict.value)) return@forEach
            val kind = if (seenNames.size == 1) SearchKeyKind.NAME else SearchKeyKind.LOCALIZED_NAME
            addNameKeys(nfkc, strict, kind, keys)
        }
        val packageWords = packageWords(app.id.packageName)
        packageWords.forEach { keys.add(SearchKey(it, SearchKeyKind.PACKAGE_NAME)) }
        // 読みは語の後に置く（同じ文字列なら語の方を残すため）。会社の名前を、かなや漢字（楽天 → らくてん）で引けるように
        packageWords.forEach { word -> latinReader.readings(word.value).forEach { keys.add(SearchKey(it, SearchKeyKind.PACKAGE_NAME)) } }
        publisherKeys(app.signerNames).forEach(keys::add)
        app.genres.forEach { keys.add(SearchKey(NormalizedText(it.value), SearchKeyKind.GENRE)) }
        return keys.build()
    }

    private fun addNameKeys(
        nfkc: String,
        strict: NormalizedText,
        kind: SearchKeyKind,
        keys: KeyCollector,
    ) {
        val yahooMark = nfkc.trimStart().startsWith(YAHOO_MARK)
        val words =
            splitNameWords(nfkc, analyzer.analyze(nfkc), latinReader).mapIndexedNotNull { i, word ->
                val spelling = TextNormalizer.strict(word.surface)
                // 「+」は正規化で消えて表記が空になるが、読みを持つので残す
                if (spelling.isEmpty && word.kind != WordKind.PLUS) {
                    null
                } else if (i == 0 && yahooMark && word.surface == YAHOO_MARK_LETTER) {
                    ReadWord(word.kind, spelling, (latinReader.readings(YAHOO_SPELLING) + readingsOf(word)).distinct())
                } else {
                    ReadWord(word.kind, spelling, readingsOf(word))
                }
            }
        if (words.isEmpty()) {
            keys.add(SearchKey(strict, kind))
            return
        }
        val name = Joined.of(words.map { it.spelling.value })
        // 語ごとに正規化してつないだ形が、名前全体の厳密形と食い違う（記号を除いて字が合成されるなど）ときは、区切りを捨てて全体の形を使う
        keys.add(if (name.text == strict.value) name.toKey(kind) else SearchKey(strict, kind))

        for (combination in combinations(words.map { it.readingOptions }, maxReadingCombinations)) {
            val reading = Joined.of(combination.map { it.value })
            keys.add(reading.toKey(SearchKeyKind.READING))
            keys.add(reading.toRomaji().toKey(SearchKeyKind.ROMAJI))
        }
        // 英字の綴りで打つ人のため、英字の語は綴りのまま、ほかの語は先頭の読みでつなぐ（yahoo + てんき → yahootenki）
        val spelled =
            Joined.of(
                words.map {
                    when (it.kind) {
                        WordKind.LATIN -> it.spelling.value
                        WordKind.PLUS -> PLUS_SPELLING
                        else -> it.readingOptions.first().value
                    }
                },
            )
        keys.add(spelled.toRomaji().toKey(SearchKeyKind.ROMAJI))
        if (yahooMark && words.first().kind == WordKind.LATIN) {
            // Y! を yahoo の綴りで打つ人のため（yahoonorikae）
            val parts = spelled.parts.toMutableList().also { it[0] = YAHOO_SPELLING }
            keys.add(Joined.of(parts).toRomaji().toKey(SearchKeyKind.ROMAJI))
        }
    }

    /**
     * 証明書の DN [signerNames] の組織名から、提供元のキーを作る。英字の語は綴りとブランド表の読み、かな・漢字の語は表記と形態素解析器の読み。
     */
    private fun publisherKeys(signerNames: List<String>): List<SearchKey> {
        val spellings = ArrayList<NormalizedText>()
        val readings = ArrayList<NormalizedText>()
        for (organization in signerNames.flatMap(PublisherWords::organizations)) {
            val nfkc = Normalizer.normalize(organization, Normalizer.Form.NFKC)
            for (word in splitNameWords(nfkc, analyzer.analyze(nfkc), latinReader)) {
                val spelling = TextNormalizer.strict(word.surface)
                when (word.kind) {
                    WordKind.LATIN -> {
                        if (!PublisherWords.isPublisherWord(spelling.value)) continue
                        spellings.add(spelling)
                        readings.addAll(latinReader.readings(spelling.value))
                    }

                    WordKind.JAPANESE -> {
                        if (spelling.isEmpty || PublisherWords.isJapaneseLegalWord(word.surface)) continue
                        spellings.add(spelling)
                        word.reading
                            ?.let { TextNormalizer.strict(it) }
                            ?.takeUnless { it.isEmpty }
                            ?.let(readings::add)
                    }

                    // 数字・ほかの用字の語は読みを付けられず、提供元の手がかりとして弱い
                    WordKind.OTHER, WordKind.PLUS -> {}
                }
            }
        }
        return (spellings + readings).distinct().map { SearchKey(it, SearchKeyKind.PUBLISHER) }
    }

    /** 語の読みの候補。読みがなければ表記の厳密形を代わりに使う（読みのキーの中に表記のまま残す）。 */
    private fun readingsOf(word: NameWord): List<NormalizedText> {
        val readings =
            when (word.kind) {
                WordKind.JAPANESE -> {
                    val dictionary = listOfNotNull(word.reading?.let { TextNormalizer.strict(it) }?.takeUnless { it.isEmpty })
                    (dictionary + ReadingSupplements.of(word.surface)).distinct()
                }

                WordKind.LATIN -> {
                    latinReader.readings(word.surface)
                }

                WordKind.OTHER -> {
                    emptyList()
                }

                WordKind.PLUS -> {
                    listOf(PLUS_READING)
                }
            }
        return readings.ifEmpty { listOf(TextNormalizer.strict(word.surface)) }
    }

    /** 定数。 */
    public companion object {
        /**
         * 読みの組み合わせの上限の既定値。matching.md の例の値で、根拠のある数ではない（仮）。
         *
         * ブランド表で読みが 2 つの語が 3 つ並ぶと 8 になる。評価セットで足りないとわかったら見直す。
         */
        public const val DEFAULT_MAX_READING_COMBINATIONS: Int = 8
    }
}

/** 表示名の頭の Yahoo! の略（「Y!乗換案内」）。 */
private const val YAHOO_MARK = "Y!"
private const val YAHOO_MARK_LETTER = "Y"

/** Y! の読みを引く綴り（ブランド表の yahoo）と、綴りの形のローマ字のキーでの Y!。 */
private const val YAHOO_SPELLING = "yahoo"

/** 「+」の読み。 */
private val PLUS_READING = NormalizedText("ぷらす")

/** 英字の綴りを残したローマ字のキーでの「+」（`disneyplus`）。 */
private const val PLUS_SPELLING = "plus"

/** 読みを付けた語。 */
private class ReadWord(
    val kind: WordKind,
    val spelling: NormalizedText,
    val readingOptions: List<NormalizedText>,
)

/**
 * 語をつないだ文字列と、2 語目以降の語の頭の位置。
 */
private class Joined(
    val text: String,
    val wordStarts: List<Int>,
    /** つないだ語。 */
    val parts: List<String> = emptyList(),
) {
    fun toKey(kind: SearchKeyKind): SearchKey = SearchKey(NormalizedText(text), kind, wordStarts)

    /**
     * 標準形のローマ字にする。語の頭の位置は、語の手前までをローマ字にした長さに移す。
     *
     * 手前までの変換は全体の変換の前の部分になる（促音が語の終わりにある場合などを除く）ので、位置がずれない。
     */
    fun toRomaji(): Joined {
        val romaji = RomajiConverter.toStandard(NormalizedText(text), RomajiMode.COMPLETE).value
        val starts = wordStarts.map { RomajiConverter.toStandard(NormalizedText(text.substring(0, it)), RomajiMode.COMPLETE).value.length }
        return Joined(romaji, cleanStarts(starts, romaji.length))
    }

    companion object {
        fun of(parts: List<String>): Joined {
            val builder = StringBuilder()
            val starts = ArrayList<Int>(parts.size)
            parts.forEach { part ->
                if (builder.isNotEmpty()) starts.add(builder.length)
                builder.append(part)
            }
            return Joined(builder.toString(), cleanStarts(starts, builder.length), parts)
        }
    }
}

/** 語の頭の位置を、0 より大きく [length] より小さい、重なりのない昇順にそろえる。 */
private fun cleanStarts(
    starts: List<Int>,
    length: Int,
): List<Int> = starts.filter { it in 1 until length }.distinct().sorted()

/**
 * 候補の組み合わせを、先頭の候補どうしの組から順に [limit] 個まで作る。
 *
 * 後ろの語の候補から先に回す（odometer の順）。どの語の候補も 1 つ以上ある前提。
 */
internal fun <T> combinations(
    options: List<List<T>>,
    limit: Int,
): List<List<T>> {
    if (options.isEmpty()) return emptyList()
    val indices = IntArray(options.size)
    val result = ArrayList<List<T>>()
    while (result.size < limit) {
        result.add(options.indices.map { options[it][indices[it]] })
        var position = options.size - 1
        while (position >= 0) {
            indices[position]++
            if (indices[position] < options[position].size) break
            indices[position] = 0
            position--
        }
        if (position < 0) break
    }
    return result
}

/**
 * パッケージ名の 2 番目の部分のときだけ除く、ドメインの第 2 レベルの語（`jp.co.〜`・`au.com.〜`・`jp.go.〜`）。
 *
 * 2 番目の位置に限るのは、名前の中の同じ綴りの語（`com.mo_t.go` の `go`）を残すため（research/package-names.md）。
 */
private val SECOND_LEVEL_DOMAIN_WORDS: Set<String> =
    setOf("co", "ne", "or", "ac", "go", "gr", "ed", "lg", "com", "org", "net", "io", "www")

/**
 * どの位置でも除く、アプリも提供元も区別しない語（research/package-names.md の 4 分類のうち 3 つ。F-Droid・IzzyOnDroid の索引と評価セットで数えた）。
 *
 * 会社の名前・開発者の名前・分野の語（`music` など）は、提供元やアプリを表すので除かない。
 */
private val COMMON_PACKAGE_WORDS: Set<String> =
    setOf(
        // コードの置き場（io.github.<人>.〜）
        "github",
        "gitlab",
        "codeberg",
        "sourceforge",
        "bitbucket",
        "framagit",
        // 技術の語
        "android",
        "app",
        "apps",
        "application",
        "mobile",
        "client",
        "example",
        // 版の種類
        "free",
        "lite",
        "full",
        "pro",
        "premium",
        "paid",
        "beta",
        "alpha",
        "nightly",
        "debug",
        "dev",
        "release",
        "stable",
        "preview",
        "canary",
        "foss",
        "fdroid",
        "libre",
        "play",
        "gms",
        "test",
        "demo",
        "sample",
    )

/** パッケージ名の語として残す最小の字数。1 字の語は前方一致で多くのアプリに当たり、区別に役立たない。 */
private const val MIN_PACKAGE_WORD_LENGTH = 2

/**
 * パッケージ名 [packageName] の語（matching.md の 2 節）。`.` と `_` で分け、最初の部分・2 番目の部分のドメインの語・ありふれた語・数字だけの語を除く。
 *
 * `jp.co.recruit.mtl.android.hotpepper` → `recruit`・`mtl`・`hotpepper`。
 */
internal fun packageWords(packageName: String): List<NormalizedText> =
    packageName
        .split('.')
        .drop(1)
        .withIndex()
        .filterNot { (i, part) -> i == 0 && TextNormalizer.strict(part).value in SECOND_LEVEL_DOMAIN_WORDS }
        .flatMap { it.value.split('_') }
        .map { TextNormalizer.strict(it) }
        .filter { word ->
            word.value.length >= MIN_PACKAGE_WORD_LENGTH && word.value !in COMMON_PACKAGE_WORDS && !word.value.all { it.isDigit() }
        }.distinct()

/**
 * キーを集め、重なりを除き、緩い形を足す。
 */
private class KeyCollector {
    private val strictKeys = ArrayList<SearchKey>()

    fun add(key: SearchKey) {
        if (!key.text.isEmpty) strictKeys.add(key)
    }

    fun build(): List<SearchKey> {
        val seen = HashSet<Pair<String, KeySpace>>()
        val result = ArrayList<SearchKey>(strictKeys.size * 2)
        val unique = strictKeys.filter { seen.add(it.text.value to it.kind.space) }
        result.addAll(unique)
        unique.mapNotNullTo(result) { key -> looseOf(key)?.takeIf { seen.add(it.text.value to it.kind.space) } }
        return result
    }

    private fun looseOf(key: SearchKey): SearchKey? {
        val loosen: (String) -> String =
            when (key.kind) {
                SearchKeyKind.NAME, SearchKeyKind.LOCALIZED_NAME, SearchKeyKind.READING,
                SearchKeyKind.PACKAGE_NAME, SearchKeyKind.PUBLISHER,
                -> {
                    { TextNormalizer.loose(NormalizedText(it)).value }
                }

                SearchKeyKind.ROMAJI -> {
                    { RomajiConverter.loose(NormalizedText(it)).value }
                }

                else -> {
                    return null
                }
            }
        val text = key.text.value
        val loose = loosen(text)
        if (loose == text || loose.isEmpty()) return null
        val starts =
            key.wordStarts.map { start ->
                val before = loosen(text.substring(0, start)).length
                // ローマ字の緩い形は同じ字の連続を 1 つにする。語の頭の字が前の語の終わりの字に吸われたら（zyorudan|norikae → zyorudanorikae）、
                // 吸った字を語の頭とみなす。そうしないと語の頭が 1 字後ろにずれ、「norikae」が語の頭から一致しない
                val absorbed =
                    key.kind == SearchKeyKind.ROMAJI && before > 0 && text[start] == text[start - 1] &&
                        loosen(text.substring(0, start + 1)).length == before
                if (absorbed) before - 1 else before
            }
        return SearchKey(NormalizedText(loose), key.kind, cleanStarts(starts, loose.length), KeyForm.LOOSE)
    }
}

/** キーを比べる相手。相手が同じキーどうしでだけ、同じ文字列を重なりとみなす。 */
private enum class KeySpace { TEXT, ROMAJI, GENRE }

private val SearchKeyKind.space: KeySpace
    get() =
        when (this) {
            SearchKeyKind.ROMAJI -> KeySpace.ROMAJI
            SearchKeyKind.GENRE -> KeySpace.GENRE
            else -> KeySpace.TEXT
        }
