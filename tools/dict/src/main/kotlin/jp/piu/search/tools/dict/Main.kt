// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.tools.dict

import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * `brands.tsv` を検査する入口。Gradle の `checkBrandTable` から呼ぶ。
 *
 * 引数は「検査する表の道」「成功したときに書く報告の道」。問題があれば標準エラーに並べ、終了コード 1 で終わる。
 */
public fun main(args: Array<String>) {
    val status = run(args, System.err)
    if (status != 0) exitProcess(status)
}

/** [main] の本体。終了コードを返す（テストから exitProcess を通さずに呼ぶため）。 */
internal fun run(
    args: Array<String>,
    err: PrintStream,
): Int {
    if (args.size != 2) {
        err.println("使い方: <brands.tsv の道> <報告の道>")
        return 2
    }
    val table = File(args[0])
    val lines = table.readLines(Charsets.UTF_8)
    val problems = BrandTableValidator.validate(lines)
    if (problems.isNotEmpty()) {
        err.println("${table.path} に ${problems.size} 個の問題がある:")
        problems.forEach { err.println("  $it") }
        return 1
    }
    // 報告は成功したときだけ書く。Gradle が up-to-date を判定する出力にするため
    val entries = lines.count { it.isNotEmpty() && !it.startsWith("#") }
    File(args[1]).apply { parentFile?.mkdirs() }.writeText("${table.path}: $entries 語、問題なし\n")
    return 0
}
