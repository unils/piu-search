// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.NoReadingAnalyzer
import jp.piu.search.core.text.NormalizedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MatchingDetailsTest {
    @ParameterizedTest
    @ValueSource(strings = ["Qoo10", "Ｑｏｏ１０", "Qoo10 買い物"])
    fun `英字と数字のブランドを表示名だけから読める`(label: String) {
        // パッケージ名や証明書からの読みで欠落を隠さないため、表示名だけを手がかりにする
        for (analyzer in listOf(NoReadingAnalyzer, TestAnalyzers.kuromoji)) {
            val generator = SearchKeyGenerator(analyzer, LatinWordReader(TestAnalyzers.brands))
            val engine = SearchEngine(SearchIndex.build(listOf(app("org.example.shopping", label)), generator))
            val result = engine.search(SearchQuery.of("きゅーてん")).single()
            assertEquals(SearchKeyKind.READING, result.reason.keyKind)
            assertEquals(if (label.endsWith("買い物")) MatchStage.PREFIX else MatchStage.EXACT, result.reason.stage)
            assertEquals(KeyForm.STRICT, result.reason.form)
        }
    }

    @Test
    fun `表にない英数字は区切り、空白をまたいでブランドを作らない`() {
        val apps = listOf(app("org.example.line", "LINE2"), app("org.example.split", "Qoo 10"), app("org.example.suffix", "Qoo10Plus"))
        val engine = SearchEngine(SearchIndex.build(apps, TestAnalyzers.generator))
        assertEquals(listOf("LINE2"), engine.search(SearchQuery.of("らいん")).map { it.label })
        assertTrue(engine.search(SearchQuery.of("きゅーてん")).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["ぐぐる", "ぐーg", "guguru", "gugu", "ぐー", "gug"])
    fun `パッケージ名と証明書の読みを緩い形とローマ字でも提供元の段で引ける`(raw: String) {
        val engine = providerEngine()
        val results = engine.search(SearchQuery.of(raw))
        assertEquals(setOf("包み", "署名"), results.map { it.label }.toSet())
        assertTrue(results.all { it.reason.stage == MatchStage.PROVIDER })
        assertEquals(setOf(SearchKeyKind.PACKAGE_NAME, SearchKeyKind.PUBLISHER), results.map { it.reason.keyKind }.toSet())
        if (raw == "ぐぐる") assertTrue(results.all { it.reason.form == KeyForm.LOOSE })
        if (raw == "ぐーg") assertTrue(results.all { it.reason.form == KeyForm.STRICT })
    }

    @ParameterizedTest
    @ValueSource(strings = ["tuita", "ついた"])
    fun `提供元のローマ字の緩い形でも促音を省いた入力に当たる`(raw: String) {
        val apps =
            listOf(
                app("com.twitter.android", "包み"),
                app("org.example.owner", "署名").copy(signerNames = listOf("O=Twitter Inc.")),
            )
        val results = SearchEngine(SearchIndex.build(apps, TestAnalyzers.generator)).search(SearchQuery.of(raw))
        assertEquals(setOf("包み", "署名"), results.map { it.label }.toSet())
        assertTrue(results.all { it.reason.stage == MatchStage.PROVIDER && it.reason.form == KeyForm.LOOSE })
        assertTrue(results.all { it.reason.matchedText.value == "ついったー" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["ぐ", "gu", "ぐる", "guru", "ggr", "ーー"])
    fun `提供元の緩い形とローマ字でも短い前方一致と部分一致と飛ばし一致を認めない`(raw: String) {
        assertTrue(providerEngine().search(SearchQuery.of(raw)).isEmpty())
    }

    @Test
    fun `短い提供元の語でも完全一致は認め、緩いキーを厳密形扱いしない`() {
        val entry = app("org.example.owner", "署名").copy(signerNames = listOf("O=GO"))
        val engine = SearchEngine(SearchIndex.build(listOf(entry), TestAnalyzers.generator))
        val exact = engine.search(SearchQuery.of("ごー")).single().reason
        assertEquals(MatchStage.PROVIDER to KeyForm.STRICT, exact.stage to exact.form)
        val loose = engine.search(SearchQuery.of("ご")).single().reason
        assertEquals(MatchStage.PROVIDER to KeyForm.LOOSE, loose.stage to loose.form)
    }

    @Test
    fun `提供元の読みのローマ字も短い語は完結した完全一致だけを認める`() {
        val entries =
            listOf(
                app("org.example.owner1", "短い語").copy(signerNames = listOf("O=ぐ")),
                app("org.example.owner2", "入力途中").copy(signerNames = listOf("O=す")),
            )
        val engine = SearchEngine(SearchIndex.build(entries, TestAnalyzers.generator))
        val result = engine.search(SearchQuery.of("gu")).single()
        assertEquals("短い語", result.label)
        assertEquals(MatchStage.PROVIDER, result.reason.stage)
        assertEquals(SearchKeyKind.PUBLISHER, result.reason.keyKind)
        assertEquals("ぐ", result.reason.matchedText.value)
        // sh の前方一致用の形 s を、短い提供元の語への完全一致として使わない
        for (raw in listOf("g", "sh", "s")) {
            assertTrue(engine.search(SearchQuery.of(raw)).isEmpty(), raw)
        }
    }

    @Test
    fun `提供元の緩い形どうしが等しくても厳密形の一致を先に並べる`() {
        val apps =
            listOf(
                app("org.example.loose", "あ").copy(signerNames = listOf("O=Google Inc.")),
                app("org.example.strict", "い").copy(signerNames = listOf("O=ぐぐる")),
            )
        val results = SearchEngine(SearchIndex.build(apps, TestAnalyzers.generator)).search(SearchQuery.of("ぐぐる"))
        assertEquals(listOf("い", "あ"), results.map { it.label })
        assertEquals(listOf(KeyForm.STRICT, KeyForm.LOOSE), results.map { it.reason.form })
    }

    @ParameterizedTest
    @ValueSource(strings = ["ーー", "ー ー", "ｰｰ"])
    fun `長音だけの検索語はジャンルに当たらない`(raw: String) {
        val entries =
            GenreVocabulary.DEFAULT.genres.mapIndexed { i, genre ->
                app("org.example.genre$i", "候補$i").copy(genres = listOf(genre))
            }
        assertTrue(GenreVocabulary.DEFAULT.matchingGenres(SearchQuery.of(raw)).isEmpty())
        assertTrue(SearchEngine(SearchIndex.build(entries, TestAnalyzers.generator)).search(SearchQuery.of(raw)).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["pay", "payー"])
    fun `パッケージ名の語の完全一致は緩い形でも段階2に下げる`(raw: String) {
        val engine = SearchEngine(SearchIndex.build(listOf(app("org.example.pay", "支払い")), TestAnalyzers.generator))
        val reason = engine.search(SearchQuery.of(raw)).single().reason
        assertEquals(MatchStage.PREFIX, reason.stage)
        assertEquals(SearchKeyKind.PACKAGE_NAME, reason.keyKind)
        assertEquals(if (raw == "pay") KeyForm.STRICT else KeyForm.LOOSE, reason.form)
    }

    @Test
    fun `パッケージ名の緩いキーの完全一致も段階2に下げる`() {
        val id = AppId("org.example.pay", "main", 0)
        val key = SearchKey(NormalizedText("pay"), SearchKeyKind.PACKAGE_NAME, form = KeyForm.LOOSE)
        val reason =
            SearchEngine(SearchIndex(listOf(IndexedApp(id, "支払い", listOf(key)))))
                .search(SearchQuery.of("pay"))
                .single()
                .reason
        assertEquals(MatchStage.PREFIX to KeyForm.LOOSE, reason.stage to reason.form)
    }

    private fun providerEngine(): SearchEngine =
        SearchEngine(
            SearchIndex.build(
                listOf(
                    app("com.google.android", "包み"),
                    app("org.example.owner", "署名").copy(signerNames = listOf("O=Google Inc.")),
                ),
                TestAnalyzers.generator,
            ),
        )

    private fun app(
        packageName: String,
        label: String,
    ) = AppEntry(AppId(packageName, "main", 0), label)
}
