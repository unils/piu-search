// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.state.ProfileCheck
import jp.piu.search.core.state.RecordsWrite
import jp.piu.search.core.text.NormalizedText
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    @Volatile var serials: Set<Long>? = setOf(OWN, WORK),
) : ProfileProbe {
    @Volatile var calls = 0

    override fun check(): ProfileCheck {
        calls++
        return serials?.let { ProfileCheck.Existing(it) } ?: ProfileCheck.Unknown
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

    // 書き込みの失敗の型。SQLite の失敗（RuntimeException）のほか、保存先の入出力の失敗（IOException）も試す
    var writeFailure: () -> Exception = { SQLiteFullException("書き込みの失敗") }
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
            throw writeFailure()
        }
        delegate.write(write)
        writes += write
    }

    override fun rebuiltAfterCorruption(): Boolean = delegate.rebuiltAfterCorruption()

    override fun clearRebuilt() = delegate.clearRebuilt()
}

/**
 * [blocks] に当たる書き込みを、[release] まで止める保存先。本物のスレッドの上で、書き込みの途中の公開値や取り消しを見るために使う。
 * 止めた書き込みは、[failBlocked] なら DB に触れずに失敗させる。
 */
internal class BlockingStorage(
    private val delegate: RecordsStorage,
    private val blocks: (RecordsWrite) -> Boolean,
) : RecordsStorage {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile var failBlocked = false

    override fun load(): StoredRecords = delegate.load()

    override fun write(write: RecordsWrite) {
        if (blocks(write) && entered.count > 0) {
            entered.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "止めた書き込みが解かれない" }
            if (failBlocked) throw SQLiteFullException("止めた書き込みの失敗")
        }
        delegate.write(write)
    }

    override fun rebuiltAfterCorruption(): Boolean = delegate.rebuiltAfterCorruption()

    override fun clearRebuilt() = delegate.clearRebuilt()

    fun awaitEntered() {
        check(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "書き込みが始まらない" }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
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

/** DB のファイルの先頭（SQLite の見出し）を壊す。開くと SQLite が破損を知らせる。 */
internal fun corrupt(
    context: Context,
    name: String,
) {
    val file = context.getDatabasePath(name)
    val bytes = file.readBytes()
    "壊れた見出し".toByteArray().copyInto(bytes)
    file.writeBytes(bytes)
}

/** 「壊れて作り直した」の印のファイル。 */
internal fun rebuiltMark(
    context: Context,
    name: String,
): java.io.File = java.io.File(context.noBackupFilesDir, "$name.rebuilt")

/**
 * 0.2.0 以前の `piu.db`（版 1〜6）を、書き出したスキーマ（data/schemas/jp.piu.search.data.db.PiuRoomDatabase/）から作る道具。
 *
 * その版の DB のクラスはもうないので、スキーマのフォルダの名前で引く（クラスを受ける形は、クラスの名前からフォルダを決める）。
 * 名前で引く形は自動の移行を扱えないので非推奨になっているが、版 6 までに自動の移行はない。
 */
@Suppress("DEPRECATION")
internal fun legacyMigrationHelper(): MigrationTestHelper =
    MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), "jp.piu.search.data.db.PiuRoomDatabase")

/** 版 6 までの移行（[Migration6To7] の前まで）。古い版の DB を版 6 へ 1 段ずつ進めて、各段の表の形を書き出したスキーマと比べる。 */
internal val LEGACY_MIGRATIONS = RecordsRoomDatabase.MIGRATIONS.filterNot { it === Migration6To7 }.toTypedArray()
