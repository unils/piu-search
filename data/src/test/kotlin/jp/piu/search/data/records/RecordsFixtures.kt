// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.state.ProfileCheck
import jp.piu.search.core.state.RecordsWrite
import jp.piu.search.core.text.NormalizedText
import java.io.IOException

// 記録の側のテストに共通の材料。自分の番号は 0、仕事用は 11

internal const val OWN = 0L
internal const val WORK = 11L
internal val OWN_APP = AppId("io.example.own", "Main", OWN)
internal val OTHER_APP = AppId("io.example.other", "Main", OWN)
internal val WORK_APP = AppId("io.example.work", "Main", WORK)

internal fun pair(
    query: String,
    app: AppId,
): LearnedPair = LearnedPair(NormalizedText(query), app)

internal fun record(
    query: String,
    app: AppId,
    count: Int = 1,
    at: Long = 1000,
    rescued: Boolean = false,
): LearnedRecord = LearnedRecord(NormalizedText(query), app, count, at, rescued)

/** 自分と仕事用の学習と起動の記録を 1 つずつ書いた中身。 */
internal fun seedWrite(): RecordsWrite =
    RecordsWrite(
        learnedPut = listOf(record("よみ", OWN_APP), record("よみ", WORK_APP)),
        launchPut = mapOf(OWN_APP to LaunchRecord(2, 1000), WORK_APP to LaunchRecord(3, 1000)),
    )

/** 問い合わせの答えを差し替えられる [ProfileProbe]。null は問い合わせの失敗。 */
internal class FakeProbe(
    var serials: Set<Long>? = setOf(OWN, WORK),
) : ProfileProbe {
    var calls = 0

    override fun check(): ProfileCheck {
        calls++
        return ProfileCheck.of(OWN, serials)
    }
}

/**
 * 読み込みと書き込みを、指定した回数だけ失敗させる保存先。失敗は委ねる前に投げ、DB には触れない
 * （トランザクションの途中での失敗は、SQLite のトリガーで本物の DB に起こす）。
 */
internal class FaultyStorage(
    private val delegate: RecordsStorage,
) : RecordsStorage {
    var failLoads = 0
    var failWrites = 0
    var loads = 0
    val writes = ArrayList<RecordsWrite>()

    override fun load(): StoredRecords {
        loads++
        if (failLoads > 0) {
            failLoads--
            throw IOException("読み込みの失敗")
        }
        return delegate.load()
    }

    override fun write(write: RecordsWrite) {
        if (failWrites > 0) {
            failWrites--
            throw SQLiteFullException("書き込みの失敗")
        }
        delegate.write(write)
        writes += write
    }
}

/** Room を通さずに DB のファイルを開いて [action] を行う。トリガーで失敗を仕込む・行を直に数えるのに使う。 */
internal fun <T> withRawDatabase(
    context: Context,
    name: String,
    action: (SQLiteDatabase) -> T,
): T {
    val db = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
    return try {
        action(db)
    } finally {
        db.close()
    }
}

internal fun SQLiteDatabase.count(sql: String): Long = rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else -1 }
