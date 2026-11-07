// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.TextNormalizer

/**
 * 英字のブランド名の読みの表（matching.md の「英字のブランド名の読み」）。綴り → 読みの一覧。
 *
 * 形態素解析器は英字の語に読みを付けないので、アプリ名の英字の語（Yahoo・LINE など）の読みをここで補う。
 * 中身は `brands.tsv`（この型と同じパッケージの資源）にあり、書式の検査は `tools:dict` が `check` で行う。
 */
public class BrandTable private constructor(
    private val readingsBySpelling: Map<String, List<NormalizedText>>,
) {
    /** 綴りの数。 */
    public val size: Int get() = readingsBySpelling.size

    /**
     * 綴り [spelling]（[TextNormalizer.strict] を通した英字）の読み。表になければ空。
     *
     * 読みはひらがなと「ー」だけで、表に書いた順に並ぶ（先頭が最も一般的な読み）。
     */
    public fun readings(spelling: NormalizedText): List<NormalizedText> = readingsBySpelling[spelling.value].orEmpty()

    /** 表の読み込み。 */
    public companion object {
        /** 表の資源の名前。[BrandTable] のクラスと同じパッケージに置く。 */
        public const val RESOURCE_NAME: String = "brands.tsv"

        /** 区切り。列はタブ、1 つの欄の中の複数の読みは `,`。 */
        private const val COLUMN_SEPARATOR = '\t'
        private const val READING_SEPARATOR = ','
        private const val COMMENT_PREFIX = "#"

        /** 列の数: 綴り・読み・メモ。 */
        private const val COLUMN_COUNT = 3

        /**
         * アプリに同梱した表を読む。
         *
         * 数百行を読むだけで軽いが、呼ぶたびに読み直すので、呼び出し側が 1 つを持ち回る。
         *
         * @throws IllegalStateException 資源が見つからない（ビルドの誤り）
         * @throws IllegalArgumentException 表の書式が崩れている
         */
        public fun loadDefault(): BrandTable {
            val stream =
                checkNotNull(BrandTable::class.java.getResourceAsStream(RESOURCE_NAME)) {
                    "資源 $RESOURCE_NAME が見つからない"
                }
            return stream.bufferedReader(Charsets.UTF_8).use { parse(it.readLines()) }
        }

        /**
         * TSV の行 [lines] から表を作る。
         *
         * 空の行と `#` で始まる行は飛ばす。綴りは念のため [TextNormalizer.strict] にかけてから鍵にする
         * （正規化済みであることは `tools:dict` が確かめるが、引く側と同じ形にそろうことをここでも保つ）。
         * 同じ綴りが 2 回出たら、読みを後ろに足す。
         *
         * @throws IllegalArgumentException 列の数が違う、綴りか読みが空の行がある
         */
        public fun parse(lines: List<String>): BrandTable {
            val table = LinkedHashMap<String, MutableList<NormalizedText>>()
            lines.forEachIndexed { index, line ->
                if (line.isBlank() || line.startsWith(COMMENT_PREFIX)) return@forEachIndexed
                val columns = line.split(COLUMN_SEPARATOR)
                val lineNumber = index + 1
                require(columns.size == COLUMN_COUNT) { "$lineNumber 行目: 列が ${columns.size} 個（$COLUMN_COUNT 個のはず）" }
                val spelling = TextNormalizer.strict(columns[0]).value
                val readings = columns[1].split(READING_SEPARATOR).filter { it.isNotEmpty() }
                require(spelling.isNotEmpty()) { "$lineNumber 行目: 綴りが空" }
                require(readings.isNotEmpty()) { "$lineNumber 行目: 読みが空" }
                val list = table.getOrPut(spelling) { ArrayList(readings.size) }
                readings.map(::NormalizedText).filterTo(list) { it !in list }
            }
            return BrandTable(table)
        }
    }
}
