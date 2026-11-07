// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SearchKeyTest {
    @Test
    fun `語と種類が同じなら等しい`() {
        assertEquals(
            SearchKey(NormalizedText("ちず"), SearchKeyKind.READING),
            SearchKey(NormalizedText("ちず"), SearchKeyKind.READING),
        )
    }

    @Test
    fun `同じ語でも種類が違えば別のキー`() {
        assertNotEquals(
            SearchKey(NormalizedText("maps"), SearchKeyKind.NAME),
            SearchKey(NormalizedText("maps"), SearchKeyKind.GENRE),
        )
    }
}
