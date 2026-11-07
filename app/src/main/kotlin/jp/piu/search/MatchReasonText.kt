// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.res.Resources
import androidx.annotation.StringRes
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.MatchReason
import jp.piu.search.core.search.SearchKeyKind

/**
 * 出た理由の文（ui.md の「検索画面」）。「ジャンル: 地図」「読み: のりかえあんない」のように、当たった手がかりの種類と中身を出す。
 * 名前で一致したときは null（出さない）。
 *
 * 種類の分岐を網羅にしてあるので、種類を足すと、ここで文を決めるまでコンパイルが通らない。
 */
fun describeReason(
    reason: MatchReason,
    resources: Resources,
): String? {
    val text = reason.matchedText.value
    return when (reason.keyKind) {
        SearchKeyKind.NAME -> null
        SearchKeyKind.LOCALIZED_NAME -> resources.getString(R.string.reason_localized_name, text)
        SearchKeyKind.READING, SearchKeyKind.BRAND -> resources.getString(R.string.reason_reading, text)
        SearchKeyKind.ROMAJI -> resources.getString(R.string.reason_romaji, text)
        SearchKeyKind.PACKAGE_NAME -> resources.getString(R.string.reason_package_name, text)
        SearchKeyKind.PUBLISHER -> resources.getString(R.string.reason_publisher, text)
        SearchKeyKind.GENRE -> resources.getString(R.string.reason_genre, genreName(reason.genre, text, resources))
        SearchKeyKind.LEARNED -> resources.getString(R.string.reason_learned, text)
    }
}

/** ジャンルの画面の名前。名前のないジャンル（語彙に足して文字列を足し忘れた）は、当たった語をそのまま出す。 */
private fun genreName(
    genre: GenreId?,
    fallback: String,
    resources: Resources,
): String = genre?.let(::genreNameRes)?.let(resources::getString) ?: fallback

/** ジャンルの id（`GenreVocabulary.DEFAULT`）→ 画面の名前。テストで、語彙のすべてのジャンルにあることを確かめる。 */
@StringRes
internal fun genreNameRes(genre: GenreId): Int? = GENRE_NAMES[genre.value]

private val GENRE_NAMES: Map<String, Int> =
    mapOf(
        "maps" to R.string.genre_maps,
        "email" to R.string.genre_email,
        "phone" to R.string.genre_phone,
        "messaging" to R.string.genre_messaging,
        "camera" to R.string.genre_camera,
        "photos" to R.string.genre_photos,
        "video" to R.string.genre_video,
        "music" to R.string.genre_music,
        "browser" to R.string.genre_browser,
        "calendar" to R.string.genre_calendar,
        "contacts" to R.string.genre_contacts,
        "calculator" to R.string.genre_calculator,
        "clock" to R.string.genre_clock,
        "files" to R.string.genre_files,
        "weather" to R.string.genre_weather,
        "translate" to R.string.genre_translate,
        "store" to R.string.genre_store,
        "pdf" to R.string.genre_pdf,
        "game" to R.string.genre_game,
        "news" to R.string.genre_news,
        "social" to R.string.genre_social,
    )
