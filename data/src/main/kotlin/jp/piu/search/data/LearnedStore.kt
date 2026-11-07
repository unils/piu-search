// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.LearnedRow
import jp.piu.search.data.db.PiuRoomDatabase

/**
 * 学習の表（`learned`）の読み書き。[LocalDatabase.learned] で得る。
 *
 * 索引の表とは別に持ち、索引を作り直しても消さない（消えてよくない表。architecture.md の「データの保存」）。
 * 学習の判定と積み方は `core:search`（[LearnedMemory]）が受け持ち、ここは変わった組を書き写すだけにする。
 * 打った文字の列（セッションの出来事）は受け取らない（learning.md の「保存と持ち出し」）。
 */
public class LearnedStore internal constructor(
    private val database: PiuRoomDatabase,
) {
    private val dao = database.learnedDao()

    /** すべての記録を読む。消えたアプリの記録も含む。 */
    public fun load(): LearnedMemory =
        LearnedMemory(
            dao.all().map {
                LearnedRecord(
                    query = NormalizedText(it.query),
                    app = AppId(it.packageName, it.activityName, it.profileSerial),
                    count = it.useCount,
                    lastUsedAt = it.lastUsedAt,
                    rescued = it.rescued,
                    suppressed = it.suppressed,
                )
            },
        )

    /**
     * [before] から [after] に変わった組だけを、1 つのトランザクションで書く。[after] にない組は消す。
     * メモリ上の記録（[before]）がデータベースと同じであることを前提にする。
     */
    public fun save(
        before: LearnedMemory,
        after: LearnedMemory,
    ) {
        if (before === after) return
        val changed = after.records.values.filter { before.records[it.pair] != it }
        val removed = before.records.keys - after.records.keys
        if (changed.isEmpty() && removed.isEmpty()) return
        database.runInTransaction {
            if (changed.isNotEmpty()) dao.put(changed.map(::rowOf))
            removed.forEach(::delete)
        }
    }

    private fun delete(pair: LearnedPair) {
        dao.delete(pair.app.profileSerial, pair.app.packageName, pair.app.activityName, pair.query.value)
    }

    private fun rowOf(record: LearnedRecord): LearnedRow =
        LearnedRow(
            query = record.query.value,
            packageName = record.app.packageName,
            activityName = record.app.activityName,
            profileSerial = record.app.profileSerial,
            useCount = record.count,
            lastUsedAt = record.lastUsedAt,
            rescued = record.rescued,
            suppressed = record.suppressed,
        )
}
