// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 提供元で引く（M6-01）。実在の 10 個のアプリ（research/package-names.md の表。2026-11-06 に APK から読んだ）で確かめる。
 *
 * 実機の試験で「楽天」「rakuten」で iSPEED が、「やふー」で Y!乗換案内が出なかったのを再現する。
 */
class ProviderSearchTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["楽天", "らくてん", "rakuten"])
    fun `会社の名前で楽天のアプリが出て、提供元だけで当たるアプリは下の段に並ぶ`(raw: String) {
        val results = engine.search(query(raw))
        val labels = results.map { it.label }
        assertEquals(listOf("楽天市場", "楽天銀行"), labels.take(2).sorted(), "結果: $labels")
        assertEquals(setOf("iSPEED", "iSPEED FX"), labels.drop(2).toSet(), "結果: $labels")
        results.drop(2).forEach { assertEquals(MatchStage.PROVIDER, it.reason.stage, it.toString()) }
        assertNameMatchesFirst(results)
        // iSPEED はパッケージ名の語、iSPEED FX は証明書の組織名で当たる
        assertEquals(SearchKeyKind.PACKAGE_NAME, results.single { it.label == "iSPEED" }.reason.keyKind)
        assertEquals(SearchKeyKind.PUBLISHER, results.single { it.label == "iSPEED FX" }.reason.keyKind)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["やふー", "yahoo", "ヤフー"])
    fun `やふーで Y! の 6 個が出て、ebookjapan は下の段に並ぶ`(raw: String) {
        val results = engine.search(query(raw))
        val labels = results.map { it.label }
        val yahooNamed = setOf("Y!マップ", "Y!乗換案内", "Y!フリマ", "Y!オークション", "Y!ショッピング")
        assertEquals(yahooNamed, labels.take(5).toSet(), "結果: $labels")
        assertEquals(listOf("ebookjapan"), labels.drop(5), "結果: $labels")
        assertEquals(MatchStage.PROVIDER, results.last().reason.stage)
        assertNameMatchesFirst(results)
    }

    @Test
    fun `Y! を やふー と読む`() {
        val results = engine.search(query("やふーのりかえ"))
        assertEquals(listOf("Y!乗換案内"), results.map { it.label })
        assertEquals(MatchStage.PREFIX to SearchKeyKind.READING, results.single().reason.let { it.stage to it.keyKind })
        // 綴りで打つ人のため yahoo の綴りでも、Y の 1 字の読み（わい）でも引ける
        assertEquals(listOf("Y!乗換案内"), engine.search(query("yahoonorikae")).map { it.label })
        assertEquals(listOf("Y!乗換案内"), engine.search(query("わいのりかえ")).map { it.label })
    }

    @Test
    fun `乗換案内の語は名前で当たり、提供元の段は出ない`() {
        val results = engine.search(query("のりかえ"))
        assertEquals(listOf("Y!乗換案内"), results.map { it.label })
    }

    @Test
    fun `提供元の語の頭だけでも引けるが、短すぎる語では引かない`() {
        // rakut（英字 5 字）は前方一致で iSPEED FX に当たる
        assertTrue("iSPEED FX" in engine.search(query("rakut")).map { it.label })
        // ra（英字 2 字）は、提供元の段の前方一致には短い
        assertTrue("iSPEED FX" !in engine.search(query("ra")).map { it.label })
    }

    @Test
    fun `提供元の段は部分一致の上限に数えず、上限で切られない`() {
        // 部分一致の上限を 0 にしても、提供元の段は残る
        val engine = SearchEngine(index, config = MatchingConfig(maxWeakResults = 0))
        assertTrue("iSPEED FX" in engine.search(query("rakuten")).map { it.label })
    }

    /** 名前（表示名・読み・ローマ字）で当たるアプリより、提供元の段のアプリが上に来ない。 */
    private fun assertNameMatchesFirst(results: List<SearchResult>) {
        val firstProvider = results.indexOfFirst { it.reason.stage == MatchStage.PROVIDER }
        if (firstProvider < 0) return
        assertTrue(results.drop(firstProvider).all { it.reason.stage == MatchStage.PROVIDER }, "結果: $results")
    }

    private fun query(raw: String): SearchQuery =
        SearchQuery.of(raw).let {
            if (it.needsReading) it.withReading(TestAnalyzers.kuromoji) else it
        }

    private companion object {
        const val RAKUTEN_SEC =
            "CN=www.rakuten-sec.co.jp, OU=Information System, O=\"Rakuten Securities, Inc.\", L=Shinagawa-ku, ST=Tokyo, C=JP"
        const val LY = "O=LY Corporation, ST=Tokyo, C=JP"

        // Yahoo 系は鍵を替えていて（v3.1 の lineage）、API 28 以上の端末では前の鍵も読む（SignerReader）
        const val YAHOO_JAPAN = "CN=Yahoo Japan Corporation, OU=Operation, O=Yahoo Japan Corporation, L=Minato-ku, ST=Tokyo, C=JP"

        /**
         * 1 つの会社が複数のアプリを出す実在の例（楽天の系列 4 個と Yahoo の系列 6 個）。パッケージ名・表示名・証明書の DN は
         * Google Play で配られている APK のもの（docs/research/package-names.md）。API 33 以上の端末を模すので、Yahoo 系は前の鍵と今の鍵の 2 つ。
         */
        val apps =
            listOf(
                app("jp.co.mobileit.ispeed_fx", "iSPEED FX", RAKUTEN_SEC),
                app("jp.co.rakuten_sec.ispeed", "iSPEED", RAKUTEN_SEC),
                app(
                    "jp.co.rakuten.android",
                    "楽天市場",
                    "CN=Rakuten, OU=Develop01, O=\"Rakuten,Inc.\", L=Shinagawa-ku, ST=Tokyo, C=JP",
                ),
                app(
                    "jp.co.rakuten_bank.rakutenbank",
                    "楽天銀行",
                    "CN=apps.rakuten-bank.co.jp, OU=APPS, O=\"Rakuten Bank, Ltd.\", L=Shinagawa-ku, ST=Tokyo, C=JP",
                ),
                app("jp.co.yahoo.android.apps.map", "Y!マップ", YAHOO_JAPAN, LY),
                app("jp.co.yahoo.android.apps.transit", "Y!乗換案内", YAHOO_JAPAN, LY),
                app("jp.co.yahoo.android.ebookjapan", "ebookjapan", YAHOO_JAPAN, LY),
                app("jp.co.yahoo.android.paypayfleamarket", "Y!フリマ", YAHOO_JAPAN, LY),
                app("jp.co.yahoo.android.yauction", "Y!オークション", YAHOO_JAPAN, LY),
                app("jp.co.yahoo.android.yshopping", "Y!ショッピング", YAHOO_JAPAN, LY),
            )

        val index: SearchIndex by lazy { SearchIndex.build(apps, TestAnalyzers.generator) }
        val engine: SearchEngine by lazy { SearchEngine(index) }

        fun app(
            packageName: String,
            label: String,
            vararg signers: String,
        ) = AppEntry(AppId(packageName, "$packageName.Main", 0), label, signerNames = signers.toList())
    }
}
