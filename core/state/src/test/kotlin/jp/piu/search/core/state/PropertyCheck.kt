// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import kotlin.random.Random

/**
 * 種（seed）を固定した乱数で性質を確かめる。依存を足さないための小さな道具。
 * 失敗したら、種と、[body] が記録した入力の列を失敗の文に出す（同じ種で同じ入力が再現する）。
 */
internal fun forAllSeeds(
    name: String,
    seeds: Int,
    body: (Random, MutableList<String>) -> Unit,
) {
    for (seed in 0 until seeds) {
        val log = ArrayList<String>()
        try {
            body(Random(seed), log)
        } catch (e: AssertionError) {
            throw AssertionError("$name: 種 $seed で失敗した。入力:\n${log.joinToString("\n")}", e)
        } catch (e: RuntimeException) {
            throw AssertionError("$name: 種 $seed で例外になった。入力:\n${log.joinToString("\n")}", e)
        }
    }
}

/** 条件 [condition] が偽なら、文 [message] で失敗させる。 */
internal fun law(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw AssertionError(message())
}
