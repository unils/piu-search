// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningLimits
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.LearnedDao
import jp.piu.search.data.db.LearnedRow
import jp.piu.search.data.db.PiuRoomDatabase
import java.util.concurrent.Callable

/** 学習の保存先。保存が失敗したときは例外を返し、変更を残さない。 */
public interface LearnedStorage {
    /** 保存した学習を読む。 */
    public fun load(): LearnedMemory

    /** [before] が保存済みの内容と一致する前提で、差分全体を保存する。 */
    public fun save(
        before: LearnedMemory,
        after: LearnedMemory,
    )

    /** メモリに見えない行も含め、全行を消す。 */
    public fun clear()
}

/**
 * 学習の表（`learned`）の読み書き。[LocalDatabase.learned] で得る。
 *
 * 索引の表とは別に持ち、索引を作り直しても消さない（消えてよくない表。architecture.md の「データの保存」）。
 * 学習の判定と積み方は `core:search`（[LearnedMemory]）が受け持ち、通常の保存では変わった組だけを書き写す。
 * 打った文字の列（セッションの出来事）は受け取らない（learning.md の「保存と持ち出し」）。
 */
public class LearnedStore internal constructor(
    private val database: PiuRoomDatabase,
    private val dao: LearnedDao = database.learnedDao(),
) : LearnedStorage {
    private var legacyRowsChecked = false

    /**
     * すべての記録を読む。消えたアプリの記録も含む。
     * 初回は上限を超える旧版の行を DB からも削除し、メモリに見えず消せない記録を残さない。
     */
    @Synchronized
    override fun load(): LearnedMemory {
        val rows =
            if (legacyRowsChecked) {
                dao.all()
            } else {
                database
                    .runInTransaction(
                        Callable {
                            dao.all().filter { row ->
                                if (LearningLimits.accepts(NormalizedText(row.query))) {
                                    true
                                } else {
                                    dao.delete(row.profileSerial, row.packageName, row.activityName, row.query)
                                    false
                                }
                            }
                        },
                    ).also {
                        // 削除が確定してから済んだ印を付ける。失敗した読み込みを再試行しても整理を省かないため
                        legacyRowsChecked = true
                    }
            }
        return LearnedMemory(
            rows.map {
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
    }

    /** 個人用・仕事用の学習を全行削除する。メモリに見えない行も消し、索引と起動の記録には触れない。 */
    override fun clear() {
        dao.deleteAll()
    }

    /**
     * [before] から [after] に変わった組だけを、1 つのトランザクションで書く。[after] にない組は消す。
     * メモリ上の記録（[before]）がデータベースと同じであることを前提にする。
     */
    override fun save(
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
