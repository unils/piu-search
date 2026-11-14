// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.RomajiConverter
import jp.piu.search.core.text.TextNormalizer

/**
 * ジャンルの語彙: ジャンルの id → そのジャンルを指す語（matching.md の「intent filter とカテゴリによるジャンル」の表）。
 *
 * 検索語がジャンルの語に前方一致したら、そのジャンルに属するアプリを段階 4 で出す。
 * 語は作るときに正規化し、緩い形と標準形のローマ字も求めておく（照合の中で変換しないため）。
 * ローマ字の経路があるので、`chizu`・`tenki` でも地図・天気のジャンルが引ける。
 *
 * @param words ジャンルの id → 語の一覧。語は生の表記でよい（カタカナ・全角を含む）
 */
public class GenreVocabulary(
    words: Map<GenreId, List<String>>,
) {
    private val entries: List<GenreWord> =
        words.flatMap { (id, list) ->
            list
                .map { TextNormalizer.strict(it) }
                .filterNot { it.isEmpty }
                .distinct()
                .map { strict ->
                    val romaji = RomajiConverter.toStandard(strict).value
                    GenreWord(
                        id = id,
                        strict = strict.value,
                        loose = TextNormalizer.loose(strict).value,
                        // 漢字の語（地図）はローマ字にならず字が残るので、ローマ字の経路では比べない
                        romaji = romaji.takeIf { it.isRomajiOnly() },
                        romajiLoose = romaji.takeIf { it.isRomajiOnly() }?.let { RomajiConverter.loose(NormalizedText(it)).value },
                    )
                }
        }

    /** 語彙にあるジャンルの id。 */
    public val genres: Set<GenreId> = words.keys

    /** [query] が語に前方一致するジャンルの id。字数が足りない検索語（[SearchQuery.isLongEnoughForGenre]）には空を返す。 */
    public fun matchingGenres(query: SearchQuery): Set<GenreId> = match(query).mapTo(LinkedHashSet()) { it.genre }

    /**
     * [query] が前方一致するジャンルと、一致の形。同じジャンルに複数の語が当たれば、厳密形の一致を残す。
     */
    internal fun match(query: SearchQuery): List<GenreHit> {
        if (!query.isLongEnoughForGenre) return emptyList()
        val hits = LinkedHashMap<GenreId, GenreHit>()
        for (forms in query.formSets) {
            for (word in entries) {
                val form = word.matchForm(forms) ?: continue
                val previous = hits[word.id]
                if (previous == null || form < previous.form) hits[word.id] = GenreHit(word.id, form, NormalizedText(word.strict))
            }
        }
        return hits.values.toList()
    }

    /** 既定の語彙。 */
    public companion object {
        /**
         * matching.md の表の語。表にある漢字の語には、かなで打つ人のために読みも足した（計算 → けいさん、予定 → よてい）。
         * カタカナと全角は正規化でひらがなと半角にそろうので、表の重なり（ちず・地図・ナビ・なび）はそのまま書いている。
         * 英語の語のかなの形は、端末の名前に使われて評価セットで効いたもの（ふぉと。Google フォトの「フォト」）だけを足した（M1-06b）。
         */
        public val DEFAULT: GenreVocabulary =
            GenreVocabulary(
                linkedMapOf(
                    GenreId("maps") to listOf("ちず", "地図", "まっぷ", "map", "なび", "ナビ"),
                    GenreId("email") to listOf("めーる", "メール", "mail"),
                    GenreId("phone") to listOf("でんわ", "電話", "tel"),
                    GenreId("messaging") to listOf("めっせーじ", "SMS"),
                    GenreId("camera") to listOf("かめら", "カメラ", "camera"),
                    GenreId("photos") to listOf("しゃしん", "写真", "ぎゃらりー", "photo", "ふぉと"),
                    GenreId("video") to listOf("どうが", "動画", "video"),
                    GenreId("music") to listOf("おんがく", "音楽", "music"),
                    GenreId("browser") to listOf("ぶらうざ", "ブラウザ", "browser"),
                    GenreId("calendar") to listOf("かれんだー", "予定", "よてい", "calendar"),
                    GenreId("contacts") to listOf("れんらくさき", "連絡先", "contacts"),
                    GenreId("calculator") to listOf("でんたく", "電卓", "計算", "けいさん", "calculator"),
                    GenreId("clock") to listOf("とけい", "時計", "あらーむ", "clock"),
                    GenreId("files") to listOf("ふぁいる", "ファイル", "files"),
                    GenreId("weather") to listOf("てんき", "天気", "weather"),
                    GenreId("translate") to listOf("ほんやく", "翻訳", "translate"),
                    GenreId("store") to listOf("すとあ", "ストア", "store"),
                    GenreId("pdf") to listOf("pdf"),
                    GenreId("game") to listOf("げーむ", "ゲーム", "game"),
                    GenreId("news") to listOf("にゅーす", "ニュース", "news"),
                    GenreId("social") to listOf("えすえぬえす", "SNS"),
                ),
            )
    }
}

/** ジャンルの語に検索語が当たったこと。[word] は当たった語の厳密形（出た理由に使う）。 */
internal class GenreHit(
    val genre: GenreId,
    val form: KeyForm,
    val word: NormalizedText,
)

private class GenreWord(
    val id: GenreId,
    val strict: String,
    val loose: String,
    val romaji: String?,
    val romajiLoose: String?,
) {
    fun matchForm(forms: QueryForms): KeyForm? {
        if (strict.startsWith(forms.strict)) return KeyForm.STRICT
        val romajiPrefix = forms.romajiPrefix
        if (romaji != null && romajiPrefix != null && romaji.startsWith(romajiPrefix)) {
            return if (forms.romajiIsStrict) KeyForm.STRICT else KeyForm.LOOSE
        }
        if (forms.loose.isNotEmpty() && loose.startsWith(forms.loose)) return KeyForm.LOOSE
        val romajiLoosePrefix = forms.romajiLoose
        if (romajiLoose != null && romajiLoosePrefix != null && romajiLoose.startsWith(romajiLoosePrefix)) return KeyForm.LOOSE
        return null
    }
}

/** 英小文字と数字だけか。ローマ字の経路で比べてよい文字列かを決める。 */
internal fun String.isRomajiOnly(): Boolean = isNotEmpty() && all { it in 'a'..'z' || it in '0'..'9' }
