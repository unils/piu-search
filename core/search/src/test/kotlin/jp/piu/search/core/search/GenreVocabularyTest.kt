// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class GenreVocabularyTest {
    private val vocabulary = GenreVocabulary.DEFAULT

    @Test
    fun `語彙のジャンルは評価セットと設計のジャンルの id と同じ`() {
        assertEquals(EvalSet.GENRE_IDS, vocabulary.genres.map { it.value }.toSet())
    }

    /** matching.md の段階 4: かな 2 字以上、英字 3 字以上で、ジャンルの語に前方一致する。ローマ字でも引ける。空の列は当たらない。 */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
ちず     | maps
チズ     | maps
地図     | maps
ち       |
map      | maps
ma       |
chizu    | maps
tizu     | maps
chiz     | maps
てんき   | weather
tenki    | weather
てん     | weather
weath    | weather
めーる   | email
める     | email
mail     | email
sms      | messaging
tel      | phone
ふぉと   | photos
フォト   | photos
でんわ   | phone
pdf      | pdf
pd       |
けいさん | calculator
計算     | calculator
よてい   | calendar
にゅーす | news
ゲーム   | game
えすえぬ | social
ぎんこう |
""",
    )
    fun `検索語が前方一致するジャンル`(
        query: String,
        expected: String?,
    ) {
        val genres = vocabulary.matchingGenres(SearchQuery.of(query)).map { it.value }
        assertEquals(listOfNotNull(expected), genres)
    }

    @Test
    fun `同じジャンルの語が厳密形と緩い形で当たれば厳密形を残す`() {
        val custom = GenreVocabulary(mapOf(GenreId("x") to listOf("らーめん", "らめんや")))
        val hit = custom.match(SearchQuery.of("らめ")).single()
        assertEquals(KeyForm.STRICT, hit.form)
        assertEquals("らめんや", hit.word.value)
        val looseOnly = GenreVocabulary(mapOf(GenreId("x") to listOf("らーめん")))
        assertEquals(KeyForm.LOOSE, looseOnly.match(SearchQuery.of("らめん")).single().form)
    }
}
