// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.test.platform.app.InstrumentationRegistry
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.state.RecordsRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 計器テストから、アプリの記録の書き手を読み書きする。 */
class TestRecords {
    private val records =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.appContainer.records

    /** それまでに頼んだ記録の変更が済むまで待つ（書き手は頼みを順に処理するので、後ろに頼みを足して待つ）。 */
    fun settle() {
        runBlocking { withTimeout(DeviceShell.TIMEOUT_MILLIS) { records.refresh().await() } }
    }

    /** 今の学習。 */
    fun learned(): Map<LearnedPair, LearnedRecord> {
        settle()
        return records.state.value.records
            ?.learned
            ?.records
            .orEmpty()
    }

    /** 検索語 [query] の学習を消し、出しかけの救済の知らせを消費する（前のテストや手で試した記録を残さない）。 */
    fun forget(query: String) {
        runBlocking {
            withTimeout(DeviceShell.TIMEOUT_MILLIS) {
                learned().keys.filter { it.query.value == query }.forEach { records.request(RecordsRequest.Remove(it)) }
            }
        }
        consumeNotices()
    }

    /** 出しかけの救済の知らせを消費する。 */
    fun consumeNotices() {
        settle()
        records.state.value.notices
            .forEach { records.consumeNotice(it.id) }
        settle()
    }
}
