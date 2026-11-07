// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText

/** 照合の段階（matching.md の「照合の段階と順位」）。上の段階ほど先に並べる。 */
public enum class MatchStage {
    /** 1. 検索語がキーと等しい。 */
    EXACT,

    /** 2. キーが検索語で始まる。 */
    PREFIX,

    /** 3. キーの 2 語目以降の語の頭から、検索語で始まる。 */
    WORD_PREFIX,

    /** 4. 検索語がジャンルの語に前方一致し、アプリがそのジャンルに属する。 */
    GENRE,

    /** 5. キーの途中に検索語がある。 */
    SUBSTRING,

    /** 6. 英字の検索語の字を、英字のキーが順に含む（頭文字・飛ばし一致）。 */
    SUBSEQUENCE,

    /**
     * 7. 提供元: 証明書の組織名（[SearchKeyKind.PUBLISHER]）か、索引の多くのアプリに共通するパッケージ名の語（会社の名前）に、
     * 検索語が等しいか前方一致する。名前で当たるアプリの後に並べる（decisions.md の 2026-11-06）。
     */
    PROVIDER,
}

/**
 * 結果が出た理由（ui.md の「出た理由」）。画面は、名前で一致しなかったときに「読み: のりかえ」「ジャンル: 地図」のように出す。
 *
 * @property stage 一致の段階
 * @property keyKind 当たったキーの種類。ジャンルの一致では [SearchKeyKind.GENRE]
 * @property form 厳密形の一致か、緩い形の一致か
 * @property matchedText 当たったキーの文字列（ジャンルの一致では当たったジャンルの語）
 * @property genre ジャンルの一致のとき、そのジャンル
 * @property viaQueryReading 漢字の検索語に形態素解析器で付けた読み（[SearchQuery.reading]）で当たったか
 */
public data class MatchReason(
    val stage: MatchStage,
    val keyKind: SearchKeyKind,
    val form: KeyForm,
    val matchedText: NormalizedText,
    val genre: GenreId? = null,
    val viaQueryReading: Boolean = false,
)

/**
 * 検索の結果 1 件。
 *
 * @property app アプリの識別子
 * @property label 表示名
 * @property reason 出た理由
 * @property score 同じ段階・同じ形の中で並べる点（キーの種類の重みと、学習・起動の加点の和）
 */
public data class SearchResult(
    val app: AppId,
    val label: String,
    val reason: MatchReason,
    val score: Int,
)
