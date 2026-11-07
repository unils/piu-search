// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.NoReadingAnalyzer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SearchEngineTest {
    /**
     * matching.md の「照合の段階と順位」の表の例と、「カタカナの名前を英字で打つ場合」「ローマ字」の例。
     * 列: 検索語・出てほしいアプリ・段階・当たったキーの種類。
     */
    @ParameterizedTest(name = "{0} → {1}（{2}）")
    @CsvSource(
        delimiter = '|',
        textBlock = """
ちず        | 地図                 | EXACT       | READING
のりか      | 乗換案内             | PREFIX      | READING
あんない    | ジョルダン乗換案内   | WORD_PREFIX | READING
まっぷ      | Yahoo!マップ         | WORD_PREFIX | NAME
ちず        | Google マップ        | GENRE       | GENRE
ちず        | Yahoo!マップ         | GENRE       | GENRE
ぎんこう    | 三井住友銀行         | SUBSTRING   | READING
gmp         | Google Maps          | SUBSEQUENCE | NAME
hot         | ホットペッパーグルメ | PREFIX      | ROMAJI
hott        | ホットペッパーグルメ | PREFIX      | ROMAJI
ほt         | ホットペッパーグルメ | PREFIX      | ROMAJI
hotpepper   | ホットペッパーグルメ | PREFIX      | PACKAGE_NAME
hotpe       | ホットペッパーグルメ | PREFIX      | PACKAGE_NAME
pepper      | ホットペッパーグルメ | SUBSTRING   | PACKAGE_NAME
nhk         | NHKプラス            | PREFIX      | NAME
んhk        | NHKプラス            | PREFIX      | ROMAJI
norikae     | 乗換案内             | PREFIX      | ROMAJI
norikaeanai | 乗換案内             | EXACT       | ROMAJI
のりk       | 乗換案内             | PREFIX      | ROMAJI
yafu        | Yahoo!マップ         | PREFIX      | ROMAJI
やふー      | Yahoo!マップ         | PREFIX      | READING
chizu       | Google マップ        | GENRE       | GENRE
でぃずにーぷらす | Disney+         | EXACT       | READING
""",
    )
    fun `段階の例`(
        query: String,
        label: String,
        stage: MatchStage,
        kind: SearchKeyKind,
    ) {
        val result = engine.search(query(query)).single { it.label == label }
        assertEquals(stage to kind, result.reason.stage to result.reason.keyKind, "理由: ${result.reason}")
    }

    @Test
    fun `上の段階ほど先に並ぶ`() {
        // 「ちず」: 読みが等しい「地図」（段階 1）がジャンルの一致（段階 4）より先
        val labels = engine.search(query("ちず")).map { it.label }
        assertEquals("地図", labels.first())
        assertTrue(labels.indexOf("地図") < labels.indexOf("Google マップ"))
        val stages = engine.search(query("ちず")).map { it.reason.stage }
        assertEquals(stages.sorted(), stages)
    }

    @Test
    fun `緩い形の一致は同じ段階の厳密形の一致の直後に並ぶ`() {
        val engine = engineOf("らーめん横丁", "らめんマップ", "ラーメン食堂")
        // 「らめん」: らめんマップ は厳密形の前方一致、ほかの 2 つは長音を除いた緩い形の前方一致
        val results = engine.search(query("らめん"))
        assertEquals(listOf("らめんマップ", "らーめん横丁", "ラーメン食堂"), results.map { it.label })
        assertEquals(listOf(KeyForm.STRICT, KeyForm.LOOSE, KeyForm.LOOSE), results.map { it.reason.form })
        assertTrue(results.all { it.reason.stage == MatchStage.PREFIX })
    }

    @Test
    fun `かなだけの検索語がローマ字のキーに当たったら緩い形の一致にする`() {
        // めーる → meru は めるかり の前方に当たるが、かなでは長音の分だけ違う
        val result = engineOf("メルカリ").search(query("めーる")).single()
        assertEquals(KeyForm.LOOSE, result.reason.form)
    }

    @Test
    fun `漢字を含む検索語は名前のキーとの一致をすぐ返し、読みを付けた結果を別の呼び出しで足す`() {
        val raw = SearchQuery.of("乗り換え")
        assertTrue(raw.needsReading)
        // 読みの前: 名前の「乗換」は送り仮名が違うので当たらない
        assertTrue(engine.search(raw).none { it.label == "乗換案内" })

        val withReading = raw.withReading(TestAnalyzers.kuromoji)
        assertFalse(withReading.needsReading)
        assertEquals("のりかえ", withReading.reading?.value)
        val result = engine.search(withReading).first { it.label == "乗換案内" }
        assertEquals(MatchStage.PREFIX, result.reason.stage)
        assertTrue(result.reason.viaQueryReading)
    }

    @Test
    fun `漢字の検索語は読みを付ける前でも名前の表記で当たる`() {
        val result = engine.search(SearchQuery.of("乗換")).first()
        assertEquals("乗換案内", result.label)
        assertEquals(MatchStage.PREFIX to SearchKeyKind.NAME, result.reason.stage to result.reason.keyKind)
        assertFalse(result.reason.viaQueryReading)
        // 漢字のジャンルの語は、読みを付ける前に当たる
        assertTrue(engine.search(SearchQuery.of("地図")).any { it.label == "Yahoo!マップ" && it.reason.stage == MatchStage.GENRE })
    }

    @Test
    fun `読みの付かない漢字の検索語は読みを付けても同じ結果`() {
        val raw = SearchQuery.of("乗換")
        val noReading = raw.withReading { emptyList() }
        assertFalse(noReading.needsReading)
        assertEquals(engine.search(raw), engine.search(noReading))
        // 漢字を含まない検索語は、そのまま返る
        val kana = SearchQuery.of("のりかえ")
        assertTrue(kana.withReading(TestAnalyzers.kuromoji) === kana)
    }

    @Test
    fun `標準形のローマ字が空になる入力の途中ではローマ字の経路を使わない`() {
        // ch で終わると、ちゃ・ち のどちらにもなるので共通の部分がない（M1-01 の約束）。tizu の前方には当てない
        val engine = engineOf("地図", "ChatWork")
        assertEquals(listOf("ChatWork"), engine.search(query("ch")).map { it.label })
        assertEquals(listOf("ChatWork"), engine.search(query("c")).map { it.label })
        // 続けて打てば当たる
        assertTrue(engine.search(query("chi")).any { it.label == "地図" })
    }

    @Test
    fun `出た理由に当たったキーとジャンルを持つ`() {
        val genre = engine.search(query("ちず")).first { it.label == "Google マップ" }.reason
        assertEquals(GenreId("maps"), genre.genre)
        assertEquals("ちず", genre.matchedText.value)

        val reading = engine.search(query("あんない")).first { it.label == "ジョルダン乗換案内" }.reason
        assertEquals("じょるだんのりかえあんない", reading.matchedText.value)
        assertNull(reading.genre)
    }

    @Test
    fun `空の検索語と空の索引では何も返さない`() {
        assertEquals(emptyList<SearchResult>(), engine.search(SearchQuery.of("")))
        assertEquals(emptyList<SearchResult>(), engine.search(SearchQuery.of(" !? ")))
        assertEquals(emptyList<SearchResult>(), SearchEngine(SearchIndex.EMPTY).search(query("ちず")))
    }

    @Test
    fun `部分一致と飛ばし一致は合わせて上限まで、前方一致はすべて返す`() {
        // 「てすと〜ぎんこう」は部分一致、「ぎんこう〜ばん」は前方一致。語の区切りが入らないよう、形態素解析器を使わない
        val names = (1..30).map { "てすと${'か' + it}ぎんこう" } + (1..25).map { "ぎんこう${it}ばん" }
        val index = indexOf(names, SearchKeyGenerator(NoReadingAnalyzer, LatinWordReader(TestAnalyzers.brands)))
        val results = SearchEngine(index).search(query("ぎんこう"))
        val byStage = results.groupingBy { it.reason.stage }.eachCount()
        assertEquals(mapOf(MatchStage.PREFIX to 25, MatchStage.SUBSTRING to MatchingConfig.DEFAULT.maxWeakResults), byStage)

        val unlimited = SearchEngine(index, config = MatchingConfig(maxWeakResults = 100)).search(query("ぎんこう"))
        assertEquals(55, unlimited.size)
    }

    @Test
    fun `同じ点なら表示名の順で、索引の並びに依らず毎回同じ順になる`() {
        val names = listOf("やふーC", "やふーA", "やふーB", "ヤフーD")
        val expected = engineOf(*names.toTypedArray()).search(query("やふ")).map { it.label }
        assertEquals(listOf("やふーA", "やふーB", "やふーC", "ヤフーD"), expected)
        repeat(5) { seed ->
            val shuffled = names.shuffled(kotlin.random.Random(seed))
            assertEquals(expected, engineOf(*shuffled.toTypedArray()).search(query("やふ")).map { it.label })
        }
    }

    @Test
    fun `学習と起動の加点は同じ段階の中の順位だけを変える`() {
        val names = listOf("のりかえ港", "のりかえ駅", "のりかえ")
        assertEquals(listOf("のりかえ", "のりかえ港", "のりかえ駅"), engineOf(*names.toTypedArray()).search(query("のりかえ")).map { it.label })
        val boost =
            object : RankingSignals {
                override fun learnedBoost(
                    query: SearchQuery,
                    app: AppId,
                ): Int = if (app.activityName.endsWith("駅")) 1000 else 0

                override fun usageBoost(app: AppId): Int = if (app.activityName.endsWith("駅")) 1 else 0
            }
        val results = SearchEngine(indexOf(names), signals = boost).search(query("のりかえ"))
        // 完全一致（段階 1）の「のりかえ」は、加点された前方一致（段階 2）より上のまま
        assertEquals(listOf("のりかえ", "のりかえ駅", "のりかえ港"), results.map { it.label })
        assertEquals(results[2].score + 1001, results[1].score)
    }

    @Test
    fun `種類の重みは同じ段階の中で効く`() {
        // 「map」: 英語名（別の言語の名前）の前方一致と、パッケージ名の語の前方一致。既定の重みでは名前の側が上
        val apps =
            listOf(
                AppEntry(AppId("com.example.maps", "a", 0), "地図帳"),
                AppEntry(AppId("org.example.atlas", "b", 0), "アトラス", listOf("Maps")),
            )
        val index = SearchIndex.build(apps, TestAnalyzers.generator)
        assertEquals(listOf("アトラス", "地図帳"), SearchEngine(index).search(query("map")).map { it.label })
        val packageFirst =
            MatchingConfig(kindWeights = MatchingConfig.DEFAULT_KIND_WEIGHTS + (SearchKeyKind.PACKAGE_NAME to 100))
        assertEquals(listOf("地図帳", "アトラス"), SearchEngine(index, config = packageFirst).search(query("map")).map { it.label })
    }

    @Test
    fun `パッケージ名の語の完全一致は前方一致として名前の前方一致と並ぶ`() {
        // google: Files はパッケージ名の語 google に等しいが、名前が Google で始まるアプリより上に出さない
        val apps =
            listOf(
                AppEntry(AppId("com.google.android.apps.nbu.files", "a", 0), "Files"),
                AppEntry(AppId("com.google.android.apps.chromecast.app", "a", 0), "Google Home"),
            )
        val results = SearchEngine(SearchIndex.build(apps, TestAnalyzers.generator)).search(query("google"))
        assertEquals(listOf("Google Home", "Files"), results.map { it.label })
        assertEquals(MatchStage.PREFIX to SearchKeyKind.PACKAGE_NAME, results[1].reason.stage to results[1].reason.keyKind)
    }

    @Test
    fun `多くのアプリに共通するパッケージ名の語は名前で当たるアプリの後の提供元の段に並ぶ`() {
        // music を持つアプリが COMMON_PACKAGE_WORD_MIN_APPS 個あると、music は提供元の段で当たる（M6-01。M1-06b では照合から外した）
        fun appsWithMusic(n: Int) = (1..n).map { AppEntry(AppId("com.vendor$it.music", "a", 0), "おと$it") }
        val few = SearchEngine(SearchIndex.build(appsWithMusic(COMMON_PACKAGE_WORD_MIN_APPS - 1), TestAnalyzers.generator))
        assertEquals(setOf(MatchStage.PREFIX), few.search(query("music")).map { it.reason.stage }.toSet())
        val apps = appsWithMusic(COMMON_PACKAGE_WORD_MIN_APPS) + AppEntry(AppId("org.example.player", "a", 0), "Music Player")
        val many = SearchEngine(SearchIndex.build(apps, TestAnalyzers.generator)).search(query("music"))
        assertEquals("Music Player", many.first().label)
        assertEquals(List(COMMON_PACKAGE_WORD_MIN_APPS) { MatchStage.PROVIDER }, many.drop(1).map { it.reason.stage })
        // 部分一致（語の途中）では当たらない
        assertEquals(
            listOf("Music Player"),
            SearchEngine(SearchIndex.build(apps, TestAnalyzers.generator)).search(query("usic")).map { it.label },
        )
    }

    @Test
    fun `1 字の検索語では部分一致を試さない`() {
        val engine = engineOf("あいう", "かあさん")
        assertEquals(listOf("あいう"), engine.search(query("あ")).map { it.label })
    }

    @Test
    fun `頭文字・飛ばし一致は 1 字目をキーか語の頭に当てる`() {
        assertTrue(isAnchoredSubsequence("googlemaps", intArrayOf(6), "gmp"))
        assertTrue(isAnchoredSubsequence("googlemaps", intArrayOf(6), "mps"))
        assertFalse(isAnchoredSubsequence("googlemaps", intArrayOf(6), "oms"))
        assertFalse(isAnchoredSubsequence("googlemaps", intArrayOf(6), "gpm"))
        assertFalse(isAnchoredSubsequence("ab", intArrayOf(), ""))
    }

    private fun query(raw: String): SearchQuery =
        SearchQuery.of(raw).let {
            if (it.needsReading) it.withReading(TestAnalyzers.kuromoji) else it
        }

    private companion object {
        /** matching.md の例に出てくるアプリ。 */
        val engine: SearchEngine by lazy {
            val apps =
                listOf(
                    AppEntry(AppId("example.chizu", "a", 0), "地図"),
                    AppEntry(AppId("jp.example.transit", "a", 0), "乗換案内"),
                    AppEntry(AppId("jp.co.jorudan.nrkj", "a", 0), "ジョルダン乗換案内"),
                    AppEntry(AppId("jp.co.yahoo.android.apps.map", "a", 0), "Yahoo!マップ", genres = listOf(GenreId("maps"))),
                    AppEntry(AppId("com.google.android.apps.maps", "a", 0), "Google マップ", listOf("Maps"), listOf(GenreId("maps"))),
                    AppEntry(AppId("com.smbc", "a", 0), "三井住友銀行"),
                    AppEntry(AppId("org.example.gm", "a", 0), "Google Maps"),
                    AppEntry(AppId("jp.co.recruit.mtl.android.hotpepper", "a", 0), "ホットペッパーグルメ"),
                    AppEntry(AppId("jp.or.nhk.nhkplus", "a", 0), "NHKプラス"),
                    AppEntry(AppId("com.disney.disneyplus", "a", 0), "Disney+"),
                )
            SearchEngine(SearchIndex.build(apps, TestAnalyzers.generator))
        }

        fun indexOf(
            names: List<String>,
            generator: SearchKeyGenerator = TestAnalyzers.generator,
        ): SearchIndex = SearchIndex.build(names.map { AppEntry(AppId("x", "x.$it", 0), it) }, generator)

        fun engineOf(vararg names: String): SearchEngine = SearchEngine(indexOf(names.toList()))
    }
}
