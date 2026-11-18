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

/** 記録の規則の具体例（読み込みの失敗・保存の失敗・取り消し・プロファイルの削除と整理・読み込み）。 */
class RecordsRulesTest {
    @Test
    fun `読み込みの失敗の間は、空の写しから計算も書き込みもせずに失敗を返す`() {
        val failed = RecordsRules.loadFailed(RecordsState(), "android.database.sqlite.SQLiteException")
        for (state in listOf(RecordsState(), failed)) {
            val change = RecordsRules.change(state, RecordsRequest.Suppress(pair("てんき", WEATHER), NOW))
            assertNull(change.write)
            assertSame(state, change.next)
            assertEquals(RecordsResult.Failure(RecordsFailure.NOT_LOADED), change.result)
        }
        // 次の頼みで読み直せば受ける
        val ready = RecordsRules.loaded(failed, UserRecords.EMPTY)
        assertTrue(RecordsRules.change(ready, RecordsRequest.Suppress(pair("てんき", WEATHER), NOW)).write != null)
    }

    @Test
    fun `記録の保存に失敗したら、写しも知らせも変わらない`() {
        val state = ready()
        val change = RecordsRules.change(state, open(rescues = listOf(pair("おかね", PAY))))
        assertEquals(1, (change.result as RecordsResult.Success).notices.size)
        assertSame(state, RecordsRules.commit(state, change, written = false))
        assertEquals(RecordsResult.Failure(RecordsFailure.WRITE_FAILED), RecordsRules.resultOf(change, written = false))
    }

    @Test
    fun `アプリを開くと、学習と起動の記録を 1 回の書き込みにする`() {
        val change = RecordsRules.change(ready(), open(selection = pair("てんき", WEATHER), launched = WEATHER))
        val write = change.write!!
        assertEquals(listOf(pair("てんき", WEATHER)), write.learnedPut.map { it.pair })
        assertEquals(mapOf(WEATHER to LaunchRecord(1, NOW)), write.launchPut)
        val next = RecordsRules.commit(ready(), change, written = true).records!!
        assertEquals(
            1,
            next.launches.records
                .getValue(WEATHER)
                .count,
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
        val stale = RecordsRules.change(again, RecordsRequest.Undo(id))
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
        assertNull(RecordsRules.change(second, RecordsRequest.Undo(firstId)).write)
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
        val change = RecordsRules.change(opened, RecordsRequest.Clear())
        assertEquals(RecordsWrite(clearLearned = true, clearLaunches = true), change.write)
        val cleared = RecordsRules.commit(opened, change, written = true)
        assertEquals(UserRecords.EMPTY, cleared.records)
        assertTrue(cleared.undo.isEmpty() && cleared.notices.isEmpty())

        val launchesOnly = RecordsRules.commit(opened, RecordsRules.change(opened, RecordsRequest.Clear(learned = false)), written = true)
        assertTrue(launchesOnly.canUndo(opened.notices.single().id))
        assertNull(RecordsRules.change(opened, RecordsRequest.Clear(learned = false, launches = false)).write)
    }

    @Test
    fun `プロファイルの削除の後の遅い記録と古い取り消しは拒まれる`() {
        val work = AppId("jp.example.mail", "Inbox", WORK)
        val confirmed = RecordsRules.profilesConfirmed(ready(), setOf(0, WORK))
        val opened = apply(confirmed, open(rescues = listOf(pair("めーる", work)), launched = work))
        val id = opened.notices.single().id

        val removed = RecordsRules.profilesConfirmed(opened, setOf(0))
        assertEquals(setOf(WORK), removed.profiles.removed)
        assertFalse(removed.canUndo(id))
        assertTrue(removed.notices.isEmpty())

        val late = RecordsRules.change(removed, open(selection = pair("めーる", work), launched = work, at = NOW + 1))
        assertNull(late.write)
        assertNull(RecordsRules.change(removed, RecordsRequest.Undo(id)).write)
        assertNull(RecordsRules.change(removed, RecordsRequest.Suppress(pair("めーる", work), NOW)).write)
    }

    @Test
    fun `確認がまだの間は削除も拒否もせず、確認した集合にない新しい番号も受ける`() {
        val work = AppId("jp.example.mail", "Inbox", 12)
        assertTrue(RecordsRules.change(ready(), open(launched = work)).write != null)
        val confirmed = RecordsRules.profilesConfirmed(ready(), setOf(0))
        assertTrue(confirmed.profiles.removed.isEmpty())
        assertTrue(RecordsRules.change(confirmed, open(launched = work)).write != null)
    }

    @Test
    fun `確認の後に書いた新しい番号の行は、読み直しても消さない`() {
        val work = AppId("jp.example.mail", "Inbox", 12)
        val confirmed = RecordsRules.profilesConfirmed(ready(), setOf(0))
        val written = apply(confirmed, open(launched = work))
        val reloaded = RecordsRules.loaded(RecordsRules.loadFailed(written, "io"), written.records!!)
        assertTrue(reloaded.profiles.removed.isEmpty())
        assertEquals(setOf(WORK + 2), reloaded.records!!.serials)
    }

    @Test
    fun `プロファイルの整理に失敗したら、集合が変わらないまま次の機会に再試行する`() {
        val work = AppId("jp.example.mail", "Inbox", WORK)
        val records = UserRecords(LearnedMemory.EMPTY, LaunchHistory(mapOf(work to LaunchRecord(1, NOW), WEATHER to LaunchRecord(1, NOW))))
        val removed = RecordsRules.profilesConfirmed(RecordsRules.profilesConfirmed(ready(records), setOf(0, WORK)), setOf(0))
        val prune = RecordsRules.pruneChange(removed)!!
        assertEquals(RecordsWrite(deleteSerials = setOf(WORK)), prune.write)

        val failed = RecordsRules.commit(removed, prune, written = false)
        // 同じ集合の確認は配られないが、次の機会に同じ整理を作れる
        assertEquals(prune, RecordsRules.pruneChange(RecordsRules.profilesConfirmed(failed, setOf(0))))
        val done = RecordsRules.commit(failed, prune, written = true)
        assertEquals(setOf(0L), done.records!!.serials)
        assertNull(RecordsRules.pruneChange(done))
        assertEquals(setOf(WORK), done.profiles.removed)
    }

    @Test
    fun `読み込みの前に確認した集合にない番号の記録は、読み込んだ後の整理の対象になる`() {
        val work = AppId("jp.example.mail", "Inbox", WORK)
        val records = UserRecords(LearnedMemory.EMPTY, LaunchHistory(mapOf(work to LaunchRecord(1, NOW))))
        val confirmed = RecordsRules.profilesConfirmed(RecordsState(), setOf(0))
        assertNull(RecordsRules.pruneChange(confirmed))
        val loaded = RecordsRules.loaded(confirmed, records)
        assertEquals(setOf(WORK), loaded.profiles.pendingPrune)
        assertEquals(RecordsWrite(deleteSerials = setOf(WORK)), RecordsRules.pruneChange(loaded)!!.write)
    }

    @Test
    fun `読み込みでは消えたプロファイルの記録を受けず、その数を返す`() {
        val removed = RecordsRules.profilesConfirmed(RecordsRules.profilesConfirmed(ready(), setOf(0, WORK)), setOf(0))
        val imported =
            listOf(
                LearnedRecord(NormalizedText("てんき"), WEATHER, 3, NOW - DAY, rescued = true),
                LearnedRecord(NormalizedText("めーる"), AppId("jp.example.mail", "Inbox", WORK), 1, NOW, rescued = false),
            )
        val change = RecordsRules.change(removed, RecordsRequest.Import(imported, NOW))
        assertEquals(RecordsResult.Success(skipped = 1), change.result)
        val next = RecordsRules.commit(removed, change, written = true).records!!
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
        assertEquals(setOf(pair("てんき", WEATHER)), RecordsRules.change(state, RecordsRequest.Forget(NOW)).write!!.learnedDelete)
        assertNull(RecordsRules.change(state, RecordsRequest.Forget(NOW - DAY)).write)
        assertEquals(
            setOf(pair("てんき", WEATHER)),
            RecordsRules.change(state, RecordsRequest.Remove(pair("てんき", WEATHER))).write!!.learnedDelete,
        )
        assertNull(RecordsRules.change(state, RecordsRequest.Remove(pair("ちず", WEATHER))).write)
    }

    private fun apply(
        state: RecordsState,
        request: RecordsRequest,
    ): RecordsState = RecordsRules.commit(state, RecordsRules.change(state, request), written = true)

    private fun ready(records: UserRecords = UserRecords.EMPTY): RecordsState = RecordsRules.loaded(RecordsState(), records)

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
        val WEATHER = AppId("jp.example.weather", "Main", 0)
        val PAY = AppId("jp.example.pay", "Main", 0)

        fun pair(
            query: String,
            app: AppId,
        ) = LearnedPair(NormalizedText(query), app)
    }
}
