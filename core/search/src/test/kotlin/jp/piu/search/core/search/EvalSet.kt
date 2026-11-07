// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import java.util.Locale

/** 評価セットの表（`src/test/resources/eval/` の TSV）1 つ。列の名前と、列の名前で引ける行。 */
internal class TsvTable(
    val header: List<String>,
    val rows: List<TsvRow>,
)

/**
 * 表の 1 行。[line] は元のファイルの行番号（1 始まり）で、誤りの知らせに使う。
 *
 * 行末の空の列は省いてよい。エディタが行末の空白を消す（.editorconfig の trim_trailing_whitespace）ため。
 */
internal class TsvRow(
    val line: Int,
    private val header: List<String>,
    val fields: List<String>,
) {
    operator fun get(column: String): String {
        val index = header.indexOf(column)
        require(index >= 0) { "列がない: $column" }
        return fields.getOrElse(index) { "" }
    }
}

/** 評価セットのアプリ 1 件。 */
internal data class EvalApp(
    val name: String,
    val englishName: String,
    val packageName: String,
    val genres: List<String>,
)

/** 正解の判定の仕方。 */
internal enum class Judge(
    val token: String,
) {
    /** 正解のどれか 1 つが出ればよい。 */
    ANY("any"),

    /** 正解がすべて出る必要がある。 */
    ALL("all"),
}

/**
 * 評価セットの検索語 1 件。[answers] は apps.tsv の表示名。
 *
 * [knownWeakness] は「既知の弱点」の印の理由（メモが [EvalSet.WEAKNESS_PREFIX] で始まる行）。印がなければ null。
 * 印の付いた検索語は指標に数えるが、合格の判定では外す（testing.md の「評価セット」）。
 */
internal data class EvalQuery(
    val query: String,
    val answers: List<String>,
    val judge: Judge,
    val category: String,
    val knownWeakness: String? = null,
)

/** 評価セットの読み込みと検査。M1-06b の評価もここで読む。 */
internal object EvalSet {
    val APP_COLUMNS = listOf("表示名", "英語名", "パッケージ名", "ジャンル", "確かめ方", "メモ")
    val QUERY_COLUMNS = listOf("検索語", "正解", "判定", "分類", "メモ")

    /** docs/design/matching.md の「intent filter とカテゴリによるジャンル」の表の id。 */
    val GENRE_IDS =
        setOf(
            "maps",
            "email",
            "phone",
            "messaging",
            "camera",
            "photos",
            "video",
            "music",
            "browser",
            "calendar",
            "contacts",
            "calculator",
            "clock",
            "files",
            "weather",
            "translate",
            "store",
            "pdf",
            "game",
            "news",
            "social",
        )

    val VERIFICATIONS = setOf("play", "f-droid", "source", "docs", "要確認")

    /** M1 のタスクカードの分類に、短い一般語の誤一致を見る「短い語」と、学習で埋める想定の「学習」を足したもの。 */
    val CATEGORIES =
        setOf(
            "表記ゆれ",
            "送り仮名",
            "ローマ字",
            "英字のブランド",
            "略語",
            "ジャンル",
            "漢字の検索語",
            "途中まで",
            "短い語",
            "学習",
        )

    const val LIST_SEPARATOR = "|"

    /** 合格の目安から外す分類。学習で埋める想定の検索語（M4 で学習の場面として使う）。 */
    const val LEARNING_CATEGORY = "学習"

    /** メモの頭にこの印があれば「既知の弱点」。印の後に理由を書く。 */
    const val WEAKNESS_PREFIX = "既知の弱点:"

    // Java のパッケージ名の文法。大文字（com.Slack）と _ を許す
    private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    fun resource(name: String): String =
        requireNotNull(EvalSet::class.java.getResourceAsStream("/eval/$name")) {
            "資源がない: eval/$name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    fun parse(text: String): TsvTable {
        val lines =
            text
                .split('\n')
                .mapIndexed { i, s -> i + 1 to s }
                .filter { (_, s) -> s.isNotBlank() && !s.startsWith("#") }
        require(lines.isNotEmpty()) { "列の名前の行がない" }
        val header = lines.first().second.split('\t')
        val rows = lines.drop(1).map { (n, s) -> TsvRow(n, header, s.split('\t')) }
        return TsvTable(header, rows)
    }

    fun apps(table: TsvTable): List<EvalApp> =
        table.rows.map {
            EvalApp(it["表示名"], it["英語名"], it["パッケージ名"], splitList(it["ジャンル"]))
        }

    fun queries(table: TsvTable): List<EvalQuery> =
        table.rows.map { row ->
            EvalQuery(
                query = row["検索語"],
                answers = splitList(row["正解"]),
                judge = requireNotNull(Judge.entries.find { it.token == row["判定"] }) { "${row.line} 行: 判定が不正" },
                category = row["分類"],
                knownWeakness = row["メモ"].takeIf { it.startsWith(WEAKNESS_PREFIX) }?.removePrefix(WEAKNESS_PREFIX)?.trim(),
            )
        }

    private fun splitList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(LIST_SEPARATOR)

    /** apps.tsv の誤りの一覧。空なら正しい。 */
    fun checkApps(table: TsvTable): List<String> {
        val errors = checkShape(table, APP_COLUMNS)
        if (errors.isNotEmpty()) return errors
        for (row in table.rows) {
            val at = "apps.tsv ${row.line} 行"
            if (row["表示名"].isBlank()) errors += "$at: 表示名が空"
            if (!PACKAGE_NAME.matches(row["パッケージ名"])) errors += "$at: パッケージ名の形が不正: ${row["パッケージ名"]}"
            if (row["確かめ方"] !in VERIFICATIONS) errors += "$at: 確かめ方が不正: ${row["確かめ方"]}"
            if (row["確かめ方"] == "要確認" && "要確認" !in row["メモ"]) errors += "$at: 要確認の理由がメモにない"
            for (genre in splitList(row["ジャンル"])) {
                if (genre !in GENRE_IDS) errors += "$at: 知らないジャンル: $genre"
            }
            if (row.fields.any { it != it.trim() }) errors += "$at: 前後に空白がある"
        }
        errors += duplicates(table, "表示名")
        errors += duplicates(table, "パッケージ名")
        return errors
    }

    /** queries.tsv の誤りの一覧。[appNames] は apps.tsv の表示名。空なら正しい。 */
    fun checkQueries(
        table: TsvTable,
        appNames: Set<String>,
    ): List<String> {
        val errors = checkShape(table, QUERY_COLUMNS)
        if (errors.isNotEmpty()) return errors
        val seen = mutableSetOf<Pair<String, String>>()
        for (row in table.rows) {
            val at = "queries.tsv ${row.line} 行"
            val query = row["検索語"]
            if (query.isBlank()) errors += "$at: 検索語が空"
            // 人は大文字を打たない前提で作った
            if (query != query.lowercase(Locale.ROOT)) errors += "$at: 検索語に大文字がある: $query"
            val answers = splitList(row["正解"])
            if (answers.isEmpty()) errors += "$at: 正解が空"
            for (answer in answers) {
                if (answer !in appNames) errors += "$at: 正解が apps.tsv にない: $answer"
            }
            if (answers.size != answers.toSet().size) errors += "$at: 正解が重なっている"
            if (Judge.entries.none { it.token == row["判定"] }) errors += "$at: 判定が不正: ${row["判定"]}"
            if (row["分類"] !in CATEGORIES) errors += "$at: 分類が不正: ${row["分類"]}"
            if (!seen.add(query to row["分類"])) errors += "$at: 同じ検索語と分類の行がある: $query"
            if (row.fields.any { it != it.trim() }) errors += "$at: 前後に空白がある"
            if (row["メモ"].startsWith(WEAKNESS_PREFIX)) {
                if (row["メモ"].removePrefix(WEAKNESS_PREFIX).isBlank()) errors += "$at: 既知の弱点の理由がない"
                if (row["分類"] == LEARNING_CATEGORY) errors += "$at: 学習の分類には既知の弱点の印を付けない（もとから合格の目安の外）"
            }
        }
        return errors
    }

    private fun checkShape(
        table: TsvTable,
        columns: List<String>,
    ): MutableList<String> {
        val errors = mutableListOf<String>()
        if (table.header != columns) {
            errors += "列の名前が違う: ${table.header}（期待: $columns）"
            return errors
        }
        for (row in table.rows) {
            if (row.fields.size > columns.size) errors += "${row.line} 行: 列が多い（${row.fields.size}）"
        }
        return errors
    }

    private fun duplicates(
        table: TsvTable,
        column: String,
    ): List<String> =
        table.rows.groupBy { it[column] }.filterValues { it.size > 1 }.map { (value, rows) ->
            "apps.tsv: $column が重なっている: $value（${rows.joinToString { it.line.toString() }} 行）"
        }
}
