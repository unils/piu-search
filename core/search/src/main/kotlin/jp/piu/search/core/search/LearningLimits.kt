// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText

/** 学習の記録と読み込みに共通の上限。長い語で索引の作成が画面のスレッドを占めないようにする。 */
public object LearningLimits {
    /** 学習する検索語の厳密形の長さの上限（コードポイント）。 */
    public const val MAX_QUERY_CODE_POINTS: Int = 64

    /** [query] が空でなく、長さの上限以内か。検索自体の長さは制限しない。 */
    public fun accepts(query: NormalizedText): Boolean =
        !query.isEmpty && query.value.codePointCount(0, query.value.length) <= MAX_QUERY_CODE_POINTS
}
