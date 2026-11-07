// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.RomajiConverter
import jp.piu.search.core.text.TextNormalizer

/**
 * 検索キーの種類。どの手がかりから作ったキーかを示し、順位の重みを変えるのに使う。
 *
 * 種類は architecture.md の `search_key` の表に合わせる。種類ごとに、検索語のどの形と比べるかが決まる:
 * [ROMAJI] は検索語の標準形のローマ字と、[GENRE] はジャンルの語彙を通して比べ、残りは検索語の正規化した形（かな・英字）と比べる。
 */
public enum class SearchKeyKind {
    /** アプリの表示名。[TextNormalizer.strict] の形（`yahoo天気`）。 */
    NAME,

    /**
     * 表示名か別の言語の名前の読み（ひらがな。`やふーてんき`）。
     *
     * 語ごとの読みをつないだもの。かなと漢字の語は形態素解析器、英字の語はブランド表と略語の規則で読む。
     * 読みのない語（表にない英字の語・数字・辞書にない漢字）は、その語の表記のまま入る。
     */
    READING,

    /**
     * 標準形のローマ字（[RomajiConverter.toStandard] の形）。2 通りから作る:
     *
     * - 読み（[READING]）をローマ字にしたもの（`yahutenki`）
     * - 英字の語を綴りのまま残し、それ以外の語を読みにした形をローマ字にしたもの（`yahootenki`・`nhkpurasu`）。
     *   英字の名前の綴りで打つ人のため
     */
    ROMAJI,

    /** 別の言語での表示名。[NAME] と同じ形で、表示名と違うときだけ作る。 */
    LOCALIZED_NAME,

    /**
     * パッケージ名の語（`hotpepper`）と、その読み（ブランド表。`yahoo` → `やふー`）。ありふれた語（`com`・`android` など）は除く。
     *
     * 読みと、索引の [COMMON_PACKAGE_WORD_MIN_APPS] 個以上のアプリが持つ語（会社の名前）は、提供元と同じいちばん下の段（[MatchStage.PROVIDER]）に並べる。
     */
    PACKAGE_NAME,

    /**
     * 提供元: 署名の証明書の組織名（`O=`）の語（`Rakuten Securities, Inc.` → `rakuten`・`securities`）と、その読み（ブランド表）。
     *
     * 法人の種類の語（`inc` など）と、提供元を表さない組織名（F-Droid の鍵・`Unknown` など。[PublisherWords]）は除く。
     * 照合では、名前で当たるアプリの後のいちばん下の段（[MatchStage.PROVIDER]）に並べる。
     */
    PUBLISHER,

    /** ブランド表から引いた名前。今は作らない（ブランド表の読みは [READING] に入る）。表の定義に合わせて残している。 */
    BRAND,

    /** アプリが属するジャンルの識別子（[GenreId.value]）。検索語の文字とは直に比べない。 */
    GENRE,

    /**
     * 学習した検索語（learning.md の R2 の救済で記録した語。厳密形）。
     *
     * キーの生成では作らず、索引に学習を重ねるとき（[SearchIndex.withLearning]）に足す。
     * 段階 1〜3（完全一致・前方一致）にだけ加わり、部分一致と飛ばし一致には使わない（matching.md の「照合の段階と順位」）。
     * かなの語は、英字を含む検索語のために標準形のローマ字の形でも比べる（索引が作る。出た理由はかなの語のまま）。
     */
    LEARNED,
}

/** キーの形: 厳密形か緩い形か。 */
public enum class KeyForm {
    /** 厳密形。[TextNormalizer.strict] か、標準形のローマ字。 */
    STRICT,

    /**
     * 緩い形。厳密形に [TextNormalizer.loose]（ローマ字のキーは [RomajiConverter.loose]）をかけたもの。
     *
     * 検索語の緩い形と比べ、一致は同じ段階の厳密形の一致の直後に並べる。厳密形と同じになるときは作らない。
     */
    LOOSE,
}

/**
 * アプリ 1 つに付く、照合に使う語 1 つ。
 *
 * 手がかりの種類が違っても同じ形で並べ、照合の部分を手がかりの追加から切り離す。
 * 照合の中で割り当てや変換をしなくて済むよう、緩い形と語の区切りも作るときに求めておく。
 */
public data class SearchKey(
    /** 正規化した語。 */
    val text: NormalizedText,
    /** どの手がかりから作ったか。 */
    val kind: SearchKeyKind,
    /**
     * 2 語目以降の語が [text] の中で始まる位置（UTF-16 の単位。昇順。0 と [text] の長さは含まない）。
     *
     * 「語の頭からの一致」（段階 3）に使う。`じょるだんのりかえあんない` なら `[5, 9]`（のりかえ・あんない の頭）。
     * 語は形態素解析器の語・英字の語（空白と記号で分ける）・数字の語。空白や記号は [text] から除かれ、区切りだけが残る。
     * パッケージ名とジャンルのキーは 1 語なので空。
     */
    val wordStarts: List<Int> = emptyList(),
    /** 厳密形か緩い形か。 */
    val form: KeyForm = KeyForm.STRICT,
)
