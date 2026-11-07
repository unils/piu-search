// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

/** 索引の版。 */
public object SearchIndexVersion {
    /**
     * 今の版。端末に保存した索引の版と違えば、全件を作り直す（architecture.md の「索引の作成と更新」の 4）。
     *
     * 同じアプリから違うキーができる変更をしたら 1 つ上げる: 正規化とローマ字の規則（`core:text`）、
     * 形態素解析器の辞書・区切り方（`core:reading` の [jp.piu.search.core.reading.KuromojiReadingAnalyzer]）、
     * ブランド表（`brands.tsv`）と略語の規則、読みの補い（[ReadingSupplements]）、[SearchKeyGenerator] のキーの作り方。
     * 履歴: 1 = M1-04 の最初の版。2 = 名前の「+」を「ぷらす」と読む（M1-05）。
     * 3 = 評価セットで外れた語をブランド表に足す（M1-06b）。4 = 形態素解析器の読みの補い（[ReadingSupplements]）を足す（M1-06b）。
     * 5 = パッケージ名の語の読み・除く語の広げ・提供元のキー・「Y!」の読み（M6-01）。
     *
     * ブランド表と読みの補いを変えて上げ忘れないよう、表の中身と版の組をテスト（`SearchIndexVersionTest`）で固定している。
     */
    public const val CURRENT: Int = 5
}
