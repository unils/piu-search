// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningConfig
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 記録の規則の具体例（読み込みの失敗・保存の失敗・取り消し・その場のプロファイルの確認と削除・整理・読み込み）。 */
class RecordsRulesTest {
    @Test
    fun `読み込みの失敗の間は、空の写しから計算も書き込みもせずに失敗を返す`() {
        val failed = RecordsRules.loadFailed(RecordsState(), "android.database.sqlite.SQLiteException")
        for (state in listOf(RecordsState(), failed)) {
            val change = RecordsRules.change(state, RecordsRequest.Suppress(pair("てんき", WEATHER), NOW), UNKNOWN)
            assertNull(change.write)
            assertSame(state, change.next)
            assertEquals(RecordsResult.Failure(RecordsFailure.NOT_LOADED), change.result)
        }
        // 次の頼みで読み直せば受ける
        val ready = RecordsRules.loaded(failed, UserRecords.EMPTY, emptySet(), UNKNOWN)
        assertTrue(RecordsRules.change(ready, RecordsRequest.Suppress(pair("てんき", WEATHER), NOW), UNKNOWN).write != null)
    }

    @Test
    fun `記録の保存に失敗したら、写しも知らせも変わらない`() {
        val state = ready()
        val change = RecordsRules.change(state, open(rescues = listOf(pair("おかね", PAY))), UNKNOWN)
        assertEquals(1, (change.result as RecordsResult.Success).notices.size)
        assertSame(state, RecordsRules.commit(change, written = false))
        assertEquals(RecordsResult.Failure(RecordsFailure.WRITE_FAILED), RecordsRules.resultOf(change, written = false))
    }

    @Test
    fun `保存に失敗しても、その場で確かめた削除は忘れず、取り消しを無効にして整理の対象にする`() {
        val work = AppId("jp.example.mail", "Inbox", WORK)
        val opened = apply(ready(), open(rescues = listOf(pair("めーる", work)), launched = work), exists(0, WORK))
        val id = opened.notices.single().id
        val change = RecordsRules.change(opened, open(selection = pair("てんき", WEATHER), at = NOW + 1), exists(0))
        val failed = RecordsRules.commit(change, written = false)
        assertEquals(opened.records, failed.records)
        assertEquals(setOf(WORK), failed.profiles.removed)
        assertEquals(setOf(WORK), failed.profiles.pendingPrune)
        assertFalse(failed.canUndo(id))
    }

    @Test
    fun `アプリを開くと、学習と起動の記録を 1 回の書き込みにし、起動の回数と時刻を前の行から進める`() {
        val change = RecordsRules.change(ready(), open(selection = pair("てんき", WEATHER), launched = WEATHER), UNKNOWN)
        val write = change.write!!
        assertEquals(listOf(pair("てんき", WEATHER)), write.learnedPut.map { it.pair })
        assertEquals(mapOf(WEATHER to LaunchRecord(1, NOW)), write.launchPut)
        val once = RecordsRules.commit(change, written = true)
        // 続けての起動。間の保存の失敗は数えない
        val lost = RecordsRules.commit(RecordsRules.change(once, open(launched = WEATHER, at = NOW + 5), UNKNOWN), written = false)
        assertEquals(once, lost)
        val twice = apply(lost, open(launched = WEATHER, at = NOW + 9))
        assertEquals(
            LaunchRecord(2, NOW + 9),
            twice.records!!
                .launches.records
                .getValue(WEATHER),
        )
        // 時刻が戻っても最後の時刻は戻さない
        assertEquals(
            LaunchRecord(3, NOW + 9),
            apply(twice, open(launched = WEATHER, at = NOW))
                .records!!
                .launches.records
                .getValue(WEATHER),
        )
    }

    @Test
    fun `救済の知らせを取り消すと記録する前に戻し、同じセッションの選択の分は残す`() {
        val before = LearnedRecord(NormalizedText("てんき"), WEATHER, 2, NOW - DAY, rescued = false)
        val state = ready(UserRecords(LearnedMemory(listOf(before)), LaunchHistory.EMPTY))
        val both = pair("てんき", WEATHER)
        val opened = apply(state, open(selection = both, rescues = listOf(both)))
        val notice = opened.notices.single()
        assertTrue(opened.canUndo(notice.id))

        val undone = apply(opened, RecordsRequest.Undo(notice.id))
        val record =
            undone.records!!
                .learned.records
                .getValue(both)
        assertEquals(3, record.count)
        assertFalse(record.rescued)
        assertFalse(undone.canUndo(notice.id))
        assertTrue(undone.notices.isEmpty())
    }

    @Test
    fun `同じ組を後で保存したら、古い取り消しは無効になり、何も変えずに成功する`() {
        val rescue = pair("おかね", PAY)
        val opened = apply(ready(), open(rescues = listOf(rescue)))
        val id = opened.notices.single().id
        val again = apply(opened, open(selection = rescue, at = NOW + 1))
        assertFalse(again.canUndo(id))
        val stale = RecordsRules.change(again, RecordsRequest.Undo(id), UNKNOWN)
        assertNull(stale.write)
        assertEquals(RecordsResult.Success(), stale.result)
        assertEquals(
            2,
            again.records!!
                .learned.records
                .getValue(rescue)
                .count,
        )
    }

    @Test
    fun `取り消しは知らせの識別子で照合する`() {
        val rescue = pair("おかね", PAY)
        val first = apply(ready(), open(rescues = listOf(rescue)))
        val firstId = first.notices.single().id
        val second = apply(apply(first, RecordsRequest.Undo(firstId)), open(rescues = listOf(rescue), at = NOW + 1))
        val secondId = second.notices.single().id
        assertTrue(secondId != firstId)
        // 前の知らせの取り消しでは、後の救済を消さない
        assertNull(RecordsRules.change(second, RecordsRequest.Undo(firstId), UNKNOWN).write)
        assertTrue(rescue !in apply(second, RecordsRequest.Undo(secondId)).records!!.learned.records)
    }

    @Test
    fun `知らせを出し終えても、取り消しの材料は保つ`() {
        val opened = apply(ready(), open(rescues = listOf(pair("おかね", PAY))))
        val id = opened.notices.single().id
        val consumed = RecordsRules.consumeNotice(opened, id)
        assertTrue(consumed.notices.isEmpty())
        assertTrue(consumed.canUndo(id))
    }

    @Test
    fun `全消去は表の全行を消し、取り消しを無効にする`() {
        val opened = apply(ready(), open(rescues = listOf(pair("おかね", PAY)), launched = PAY))
        val change = RecordsRules.change(opened, RecordsRequest.Clear(), UNKNOWN)
        assertEquals(RecordsWrite(clearLearned = true, clearLaunches = true), change.write)
        val cleared = RecordsRules.commit(change, written = true)
        assertEquals(UserRecords.EMPTY, cleared.records)
        assertTrue(cleared.undo.isEmpty() && cleared.notices.isEmpty())

        val launchesOnly = apply(opened, RecordsRequest.Clear(learned = false))
        assertTrue(launchesOnly.canUndo(opened.notices.single().id))
        assertNull(RecordsRules.change(opened, RecordsRequest.Clear(learned = false, launches = false), UNKNOWN).write)
    }

    @Test
    fun `プロファイルの削除の後の遅い記録と古い取り消しは、確かめられない間も拒まれる`() {
        val work = AppId("jp.example.mail", "Inbox", WORK)
        val opened = apply(ready(), open(rescues = listOf(pair("めーる", work)), launched = work), exists(0, WORK))
        val id = opened.notices.single().id

        val removed = RecordsRules.checked(opened, exists(0))
        assertEquals(setOf(WORK), removed.profiles.removed)
        assertFalse(removed.canUndo(id))
        assertTrue(removed.notices.isEmpty())

        assertNull(RecordsRules.change(removed, open(selection = pair("めーる", work), launched = work, at = NOW + 1), UNKNOWN).write)
        assertNull(RecordsRules.change(removed, RecordsRequest.Undo(id), UNKNOWN).write)
        assertNull(RecordsRules.change(removed, RecordsRequest.Suppress(pair("めーる", work), NOW), UNKNOWN).write)
    }

    @Test
    fun `確かめられない間は削除も拒否もせず、確認した集合にない新しい番号も受ける`() {
        val work = AppId("jp.example.mail", "Inbox", 12)
        assertTrue(RecordsRules.change(ready(), open(launched = work), UNKNOWN).write != null)
        val confirmed = RecordsRules.checked(ready(), exists(0))
        assertTrue(confirmed.profiles.removed.isEmpty())
        val written = RecordsRules.change(confirmed, open(launched = work), UNKNOWN)
        assertTrue(written.write != null)
        assertTrue(
            written.next.profiles.removed
                .isEmpty(),
        )
    }

    @Test
    fun `頼みの時点で確かめて不在なら、行がなくても削除済みにして拒み、存在すれば受ける`() {
        val work = AppId("jp.example.mail", "Inbox", WORK)
        val confirmed = RecordsRules.checked(ready(), exists(0))
        val request = open(selection = pair("めーる", work), launched = work)
        val absent = RecordsRules.change(confirmed, request, exists(0))
        assertNull(absent.write)
        assertEquals(setOf(WORK), absent.next.profiles.removed)
        assertTrue(RecordsRules.change(confirmed, request, exists(0, WORK)).write != null)
    }

    @Test
    fun `確かめられない間に書いた番号も、後の確認で存在が分かった後に消えれば、削除して整理し、取り消しを無効にする`() {
        val work = AppId("jp.example.mail", "Inbox", 12)
        val state = RecordsRules.checked(ready(), exists(0))
        val written = apply(state, open(rescues = listOf(pair("めーる", work)), launched = work))
        val id = written.notices.single().id
        val seen = RecordsRules.checked(written, exists(0, 12))
        assertTrue(seen.profiles.removed.isEmpty())
        val gone = RecordsRules.checked(seen, exists(0))
        assertEquals(setOf(12L), gone.profiles.removed)
        assertEquals(setOf(12L), gone.profiles.pendingPrune)
        assertFalse(gone.canUndo(id))
        assertNull(RecordsRules.change(gone, RecordsRequest.Undo(id), UNKNOWN).write)
        val pruned = RecordsRules.commit(RecordsRules.pruneChange(gone)!!, written = true)
        assertTrue(12L !in pruned.records!!.serials)
    }

    @Test
    fun `確認に渡した集合を呼ぶ側が後で変えても、確認した事実を失わない`() {
        val existing = mutableSetOf(0L, WORK)
        val confirmed = RecordsRules.checked(ready(), ProfileCheck.Existing(existing))
        existing -= WORK
        assertEquals(setOf(0L, WORK), confirmed.profiles.confirmed)
        // 行のない 10 も、前に確認した集合にあったので、次の確認で消えれば削除済みになる
        assertEquals(setOf(WORK), RecordsRules.checked(confirmed, exists(0)).profiles.removed)
    }

    @Test
    fun `読み込んだ起動の記録の Map を後で変えても、写しも次の書き込みも変わらない`() {
        val source = mutableMapOf(WEATHER to LaunchRecord(1, NOW))
        val state = RecordsRules.loaded(RecordsState(), UserRecords(LearnedMemory.EMPTY, LaunchHistory(source)), setOf(0), UNKNOWN)
        source[WEATHER] = LaunchRecord(9, NOW)
        source[PAY] = LaunchRecord(1, NOW)
        assertEquals(mapOf(WEATHER to LaunchRecord(1, NOW)), state.records!!.launches.records)
        assertEquals(
            mapOf(WEATHER to LaunchRecord(2, NOW + 1)),
            RecordsRules.change(state, open(launched = WEATHER, at = NOW + 1), UNKNOWN).write!!.launchPut,
        )
    }

    @Test
    fun `写しに見えない行だけを持つ番号も、表の番号から整理の対象にする`() {
        val hidden = LearnedRecord(NormalizedText("あ".repeat(80)), AppId("jp.example.mail", "Inbox", WORK), 1, NOW, rescued = false)
        val records = UserRecords(LearnedMemory(listOf(hidden)), LaunchHistory.EMPTY)
        assertTrue(records.serials.isEmpty())
        val loaded = RecordsRules.loaded(RecordsState(), records, storedSerials = setOf(WORK), exists(0))
        assertEquals(RecordsWrite(deleteSerials = setOf(WORK)), RecordsRules.pruneChange(loaded)!!.write)
        val pruned = RecordsRules.commit(RecordsRules.pruneChange(loaded)!!, written = true)
        assertTrue(pruned.storedSerials.isEmpty())
        // 読み込みの後の確認でも、表の番号で消えた番号を見つける
        val later = RecordsRules.checked(RecordsRules.loaded(RecordsState(), records, setOf(0, WORK), UNKNOWN), exists(0))
        assertEquals(setOf(WORK), later.profiles.pendingPrune)
    }

    @Test
    fun `確かめられない間に書いた新しい番号の行は、確かめられないまま読み直しても消さない`() {
        val work = AppId("jp.example.mail", "Inbox", 12)
        val confirmed = RecordsRules.checked(ready(), exists(0))
        val written = apply(confirmed, open(launched = work))
        val reloaded = RecordsRules.loaded(RecordsRules.loadFailed(written, "io"), written.records!!, written.records!!.serials, UNKNOWN)
        assertTrue(reloaded.profiles.removed.isEmpty())
        assertEquals(setOf(12L), reloaded.records!!.serials)
    }

    @Test
    fun `読み込みに失敗している間に確かめた削除も、書いた番号の行を見落とさない`() {
        val work = AppId("jp.example.mail", "Inbox", 12)
        val written = apply(RecordsRules.checked(ready(), exists(0)), open(launched = work))
        val gone = RecordsRules.checked(RecordsRules.loadFailed(written, "io"), exists(0))
        assertEquals(setOf(12L), gone.profiles.pendingPrune)
    }

    @Test
    fun `プロファイルの整理に失敗したら、集合が変わらないまま次の機会に再試行する`() {
        val work = AppId("jp.example.mail", "Inbox", WORK)
        val records = UserRecords(LearnedMemory.EMPTY, LaunchHistory(mapOf(work to LaunchRecord(1, NOW), WEATHER to LaunchRecord(1, NOW))))
        val removed = RecordsRules.checked(RecordsRules.checked(ready(records), exists(0, WORK)), exists(0))
        val prune = RecordsRules.pruneChange(removed)!!
        assertEquals(RecordsWrite(deleteSerials = setOf(WORK)), prune.write)

        val failed = RecordsRules.commit(prune, written = false)
        assertSame(removed, failed)
        // 同じ集合の確認でも、次の機会に同じ整理を作れる
        assertEquals(prune.write, RecordsRules.pruneChange(RecordsRules.checked(failed, exists(0)))!!.write)
        val done = RecordsRules.commit(prune, written = true)
        assertEquals(setOf(0L), done.records!!.serials)
        assertNull(RecordsRules.pruneChange(done))
        assertEquals(setOf(WORK), done.profiles.removed)
    }

    @Test
    fun `読み込みの時点で確かめた集合にない番号の記録は、整理の対象になる`() {
        val work = AppId("jp.example.mail", "Inbox", WORK)
        val records = UserRecords(LearnedMemory.EMPTY, LaunchHistory(mapOf(work to LaunchRecord(1, NOW))))
        val loaded = RecordsRules.loaded(RecordsState(), records, records.serials, exists(0))
        assertEquals(setOf(WORK), loaded.profiles.pendingPrune)
        assertEquals(RecordsWrite(deleteSerials = setOf(WORK)), RecordsRules.pruneChange(loaded)!!.write)
        // 削除を確かめた番号の行が読み戻されたら、確かめられない読み込みでも整理し直す
        val again =
            RecordsRules.loaded(
                RecordsRules.commit(RecordsRules.pruneChange(loaded)!!, written = true),
                records,
                records.serials,
                UNKNOWN,
            )
        assertEquals(setOf(WORK), again.profiles.pendingPrune)
    }

    @Test
    fun `読み込みでは消えたプロファイルの記録を受けず、その数を返す`() {
        val removed = RecordsRules.checked(RecordsRules.checked(ready(), exists(0, WORK)), exists(0))
        val imported =
            listOf(
                LearnedRecord(NormalizedText("てんき"), WEATHER, 3, NOW - DAY, rescued = true),
                LearnedRecord(NormalizedText("めーる"), AppId("jp.example.mail", "Inbox", WORK), 1, NOW, rescued = false),
            )
        val change = RecordsRules.change(removed, RecordsRequest.Import(imported, NOW), UNKNOWN)
        assertEquals(RecordsResult.Success(skipped = 1), change.result)
        val next = RecordsRules.commit(change, written = true).records!!
        assertEquals(setOf(pair("てんき", WEATHER)), next.learned.records.keys)
        assertEquals(
            NOW,
            next.learned.records
                .getValue(pair("てんき", WEATHER))
                .lastUsedAt,
        )
    }

    @Test
    fun `忘却と削除は学習だけを変え、変わらなければ書かない`() {
        val old = LearnedRecord(NormalizedText("てんき"), WEATHER, 1, NOW - LearningConfig.DEFAULT.forgetAfterMillis, rescued = false)
        val state = ready(UserRecords(LearnedMemory(listOf(old)), LaunchHistory.EMPTY))
        assertEquals(setOf(pair("てんき", WEATHER)), RecordsRules.change(state, RecordsRequest.Forget(NOW), UNKNOWN).write!!.learnedDelete)
        assertNull(RecordsRules.change(state, RecordsRequest.Forget(NOW - DAY), UNKNOWN).write)
        assertEquals(
            setOf(pair("てんき", WEATHER)),
            RecordsRules.change(state, RecordsRequest.Remove(pair("てんき", WEATHER)), UNKNOWN).write!!.learnedDelete,
        )
        assertNull(RecordsRules.change(state, RecordsRequest.Remove(pair("ちず", WEATHER)), UNKNOWN).write)
    }

    private fun apply(
        state: RecordsState,
        request: RecordsRequest,
        check: ProfileCheck = UNKNOWN,
    ): RecordsState = RecordsRules.commit(RecordsRules.change(state, request, check), written = true)

    private fun ready(records: UserRecords = UserRecords.EMPTY): RecordsState =
        RecordsRules.loaded(RecordsState(), records, records.serials, UNKNOWN)

    private fun open(
        selection: LearnedPair? = null,
        rescues: List<LearnedPair> = emptyList(),
        launched: AppId? = null,
        at: Long = NOW,
    ) = RecordsRequest.Open(LearningDecision(selection, rescues), launched, at, rescues.associateWith { "ラベル" })

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = LearningConfig.DAY_MILLIS
        const val WORK = 10L
        val UNKNOWN = ProfileCheck.Unknown
        val WEATHER = AppId("jp.example.weather", "Main", 0)
        val PAY = AppId("jp.example.pay", "Main", 0)

        fun exists(vararg serials: Long): ProfileCheck = ProfileCheck.Existing(serials.toSet())

        fun pair(
            query: String,
            app: AppId,
        ) = LearnedPair(NormalizedText(query), app)
    }
}
