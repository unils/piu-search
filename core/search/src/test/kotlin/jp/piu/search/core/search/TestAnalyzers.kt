// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.KuromojiReadingAnalyzer

/** テストで使い回す部品。辞書の読み込みは 0.2 秒ほどかかるので、テストのクラスの間で 1 つを共有する。 */
internal object TestAnalyzers {
    val kuromoji: KuromojiReadingAnalyzer by lazy { KuromojiReadingAnalyzer() }
    val brands: BrandTable by lazy { BrandTable.loadDefault() }
    val generator: SearchKeyGenerator by lazy { SearchKeyGenerator(kuromoji, LatinWordReader(brands)) }
}
