// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import jp.piu.search.core.state.RecordsWrite
import jp.piu.search.core.state.UserRecords

/**
 * 1 つのトランザクションで読んだ記録。
 *
 * @property records 学習と起動の記録の写し
 * @property storedSerials 同じトランザクションで、両方の表から直に数えた行のある番号（`RecordsRules.loaded` に渡す）
 */
public data class StoredRecords(
    val records: UserRecords,
    val storedSerials: Set<Long>,
)

/**
 * 記録の保存先。[RecordsActor] だけが、1 本の列の中から順に呼ぶ。どちらの操作も、失敗したら例外を投げ、DB を変えない。
 *
 * 呼ぶ側の coroutine を止めてファイルに触れる（suspend にしない）。書き込みの途中で取り消しが届いても、トランザクションを
 * 最後まで済ませてから結果を見るため。
 */
public interface RecordsStorage {
    /** 記録を 1 つのトランザクションで読む。最初の読み込みの前に、端末間の転送の整理を済ませる。 */
    public fun load(): StoredRecords

    /** [write] を 1 つのトランザクションで書く。 */
    public fun write(write: RecordsWrite)
}
