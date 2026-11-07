// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.text.Normalizer

class LatinWordReaderTest {
    private val reader = LatinWordReader(BrandTable.loadDefault())

    private fun readingsOf(word: String): List<String> = reader.readings(word).map { it.value }

    @Test
    fun `Yahoo!天気 の英字の部分から やふー が引ける`() {
        val words = latinWords("Yahoo!天気")
        assertEquals(listOf("Yahoo"), words)
        assertEquals(listOf("やふー"), readingsOf(words.single()))
    }

    @Test
    fun `NHKプラス の英字の部分から えぬえいちけー が作られる`() {
        val words = latinWords("NHKプラス")
        assertEquals(listOf("NHK"), words)
        assertEquals(listOf("えぬえいちけー"), readingsOf(words.single()))
    }

    @Test
    fun `表にある綴りは大文字でも表の読みを使う`() {
        // 1 字ずつなら じぇーえーえる・ぴーえーわい になる
        assertEquals(listOf("じゃる"), readingsOf("JAL"))
        assertEquals(listOf("ぺい"), readingsOf("PAY"))
        assertEquals(listOf("らいん"), readingsOf("LINE"))
    }

    @Test
    fun `表になく小文字を含む語には読みがない`() {
        assertEquals(emptyList<String>(), readingsOf("Zyxwv"))
        assertEquals(emptyList<String>(), readingsOf("!!"))
        assertEquals(emptyList<String>(), readingsOf(""))
    }

    @Test
    fun `全角の英字も同じに扱う`() {
        assertEquals(listOf("やふー"), readingsOf("Ｙａｈｏｏ"))
        assertEquals(listOf("えぬえいちけー"), readingsOf("ＮＨＫ"))
    }

    @ParameterizedTest
    @CsvSource(
        "NHK, えぬえいちけー",
        "UFJ, ゆーえふじぇー",
        "X, えっくす",
        "AT&T, えーてぃーてぃー",
        "U.S.A., ゆーえすえー",
        "ABCDEFGHIJKLM, えーびーしーでぃーいーえふじーえいちあいじぇーけーえるえむ",
        "NOPQRSTUVWXYZ, えぬおーぴーきゅーあーるえすてぃーゆーぶいだぶりゅーえっくすわいぜっと",
    )
    fun `大文字の略語は 1 字ずつ読む`(
        word: String,
        expected: String,
    ) {
        assertEquals(NormalizedText(expected), AcronymReader.read(word))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Nhk", "nhk", "3D", "B2B", "ÉA", "NHKプラス", "Ａβ", "", "&"])
    fun `大文字の英字だけでない語は略語とみなさない`(word: String) {
        assertNull(AcronymReader.read(word))
    }

    /** 名前の中の英字の語。キーの生成と同じ分け方（[splitNameWords]）で取り出す。 */
    private fun latinWords(name: String): List<String> =
        splitNameWords(
            Normalizer.normalize(name, Normalizer.Form.NFKC),
            emptyList(),
        ).filter { it.kind == WordKind.LATIN }.map { it.surface }
}
