// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

/**
 * 正規化を済ませた文字列。
 *
 * 検索語と検索キーを同じ規則で正規化してから比べるため、正規化の前後を型で区別する。
 * 生の文字列からは [TextNormalizer.strict] で作る。厳密形・緩い形・標準形のローマ字のどれも、この型で持つ。
 *
 * コンストラクタは中身を確かめない。保存しておいた正規化済みの値を読み戻すときのために公開している。
 */
@JvmInline
public value class NormalizedText(
    /** 正規化の後の文字列。 */
    public val value: String,
) {
    /** 空の文字列か。空の検索語では照合せず、別の表示にするため。 */
    public val isEmpty: Boolean get() = value.isEmpty()

    override fun toString(): String = value
}
