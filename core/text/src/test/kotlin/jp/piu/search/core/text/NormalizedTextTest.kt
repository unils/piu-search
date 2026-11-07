// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NormalizedTextTest {
    @Test
    fun `同じ文字列なら等しい`() {
        assertEquals(NormalizedText("のりかえ"), NormalizedText("のりかえ"))
    }

    @Test
    fun `空かどうか`() {
        assertTrue(NormalizedText("").isEmpty)
        assertFalse(NormalizedText("a").isEmpty)
    }

    @Test
    fun `文字列に戻すと中身がそのまま出る`() {
        assertEquals("やふー", NormalizedText("やふー").toString())
    }
}
