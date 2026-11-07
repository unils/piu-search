// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.TextNormalizer
import java.text.Normalizer
import java.util.Locale

/**
 * 署名の証明書の DN から、提供元の語の材料になる組織名（`O=`）を取り出す（M6-01）。
 *
 * Android の端末の外で決めたい（JVM でテストする）ので、`javax.naming`（Android にない）や `X500Principal` に頼らず自前で読む。
 */
public object PublisherWords {
    /**
     * DN [distinguishedName] の組織名。提供元を表さない DN（[isNonPublisher]）なら空。
     *
     * 受ける形は 2 つ: RFC 2253（Android の `X500Principal.getName()`。`O=Rakuten Securities\, Inc.,C=JP`）と、
     * apksigner と `X500Principal.toString()` の形（`O="Rakuten Securities, Inc.", C=JP`）。
     */
    public fun organizations(distinguishedName: String): List<String> {
        val attributes = parseDistinguishedName(distinguishedName)
        if (isNonPublisher(attributes)) return emptyList()
        return attributes
            .filter { it.first == "O" }
            .map { it.second.trim() }
            .filterNot { TextNormalizer.strict(it).value in PLACEHOLDER_ORGANIZATIONS }
            .filter { it.isNotEmpty() }
    }

    /**
     * 組織名の英字の語 [word]（厳密形）が提供元を表すか。法人の種類の語（`inc`・`ltd` など）・1 字の語・数字だけの語は表さない。
     *
     * `Rakuten Securities, Inc.` の語のうち `rakuten`・`securities` が残る。語に分けるのは [SearchKeyGenerator]（かな・漢字に読みを付けるため）。
     */
    public fun isPublisherWord(word: String): Boolean = word.length >= MIN_WORD_LENGTH && word !in LEGAL_WORDS && !word.all { it.isDigit() }

    /** 日本語の法人の種類の語（「株式会社」など）か。 */
    public fun isJapaneseLegalWord(word: String): Boolean = TextNormalizer.strict(word).value in JAPANESE_LEGAL_WORDS

    /**
     * 提供元を表さないと確かめた DN か（2026-11-06 に実物の証明書で確かめた）。
     *
     * - F-Droid が署名するアプリの鍵: `CN=FDroid, OU=FDroid, O=fdroid.org, L=ORG, ST=ORG, C=UK`（f-droid.org の Editor・DAVx⁵ の APK）
     * - Google の鍵: `CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US`。Google のアプリはパッケージ名に
     *   `google` を持つので、組織名がなくても困らない。Play App Signing で Google が作った鍵もこの DN だとする記述があるが未確認で、
     *   そうなら Google 以外のアプリが「google」で出てしまうので外す
     * - Android の試験用の鍵と debug の鍵は組織名が `Android`（[PLACEHOLDER_ORGANIZATIONS] で外れる）
     */
    internal fun isNonPublisher(attributes: List<Pair<String, String>>): Boolean {
        val organization =
            attributes
                .firstOrNull { it.first == "O" }
                ?.second
                ?.trim()
                ?.lowercase(Locale.ROOT) ?: return false
        val commonName = attributes.firstOrNull { it.first == "CN" }?.second?.trim()
        val unit = attributes.firstOrNull { it.first == "OU" }?.second?.trim()
        return organization == FDROID_ORGANIZATION ||
            (organization == GOOGLE_ORGANIZATION && commonName == ANDROID && unit == ANDROID)
    }

    private const val MIN_WORD_LENGTH = 2
    private const val FDROID_ORGANIZATION = "fdroid.org"
    private const val GOOGLE_ORGANIZATION = "google inc."
    private const val ANDROID = "Android"

    /**
     * 組織名の欄に置かれがちな、提供元を表さない値（厳密形）。
     *
     * `Unknown` は keytool の既定の値（F-Droid の本体の鍵も `O=Unknown`）、`Android` は Android の試験用の鍵と debug の鍵
     * （`O=Android, CN=Android Debug`）。ほかは鍵を作るときに欄を埋めただけの語。
     */
    private val PLACEHOLDER_ORGANIZATIONS: Set<String> =
        setOf(
            "unknown",
            "android",
            "none",
            "na",
            "null",
            "test",
            "testing",
            "debug",
            "company",
            "mycompany",
            "organization",
            "organisation",
            "org",
            "personal",
            "private",
            "individual",
            "home",
            "self",
            "default",
            "example",
            "developer",
        )

    /** 法人の種類と、組織名をつなぐだけの語。会社を区別しない。 */
    private val LEGAL_WORDS: Set<String> =
        setOf(
            "inc",
            "incorporated",
            "ltd",
            "limited",
            "llc",
            "llp",
            "lp",
            "plc",
            "corp",
            "corporation",
            "co",
            "company",
            "gmbh",
            "ag",
            "kg",
            "sa",
            "sas",
            "sarl",
            "srl",
            "spa",
            "bv",
            "nv",
            "ab",
            "oy",
            "oyj",
            "kk",
            "gk",
            "pte",
            "pty",
            "pvt",
            "sdn",
            "bhd",
            "ltda",
            "group",
            "holdings",
            "the",
            "and",
            "of",
        )

    private val JAPANESE_LEGAL_WORDS: Set<String> =
        setOf("株式会社", "有限会社", "合同会社", "合資会社", "合名会社", "株", "有", "一般社団法人", "社団法人", "財団法人", "一般財団法人")
}

/**
 * DN を属性の型（大文字）と値の組に分ける。
 *
 * 区切りは `,` と `;`（RFC 1779）と、複数の値の `+`。値の `"…"` の中の区切りと、`\` で逃がした字は値の一部にする。
 * `#` で始まる値（RFC 2253 の 16 進の符号化）は読まない。
 */
internal fun parseDistinguishedName(dn: String): List<Pair<String, String>> {
    val result = ArrayList<Pair<String, String>>()
    val text = Normalizer.normalize(dn, Normalizer.Form.NFC)
    var i = 0
    while (i < text.length) {
        val eq = text.indexOf('=', i)
        if (eq < 0) break
        val type = text.substring(i, eq).trim().uppercase(Locale.ROOT)
        val value = StringBuilder()
        var j = eq + 1
        while (j < text.length && text[j] == ' ') j++
        var quoted = false
        while (j < text.length) {
            val ch = text[j]
            when {
                ch == '\\' && j + 1 < text.length -> {
                    value.append(text[j + 1])
                    j += 2
                    continue
                }

                ch == '"' -> {
                    quoted = !quoted
                }

                !quoted && (ch == ',' || ch == ';' || ch == '+') -> {
                    break
                }

                else -> {
                    value.append(ch)
                }
            }
            j++
        }
        val raw = value.toString().trim()
        if (type.isNotEmpty() && !raw.startsWith("#")) result.add(type to raw)
        i = j + 1
    }
    return result
}
