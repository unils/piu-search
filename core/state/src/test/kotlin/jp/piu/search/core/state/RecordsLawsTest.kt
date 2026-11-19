// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.text.NormalizedText
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 法則 6（記録は、DB に書けたときだけ変わる）を、乱数の頼みと書き込みの失敗の列で確かめる。
 *
 * 純粋な層では DB の代わりに、書き込みの中身を当てる表（[FakeDb]）を置き、成功した書き込みの後に表と写しが一致することを確かめる。種ごとに [STEPS] 個の出来事
 * （頼み・読み込みの成否・プロファイルの増減・確認の配達・整理）を作り、書き込みは [WRITE_FAILURE_RATE]% で失敗させる。
 *
 * プロファイルの確認は、問い合わせのたびに番号を増やして列に積み、順不同で配る（書き手の列に届く順は問い合わせた順と限らない）。
 * どの順で届いても、存在するプロファイルを削除済みにしないことを確かめる。期待値は、規則の出力ではなく頼みの中身から直に決める
 * （抑制した行の印・整理した番号の行の不在・古い取り消しで表が変わらないこと）。
 */
class RecordsLawsTest {
    @Test
    fun `法則 6 - 書き込みに失敗した頼みは、写しも DB も変えない`() {
        forAllSeeds("記録の法則", SEEDS) { random, log ->
            val db = FakeDb()
            // 回数が上限の救済の行。同じ時刻の後の選択でも行の値が変わらないので、取り消しの無効化を値の差だけに頼ると見落とす
            db.apply(
                RecordsWrite(
                    learnedPut =
                        listOf(
                            LearnedRecord(NormalizedText("てんき"), AppId(PACKAGES[0], "Main", 0), Int.MAX_VALUE, NOW, rescued = true),
                        ),
                ),
            )
            var state = RecordsState()
            val existing = INITIAL.toMutableSet()
            val created = INITIAL.toMutableSet()
            var seq = 0L
            val queued = ArrayList<Pair<Long, Set<Long>>>()
            repeat(STEPS) { step ->
                when (random.nextInt(12)) {
                    0 -> {
                        state =
                            if (random.nextInt(4) == 0) {
                                RecordsRules.loadFailed(state, "io")
                            } else {
                                RecordsRules.loaded(state, db.records(), db.serials())
                            }
                        log += "step $step: 読み込み → ${state.load::class.simpleName}"
                    }

                    1 -> {
                        // プロファイルの増減。消えたプロファイルは戻らず、番号は使い回されない
                        if (random.nextBoolean()) {
                            existing.filter { it != 0L }.randomOrNull(random)?.let { existing -= it }
                        } else {
                            (SERIALS - created).firstOrNull()?.let {
                                existing += it
                                created += it
                            }
                        }
                        log += "step $step: 増減 → $existing"
                    }

                    2 -> {
                        // 集合の通知: 今の集合を問い合わせて列に積む（配るのは後）
                        queued += ++seq to existing.toSet()
                        log += "step $step: 問い合わせ $seq → $existing"
                    }

                    3 -> {
                        if (queued.isNotEmpty()) {
                            val (n, seen) = queued.removeAt(random.nextInt(queued.size))
                            state = RecordsRules.profilesConfirmed(state, seen, n)
                            log += "step $step: 確認の配達 $n $seen"
                        }
                    }

                    4 -> {
                        RecordsRules.pruneChange(state)?.let { change ->
                            // 期待する番号は書き込みからでなく、整理の前の状態から取る
                            val targets = state.profiles.pendingPrune
                            state =
                                commit(random, db, state, change, log, "step $step: 整理") { written ->
                                    if (written) law(db.serials().none { it in targets }) { "整理した番号 $targets の行が表に残った" }
                                }
                        }
                    }

                    else -> {
                        val request = randomRequest(random, state, created)
                        // 書き手の手順: 確認済みでない番号があれば、問い合わせ直してから計算する（問い合わせは 1/4 で失敗し、最後の確認を保つ）
                        val asked = RecordsRules.unconfirmed(state, request)
                        val absent =
                            if (asked.isEmpty()) {
                                emptySet()
                            } else if (random.nextInt(4) > 0) {
                                state = RecordsRules.profilesConfirmed(state, existing.toSet(), ++seq, asked)
                                log += "step $step: 問い合わせ直し $seq $asked → $existing"
                                asked - existing
                            } else {
                                state = RecordsRules.profileQueryFailed(state, ++seq)
                                log += "step $step: 問い合わせ直し $seq の失敗"
                                emptySet()
                            }
                        val change = RecordsRules.change(state, request)
                        val puts =
                            change.write?.let { w ->
                                w.learnedPut.map { it.app.profileSerial } +
                                    w.launchPut.keys.map { it.profileSerial }
                            }
                        law(puts.orEmpty().none { it in absent }) { "問い合わせ直して不在と分かった番号に書く: $request" }
                        val before = state
                        val dbBefore = db.records()
                        state =
                            commit(random, db, state, change, log, "step $step: $request") { written ->
                                if (written) checkRequest(before, request, change, dbBefore, db)
                            }
                        if (request is RecordsRequest.Open && state !== before) checkOpen(before, state, request)
                    }
                }
                state.records?.let { records ->
                    law(db.records() == records) { "DB と写しが違う: db=${db.records()} 写し=$records" }
                }
                val removed = state.profiles.removed
                law(removed.none { it in existing }) { "存在するプロファイル ${removed.intersect(existing)} を削除済みにした" }
                law(state.profiles.pendingPrune.all { it in removed }) { "削除済みでない番号を整理する: ${state.profiles.pendingPrune}" }
                law(state.undo.values.none { it.notice.pair.app.profileSerial in removed }) { "消えたプロファイルの取り消しが残った" }
            }
        }
    }

    // 書けた頼みの、頼みの中身から直に決まる結果
    private fun checkRequest(
        before: RecordsState,
        request: RecordsRequest,
        change: RecordsChange,
        dbBefore: UserRecords,
        db: FakeDb,
    ) {
        if (change.result !is RecordsResult.Success) return
        when (request) {
            is RecordsRequest.Suppress -> {
                if (before.profiles.accepts(request.pair.app.profileSerial)) {
                    val row = db.records().learned.records[request.pair]
                    law(row?.suppressed == true) { "抑制した ${request.pair} の行に印がない: $row" }
                }
            }

            is RecordsRequest.Undo -> {
                if (!before.canUndo(request.noticeId)) {
                    law(change.write == null && db.records() == dbBefore) { "古い取り消し ${request.noticeId} で表が変わった" }
                }
            }

            else -> {}
        }
    }

    // 開いた頼みを当てた後: 同じ組を後で保存した取り消しは、行の値が変わらなくても無効になる。
    // 使った学習のキー（救済になったことがあり、印のない組）は、最後に使った時刻が進む
    private fun checkOpen(
        before: RecordsState,
        after: RecordsState,
        request: RecordsRequest.Open,
    ) {
        val accepts = before.profiles::accepts
        val decision = request.decision
        val saved = (listOfNotNull(decision.selection, decision.used) + decision.rescues).filter { accepts(it.app.profileSerial) }.toSet()
        for ((id, material) in before.undo) {
            if (material.notice.pair in saved) law(!after.canUndo(id)) { "後で同じ組を保存したのに、取り消し $id が有効なまま: $request" }
        }
        val used = decision.used?.takeIf { accepts(it.app.profileSerial) } ?: return
        val old =
            before.records
                ?.learned
                ?.records
                ?.get(used) ?: return
        if (!old.rescued || old.suppressed) return
        val now =
            after.records!!
                .learned.records
                .getValue(used)
        law(now.lastUsedAt >= request.at) { "使った学習のキー $used の時刻が進まない: $request" }
    }

    @Suppress("LongParameterList")
    private fun commit(
        random: Random,
        db: FakeDb,
        state: RecordsState,
        change: RecordsChange,
        log: MutableList<String>,
        label: String,
        after: (Boolean) -> Unit = {},
    ): RecordsState {
        val write = change.write
        if (change.result is RecordsResult.Failure) {
            law(write == null && change.next == state) { "読めていないのに計算した: $label" }
        }
        val removed = state.profiles.removed
        if (write != null) {
            val puts = write.learnedPut.map { it.app.profileSerial } + write.launchPut.keys.map { it.profileSerial }
            law(puts.none { it in removed }) { "消えたプロファイルの行を書く: $label $write" }
        }
        val written = write == null || random.nextInt(100) >= WRITE_FAILURE_RATE
        if (write != null && written) db.apply(write)
        val next = RecordsRules.commit(state, change, written)
        log += "$label → 書き込み=${write != null} 成功=$written"
        after(written)
        if (!written) {
            // 法則 6: 失敗した頼みは写しを変えない。DB の側（トランザクションが途中で失敗したら何も残らないこと）は、
            // この表では確かめられない（失敗した回は当てないだけなので）。Room の故障の試験で確かめる
            law(next == state) { "法則 6: 書けなかったのに状態が変わった: $label" }
            law(RecordsRules.resultOf(change, written) == RecordsResult.Failure(RecordsFailure.WRITE_FAILED)) { "失敗を返さない: $label" }
        }
        return next
    }

    // 頼みが対象にするのは、できたことのあるプロファイルだけ（まだないプロファイルのアプリは選べない）
    private fun randomRequest(
        random: Random,
        state: RecordsState,
        created: Set<Long>,
    ): RecordsRequest {
        fun randomApp(): AppId = AppId(PACKAGES.random(random), "Main", created.random(random))

        fun randomPair(): LearnedPair = LearnedPair(NormalizedText(QUERIES.random(random)), randomApp())
        // 同じ時刻が続く場面を作るため、半分は NOW にする
        val at = if (random.nextBoolean()) NOW else NOW + random.nextLong(0, 400) * DAY_HOURS
        return when (random.nextInt(8)) {
            0, 1 -> {
                val rescues = List(random.nextInt(3)) { randomPair() }
                val selection = if (random.nextBoolean()) randomPair() else null
                val used = if (random.nextBoolean()) randomPair() else null
                RecordsRequest.Open(LearningDecision(selection, rescues, used), randomApp().takeIf { random.nextBoolean() }, at)
            }

            2 -> {
                RecordsRequest.Suppress(randomPair(), at)
            }

            3 -> {
                RecordsRequest.Undo(random.nextLong(1, state.nextNoticeId + 1))
            }

            4 -> {
                RecordsRequest.Remove(randomPair())
            }

            5 -> {
                RecordsRequest.Clear(random.nextBoolean(), random.nextBoolean())
            }

            6 -> {
                RecordsRequest.Import(
                    List(random.nextInt(3)) {
                        val pair = randomPair()
                        LearnedRecord(pair.query, pair.app, random.nextInt(1, 5), at, random.nextBoolean(), random.nextInt(5) == 0)
                    },
                    at,
                )
            }

            else -> {
                RecordsRequest.Forget(at)
            }
        }
    }

    /** 書き込みの中身を当てる表。DB の代わり。 */
    private class FakeDb {
        private val learned = LinkedHashMap<LearnedPair, LearnedRecord>()
        private val launches = LinkedHashMap<AppId, LaunchRecord>()

        fun apply(write: RecordsWrite) {
            if (write.clearLearned) learned.clear()
            if (write.clearLaunches) launches.clear()
            learned.keys.removeAll { it.app.profileSerial in write.deleteSerials }
            launches.keys.removeAll { it.profileSerial in write.deleteSerials }
            write.learnedDelete.forEach { learned.remove(it) }
            write.learnedPut.forEach { learned[it.pair] = it }
            launches.putAll(write.launchPut)
        }

        fun serials(): Set<Long> = learned.keys.mapTo(HashSet()) { it.app.profileSerial } + launches.keys.map { it.profileSerial }

        fun records(): UserRecords = UserRecords(LearnedMemory(learned.values.toList()), LaunchHistory(launches.toMap()))
    }

    private companion object {
        const val SEEDS = 300
        const val STEPS = 40
        const val WRITE_FAILURE_RATE = 20
        const val NOW = 1_800_000_000_000L
        const val DAY_HOURS = 60L * 60 * 1000 * 12
        val INITIAL = setOf(0L, 10L, 11L)
        val SERIALS = listOf(0L, 10L, 11L, 12L, 13L)
        val QUERIES = listOf("てんき", "おかね", "ちず", "めーる", "て")
        val PACKAGES = listOf("jp.example.weather", "jp.example.pay", "jp.example.mail")
    }
}
