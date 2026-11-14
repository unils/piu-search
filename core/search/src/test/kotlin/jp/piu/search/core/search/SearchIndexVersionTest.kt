// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class SearchIndexVersionTest {
    /**
     * ブランド表の中身（綴りと読み。メモとコメントは除く）・読みの補い（[ReadingSupplements]）と索引の版の組を固定する。
     *
     * 表を変えると端末の索引のキーが古くなる。版を上げ忘れると、更新した人の端末では作り直されない。
     * 失敗したら [SearchIndexVersion.CURRENT] を 1 つ上げ、ここの版とハッシュを新しい値にする。
     */
    @Test
    fun `ブランド表を変えたら索引の版を上げる`() {
        assertEquals(
            EXPECTED_VERSION to EXPECTED_BRANDS_SHA256,
            SearchIndexVersion.CURRENT to brandTableDigest(),
            "brands.tsv の綴りか読みが変わった。SearchIndexVersion.CURRENT を上げ、このテストの版とハッシュを直す",
        )
    }

    @Test
    fun `読みの補いを変えたら索引の版を上げる`() {
        val content =
            ReadingSupplements.ENTRIES.entries.joinToString(
                "\n",
            ) { (surface, readings) -> "$surface\t${readings.joinToString(",")}" }
        assertEquals(
            EXPECTED_VERSION to EXPECTED_SUPPLEMENTS_SHA256,
            SearchIndexVersion.CURRENT to sha256(content),
            "ReadingSupplements の中身が変わった。SearchIndexVersion.CURRENT を上げ、このテストの版とハッシュを直す",
        )
    }

    private fun brandTableDigest(): String {
        val stream = checkNotNull(BrandTable::class.java.getResourceAsStream(BrandTable.RESOURCE_NAME))
        val content =
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .joinToString("\n") { it.split('\t').take(2).joinToString("\t") }
            }
        return sha256(content)
    }

    private fun sha256(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val EXPECTED_VERSION = 7
        const val EXPECTED_SUPPLEMENTS_SHA256 = "2d7b06b63f442bb74d688c821e379faae9856d1cb805cbd769277448a4105f71"
        const val EXPECTED_BRANDS_SHA256 = "7371adadf67cf87dc7d44b3bb67a4f4b83741114a27495c64b8559806074a15a"
    }
}
