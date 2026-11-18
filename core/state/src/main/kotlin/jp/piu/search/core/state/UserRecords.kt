// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord

/**
 * 記録の写し（学習と起動の記録）。1 つのトランザクションで読み、1 つの写しにする。
 *
 * 等しさは中身（学習の組ごとの記録と起動の記録）で比べる。[LearnedMemory] と [LaunchHistory] は中身で比べないため。
 *
 * @property learned 学習
 * @property launches 起動の記録
 */
public class UserRecords(
    public val learned: LearnedMemory,
    public val launches: LaunchHistory,
) {
    /** 記録に出てくるプロファイルのシリアル番号。 */
    public val serials: Set<Long>
        get() = learned.records.keys.mapTo(HashSet()) { it.app.profileSerial } + launches.records.keys.map { it.profileSerial }

    /** [serials] のプロファイルの記録を除いたもの。 */
    public fun without(serials: Set<Long>): UserRecords =
        UserRecords(
            LearnedMemory(learned.records.values.filter { it.app.profileSerial !in serials }),
            LaunchHistory(launches.records.filterKeys { it.profileSerial !in serials }),
        )

    override fun equals(other: Any?): Boolean =
        other is UserRecords && learned.records == other.learned.records && launches.records == other.launches.records

    override fun hashCode(): Int = 31 * learned.records.hashCode() + launches.records.hashCode()

    override fun toString(): String = "UserRecords(learned=${learned.records.values}, launches=${launches.records})"

    /** 定数。 */
    public companion object {
        /** 記録のない写し。読み込みの失敗の代わりには使わない（空から保存すると保存済みの記録を上書きするため）。 */
        public val EMPTY: UserRecords = UserRecords(LearnedMemory.EMPTY, LaunchHistory.EMPTY)
    }
}

/**
 * 記録の DB への書き込み 1 回の中身。1 つのトランザクションで書く。
 *
 * @property clearLearned 学習の表の全行を消す（全消去。写しに見えない旧版の行も残さないため）
 * @property clearLaunches 起動の記録の表の全行を消す
 * @property deleteSerials この番号のプロファイルの行を両方の表から消す（プロファイルの整理）
 * @property learnedPut 書く学習の行
 * @property learnedDelete 消す学習の組
 * @property launchPut 書く起動の記録
 */
public data class RecordsWrite(
    val clearLearned: Boolean = false,
    val clearLaunches: Boolean = false,
    val deleteSerials: Set<Long> = emptySet(),
    val learnedPut: List<LearnedRecord> = emptyList(),
    val learnedDelete: Set<LearnedPair> = emptySet(),
    val launchPut: Map<AppId, LaunchRecord> = emptyMap(),
) {
    /** 書くものがないか。 */
    public val isEmpty: Boolean
        get() =
            !clearLearned && !clearLaunches && deleteSerials.isEmpty() && learnedPut.isEmpty() &&
                learnedDelete.isEmpty() && launchPut.isEmpty()

    /** 作り方。 */
    public companion object {
        /** [old] から [next] への差分。起動の記録は消さない操作しかないので、書くだけにする。 */
        public fun diff(
            old: UserRecords,
            next: UserRecords,
        ): RecordsWrite {
            val before = old.learned.records
            val after = next.learned.records
            return RecordsWrite(
                learnedPut = after.values.filter { before[it.pair] != it },
                learnedDelete = before.keys - after.keys,
                launchPut = next.launches.records.filter { (app, record) -> old.launches.records[app] != record },
            )
        }
    }
}
