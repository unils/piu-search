// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.GenreVocabulary
import jp.piu.search.core.search.KeyForm
import jp.piu.search.core.search.MatchReason
import jp.piu.search.core.search.MatchStage
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// 出た理由の文（ui.md）。日本語の端末で、種類ごとの文と、ジャンルの名前の漏れがないことを確かめる
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja-rJP")
class MatchReasonTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun eachKindHasItsSentence() {
        assertNull(describe(SearchKeyKind.NAME, "乗換案内"))
        assertEquals("読み: のりかえあんない", describe(SearchKeyKind.READING, "のりかえあんない"))
        assertEquals("ローマ字: norikaeannai", describe(SearchKeyKind.ROMAJI, "norikaeannai"))
        assertEquals("別名: maps", describe(SearchKeyKind.LOCALIZED_NAME, "maps"))
        assertEquals("パッケージ名: jorudan", describe(SearchKeyKind.PACKAGE_NAME, "jorudan"))
        assertEquals("提供元: rakuten", describe(SearchKeyKind.PUBLISHER, "rakuten"))
        assertEquals("学習: おかね", describe(SearchKeyKind.LEARNED, "おかね"))
        assertEquals("ジャンル: 地図", describe(SearchKeyKind.GENRE, "ちず", GenreId("maps")))
        // 名前のないジャンルは、当たった語を出す
        assertEquals("ジャンル: ふね", describe(SearchKeyKind.GENRE, "ふね", GenreId("ships")))
    }

    @Test
    fun everyGenreOfTheVocabularyHasAName() {
        for (genre in GenreVocabulary.DEFAULT.genres) {
            assertNotNull("ジャンル ${genre.value} の名前がない", genreNameRes(genre))
        }
    }

    private fun describe(
        kind: SearchKeyKind,
        text: String,
        genre: GenreId? = null,
    ): String? = describeReason(MatchReason(MatchStage.PREFIX, kind, KeyForm.STRICT, NormalizedText(text), genre), resources)
}
