// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublisherWordsTest {
    private val generator = TestAnalyzers.generator

    @Test
    fun `apksigner の形と RFC 2253 の形の DN から組織名を読む`() {
        // apksigner の形（research/package-names.md の iSPEED）
        assertEquals(
            listOf("Rakuten Securities, Inc."),
            PublisherWords.organizations(
                "CN=www.rakuten-sec.co.jp, OU=Information System, O=\"Rakuten Securities, Inc.\", L=Shinagawa-ku, ST=Tokyo, C=JP",
            ),
        )
        // RFC 2253（Android の X500Principal.getName()）は逆順で、, を \ で逃がす
        assertEquals(
            listOf("Rakuten Securities, Inc."),
            PublisherWords.organizations(
                "C=JP,ST=Tokyo,L=Shinagawa-ku,O=Rakuten Securities\\, Inc.,OU=Information System,CN=www.rakuten-sec.co.jp",
            ),
        )
        assertEquals(listOf("Rakuten,Inc."), PublisherWords.organizations("CN=Rakuten, OU=Develop01, O=\"Rakuten,Inc.\", C=JP"))
        // API 37 のエミュレータに入れた楽天市場の APK から SignerReader が読んだ形（2026-11-06）
        assertEquals(
            listOf("Rakuten,Inc."),
            PublisherWords.organizations("CN=Rakuten,OU=Develop01,O=Rakuten\\,Inc.,L=Shinagawa-ku,ST=Tokyo,C=JP"),
        )
        assertEquals(listOf("LY Corporation"), PublisherWords.organizations("O=LY Corporation, ST=Tokyo, C=JP"))
        // 組織名のない DN・16 進の値・壊れた DN
        assertEquals(emptyList<String>(), PublisherWords.organizations("CN=Taro Yamada, C=JP"))
        assertEquals(emptyList<String>(), PublisherWords.organizations("O=#0c0461626364,C=JP"))
        assertEquals(emptyList<String>(), PublisherWords.organizations("garbage"))
        assertEquals(listOf("A", "B"), PublisherWords.organizations("O=A+O=B;C=US"))
    }

    @Test
    fun `提供元を表さない DN は外す`() {
        // 2026-11-06 に実物で確かめた DN: F-Droid の鍵（f-droid.org の APK）、keytool の既定（F-Droid の本体）、
        // debug の鍵（~/.android/debug.keystore）、AOSP の試験用の鍵（build/make/target/product/security/testkey.x509.pem）
        val dns =
            listOf(
                "CN=FDroid, OU=FDroid, O=fdroid.org, L=ORG, ST=ORG, C=UK",
                "CN=Ciaran Gultnieks, OU=Unknown, O=Unknown, L=Wetherby, ST=Unknown, C=UK",
                "C=US, O=Android, CN=Android Debug",
                "EMAILADDRESS=android@android.com, CN=Android, OU=Android, O=Android, L=Mountain View, ST=California, C=US",
                "CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US",
            )
        // AOSP の像の初めからのアプリを SignerReader で読んだ形（RFC 2253。電子メールの属性は 16 進）
        val aosp =
            "1.2.840.113549.1.9.1=#1613616e64726f696440616e64726f69642e636f6d,CN=Android,OU=Android,O=Android,L=Mountain View,ST=California,C=US"
        (dns + aosp).forEach { assertEquals(emptyList<String>(), PublisherWords.organizations(it), it) }
        // Google の名の組織でも、ほかの DN なら残す
        assertEquals(listOf("Google LLC"), PublisherWords.organizations("CN=Google LLC, O=Google LLC, C=US"))
    }

    @Test
    fun `組織名の語から法人の種類の語を除き、読みを足す`() {
        assertEquals(
            listOf("rakuten", "securities", "らくてん"),
            publisherKeys("CN=x, O=\"Rakuten Securities, Inc.\", C=JP"),
        )
        assertEquals(listOf("rakuten", "らくてん"), publisherKeys("O=\"Rakuten,Inc.\""))
        assertEquals(listOf("rakuten", "bank", "らくてん"), publisherKeys("O=\"Rakuten Bank, Ltd.\""))
        assertEquals(listOf("ly"), publisherKeys("O=LY Corporation, ST=Tokyo, C=JP"))
        assertEquals(listOf("google", "ぐーぐる", "ぐぐる"), publisherKeys("O=Google LLC"))
        // かな・漢字の組織名は形態素解析器で分け、「株式会社」を除いて読みを付ける
        assertEquals(listOf("楽天", "らくてん"), publisherKeys("O=楽天株式会社"))
        // 数字だけの語・1 字の語は除く
        assertEquals(listOf("acme"), publisherKeys("O=Acme 2 X Co."))
        // 署名者が 2 人なら両方の語
        val two = generator.generate(entry(listOf("O=Alpha", "O=Beta"))).filter { it.kind == SearchKeyKind.PUBLISHER }
        assertEquals(listOf("alpha", "beta"), two.map { it.text.value })
    }

    private fun publisherKeys(dn: String): List<String> =
        generator.generate(entry(listOf(dn))).filter { it.kind == SearchKeyKind.PUBLISHER }.map { it.text.value }

    private fun entry(signers: List<String>) = AppEntry(AppId("x.y", "a", 0), "なまえ", signerNames = signers)
}
