// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 学習の記録（R1・R2 の結果の積み方、R4 の印、R5 の忘却）と、照合での効き方（R3・R4）。 */
class LearnedMemoryTest {
    @Test
    fun `記録すると回数が増え、救済になったことは残る`() {
        val memory =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(null, listOf(pair("てんき", WEATHER))), NOW)
                .recorded(LearningDecision(pair("てんき", WEATHER), emptyList()), NOW + DAY)
        val record = memory.records.getValue(pair("てんき", WEATHER))
        assertEquals(2, record.count)
        assertEquals(NOW + DAY, record.lastUsedAt)
        assertTrue(record.rescued)
        assertFalse(record.suppressed)
    }

    @Test
    fun `1 つのセッションで選択と救済の両方になっても 1 回と数える`() {
        val both = LearningDecision(pair("てんき", WEATHER), listOf(pair("てんき", WEATHER)))
        val record =
            LearnedMemory.EMPTY
                .recorded(both, NOW)
                .records
                .getValue(pair("てんき", WEATHER))
        assertEquals(1, record.count)
        assertTrue(record.rescued)
    }

    @Test
    fun `救済の語は学習のキーになり、打っている途中でも前方一致で出る`() {
        val engine = engineWith(LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(pair("おかね", PAYPAY))), NOW))
        val exact = engine.search(SearchQuery.of("おかね")).single()
        assertEquals("PayPay", exact.label)
        assertEquals(MatchStage.EXACT to SearchKeyKind.LEARNED, exact.reason.stage to exact.reason.keyKind)
        assertEquals(
            MatchStage.PREFIX,
            engine
                .search(SearchQuery.of("おか"))
                .single()
                .reason.stage,
        )
        // 学習のキーは段階 1〜3 にだけ加わり、部分一致には使わない
        assertEquals(emptyList<SearchResult>(), engine.search(SearchQuery.of("かね")))
    }

    @Test
    fun `かなの学習の語はローマ字でも出て、出た理由は学習した語のまま`() {
        val engine = engineWith(LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(pair("おかね", PAYPAY))), NOW))
        val exact = engine.search(SearchQuery.of("okane")).single()
        assertEquals("PayPay", exact.label)
        assertEquals(MatchStage.EXACT to SearchKeyKind.LEARNED, exact.reason.stage to exact.reason.keyKind)
        assertEquals("おかね", exact.reason.matchedText.value)
        // 打っている途中のローマ字（IME の未確定のかなと英字が混ざった形を含む）でも前方一致で出る
        assertEquals(
            MatchStage.PREFIX,
            engine
                .search(SearchQuery.of("oka"))
                .single()
                .reason.stage,
        )
        assertEquals(
            MatchStage.PREFIX,
            engine
                .search(SearchQuery.of("おかn"))
                .single()
                .reason.stage,
        )
        // 前方一致の長さの制限と、部分一致に使わないことはかなと同じ
        assertEquals(emptyList<SearchResult>(), engine.search(SearchQuery.of("ok")))
        assertEquals(emptyList<SearchResult>(), engine.search(SearchQuery.of("kane")))
    }

    @Test
    fun `ローマ字にできない学習の語と英字の学習の語は、ローマ字の表に置かない`() {
        val memory =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(null, listOf(pair("お金", PAYPAY))), NOW)
                .recorded(LearningDecision(null, listOf(pair("pp", PAYPAY))), NOW)
        val index = BASE.withLearning(memory)
        assertEquals(0, (0 until index.romajiKeys.size).count { index.romajiKeys.kind[it] == SearchKeyKind.LEARNED })
        assertEquals("PayPay", labels(engineWith(memory), "pp").first())
    }

    @Test
    fun `学習のキーの前方一致は、検索語がキーの半分以上の長さのときだけ`() {
        val memory =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(null, listOf(pair("やふー", WEATHER))), NOW)
                .recorded(LearningDecision(null, listOf(pair("ぺいぺいふりま", MAPS))), NOW)
        val engine = engineWith(memory)
        // 「やふ」は「やふー」の 2/3 なので当たる（learning.md の R3 の例）
        assertTrue(labels(engine, "やふ").contains("ウェザーニュース"))
        // 「ぺい」は 2/7 で当たらず、「ぺいぺい」（4/7）なら当たる
        assertFalse(labels(engine, "ぺい").contains("Google マップ"))
        assertTrue(labels(engine, "ぺいぺい").contains("Google マップ"))
    }

    @Test
    fun `選択だけの記録はキーにならず、もともと当たるアプリを上げるだけ`() {
        val memory = LearnedMemory.EMPTY.recorded(LearningDecision(pair("おかね", PAYPAY), emptyList()), NOW)
        assertEquals(emptyList<SearchResult>(), engineWith(memory).search(SearchQuery.of("おかね")))
    }

    @Test
    fun `選択の加点は記録の検索語が検索語で始まるときに効き、上限がある`() {
        val config = LearningConfig.DEFAULT
        var memory = LearnedMemory.EMPTY
        repeat(2) { memory = memory.recorded(LearningDecision(pair("まっぷ", YAHOO_MAP), emptyList()), NOW) }
        assertEquals(2 * config.boostPerUse, memory.learnedBoost(SearchQuery.of("まっ"), YAHOO_MAP))
        assertEquals(2 * config.boostPerUse, memory.learnedBoost(SearchQuery.of("まっぷ"), YAHOO_MAP))
        // 記録の q が q' で始まらない（q' のほうが長い・別の語）なら加点しない
        assertEquals(0, memory.learnedBoost(SearchQuery.of("まっぷす"), YAHOO_MAP))
        assertEquals(0, memory.learnedBoost(SearchQuery.of("ちず"), YAHOO_MAP))
        assertEquals(0, memory.learnedBoost(SearchQuery.of(""), YAHOO_MAP))
        assertEquals(0, memory.learnedBoost(SearchQuery.of("まっぷ"), MAPS))
        repeat(20) { memory = memory.recorded(LearningDecision(pair("まっぷ", YAHOO_MAP), emptyList()), NOW) }
        assertEquals(config.maxBoost, memory.learnedBoost(SearchQuery.of("まっぷ"), YAHOO_MAP))
    }

    @Test
    fun `選択の加点で同じ段階の中の順位が上がる`() {
        // 「まっぷ」: Google マップ と Yahoo!マップ はどちらも語の頭からの一致
        val before = labels(engineWith(LearnedMemory.EMPTY), "まっぷ")
        assertEquals(listOf("Google マップ", "Yahoo!マップ"), before.take(2))
        val memory = LearnedMemory.EMPTY.recorded(LearningDecision(pair("まっぷ", YAHOO_MAP), emptyList()), NOW)
        assertEquals(listOf("Yahoo!マップ", "Google マップ"), labels(engineWith(memory), "まっぷ").take(2))
    }

    @Test
    fun `段階を上げる幅は、回数から 1 回分を引き、救済なら 1 段足したもの`() {
        fun lift(
            selections: Int,
            rescued: Boolean = false,
        ): Int {
            var memory = LearnedMemory.EMPTY
            if (rescued) memory = memory.recorded(LearningDecision(null, listOf(pair("めーる", MAIL))), NOW)
            repeat(selections) { memory = memory.recorded(LearningDecision(pair("めーる", MAIL), emptyList()), NOW) }
            return memory.stageLift(SearchQuery.of("めーる"), MAIL, NOW)
        }
        // 1 回の選択（誤って押したかもしれない）では段階をまたがない
        assertEquals(0, lift(0))
        assertEquals(0, lift(1))
        assertEquals(1, lift(2))
        assertEquals(2, lift(3))
        // 救済は、見えなかったアプリを自分で探して開いた記録なので、1 回で 1 段上げる
        assertEquals(1, lift(0, rescued = true))
        assertEquals(2, lift(1, rescued = true))
        // 上げる幅は段階の数で頭を打つ
        assertEquals(MatchStage.entries.size - 1, lift(100))
    }

    @Test
    fun `段階を上げるのは、検索語が記録の語と同じか、英字で最後の 1 音を打ちかけた形のときだけ`() {
        var memory = LearnedMemory.EMPTY
        repeat(3) { memory = memory.recorded(LearningDecision(pair("めーる", MAIL), emptyList()), NOW) }

        fun lift(raw: String) = memory.stageLift(SearchQuery.of(raw), MAIL, NOW)
        assertEquals(2, lift("めーる"))
        assertEquals(2, lift("めーr"))
        assertEquals(2, lift("meru"))
        assertEquals(2, lift("mer"))
        // 最後の 1 音に入る前の形・かなだけの途中の形・長い語・別の語では上げない（加点は同じ段階の中でだけ効く）
        assertEquals(0, lift("me"))
        assertEquals(0, lift("めー"))
        assertEquals(0, lift("めーるあぷり"))
        assertEquals(0, lift("めもり"))
        assertEquals(0, lift(""))
        assertEquals(0, memory.stageLift(SearchQuery.of("めーる"), MAPS, NOW))
    }

    @Test
    fun `30 日使わなかった記録は、段階を上げる幅を 1 段減らす`() {
        var memory = LearnedMemory.EMPTY
        repeat(3) { memory = memory.recorded(LearningDecision(pair("めーる", MAIL), emptyList()), NOW) }
        assertEquals(2, memory.stageLift(SearchQuery.of("めーる"), MAIL, NOW + 29 * DAY))
        assertEquals(1, memory.stageLift(SearchQuery.of("めーる"), MAIL, NOW + 30 * DAY))
        // 1 回の救済は、古くなれば段階をまたがない
        val rescued = LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(pair("めーる", MAIL))), NOW)
        assertEquals(0, rescued.stageLift(SearchQuery.of("めーる"), MAIL, NOW + 30 * DAY))
    }

    @Test
    fun `選び続けたアプリは段階をまたいで上がり、元の段階の差が大きいほど多くの選択が要る`() {
        // 「めーる」: メルカリ（ローマ字の緩い形の前方一致、段階 2）がメールのアプリ（ジャンル、段階 4）より上
        fun top(times: Int): String {
            var memory = LearnedMemory.EMPTY
            repeat(times) { memory = memory.recorded(LearningDecision(pair("めーる", MAIL), emptyList()), NOW) }
            return labels(engineWith(memory), "めーる").first()
        }
        assertEquals("メルカリ", top(0))
        assertEquals("メルカリ", top(2))
        assertEquals("Gmail", top(3))

        // 「ちず」: 地図（読みの完全一致、段階 1）とジャンルの Google マップ（段階 4）は差が 3 段
        fun topChizu(times: Int): String {
            var memory = LearnedMemory.EMPTY
            repeat(times) { memory = memory.recorded(LearningDecision(pair("ちず", MAPS), emptyList()), NOW) }
            return labels(engineWith(memory), "ちず").first()
        }
        assertEquals("地図", topChizu(3))
        assertEquals("Google マップ", topChizu(4))
    }

    @Test
    fun `段階を上げたアプリの出た理由は元の当たり方のまま`() {
        var memory = LearnedMemory.EMPTY
        repeat(3) { memory = memory.recorded(LearningDecision(pair("めーる", MAIL), emptyList()), NOW) }
        val first = engineWith(memory).search(SearchQuery.of("めーr")).first()
        assertEquals("Gmail", first.label)
        assertEquals(MatchStage.GENRE, first.reason.stage)
        assertEquals(2, first.stageLift)
    }

    @Test
    fun `印を付けた組は、選び続けた記録があっても段階を上げず、出さない`() {
        var memory = LearnedMemory.EMPTY
        repeat(5) { memory = memory.recorded(LearningDecision(pair("めーる", MAIL), emptyList()), NOW) }
        val marked = memory.suppressed(pair("めーる", MAIL), NOW)
        assertEquals(0, marked.stageLift(SearchQuery.of("めーる"), MAIL, NOW))
        assertFalse(labels(engineWith(marked), "めーる").contains("Gmail"))
        // 印はその検索語の厳密形だけに効く。打ちかけの形では、印のない記録はないので上げない
        assertEquals(0, marked.stageLift(SearchQuery.of("めーr"), MAIL, NOW))
        assertEquals("メルカリ", labels(engineWith(marked), "めーr").first())
    }

    @Test
    fun `印を付けた組は、名前で一致する場合もその検索語では出さない`() {
        val memory = LearnedMemory.EMPTY.suppressed(pair("ちず", CHIZU), NOW)
        val engine = engineWith(memory)
        assertFalse(labels(engine, "ちず").contains("地図"))
        // その検索語（厳密形）だけ。途中の形や、表記の違う検索語（漢字の「地図」）では出る
        assertTrue(labels(engine, "ち").contains("地図"))
        assertTrue(labels(engine, "地図").contains("地図"))
        // ほかのアプリは出る
        assertTrue(labels(engine, "ちず").contains("Google マップ"))
    }

    @Test
    fun `印を付けた組は学習のキーを消し、加点もしない`() {
        val memory =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(pair("おかね", PAYPAY), listOf(pair("おかね", PAYPAY))), NOW)
                .recorded(LearningDecision(pair("おかねかんり", PAYPAY), listOf(pair("おかねかんり", PAYPAY))), NOW)
                .suppressed(pair("おかね", PAYPAY), NOW)
        val engine = engineWith(memory)
        assertEquals(emptyList<SearchResult>(), engine.search(SearchQuery.of("おかね")))
        // 印のない別の組（おかねかんり）は残り、「おかねか」ではそちらで出る
        val partial = engine.search(SearchQuery.of("おかねか")).single()
        assertEquals("おかねかんり", partial.reason.matchedText.value)
        assertEquals(LearningConfig.DEFAULT.boostPerUse, memory.learnedBoost(SearchQuery.of("おか"), PAYPAY))
        // 印のある組だけが当たる検索語では加点しない
        val onlySuppressed =
            LearnedMemory.EMPTY
                .recorded(
                    LearningDecision(pair("まっぷ", MAPS), emptyList()),
                    NOW,
                ).suppressed(pair("まっぷ", MAPS), NOW)
        assertEquals(0, onlySuppressed.learnedBoost(SearchQuery.of("まっぷ"), MAPS))
    }

    @Test
    fun `印を付けた組は学習で戻さず、消せば印も消える`() {
        val marked = LearnedMemory.EMPTY.suppressed(pair("おかね", PAYPAY), NOW)
        val again = marked.recorded(LearningDecision(null, listOf(pair("おかね", PAYPAY))), NOW + DAY)
        assertEquals(marked.records, again.records)
        val removed = again.removed(pair("おかね", PAYPAY))
        assertTrue(removed.records.isEmpty())
        assertSame(removed, removed.removed(pair("おかね", PAYPAY)))
    }

    @Test
    fun `救済を取り消すと記録する前に戻り、同じ組の選択の分は残る`() {
        val rescue = pair("てんき", WEATHER)
        val before = LearnedMemory.EMPTY.recorded(LearningDecision(rescue, emptyList()), NOW)
        val previous = before.records[rescue]
        val after = before.recorded(LearningDecision(rescue, listOf(rescue)), NOW + DAY)
        val undone = after.rescueUndone(rescue, previous, selection = rescue, at = NOW + DAY)
        assertEquals(LearnedRecord(rescue.query, WEATHER, 2, NOW + DAY, rescued = false), undone.records[rescue])
        // 前に記録がなく、選択でもなければ、記録ごと消える
        val okane = pair("おかね", PAYPAY)
        val onlyRescue = LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(okane)), NOW)
        assertTrue(onlyRescue.rescueUndone(okane, null, selection = null, at = NOW).records.isEmpty())
        // 取り消す前に印を付けていたら、印を残す
        val marked = onlyRescue.suppressed(okane, NOW)
        assertSame(marked, marked.rescueUndone(okane, null, selection = null, at = NOW))
    }

    @Test
    fun `180 日使わなかった記録は消え、印の付いた組は残る（R5）`() {
        val memory =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(pair("ちず", MAPS), emptyList()), NOW)
                .recorded(LearningDecision(pair("まっぷ", MAPS), emptyList()), NOW + 10 * DAY)
                .suppressed(pair("ちず", CHIZU), NOW)
        val limit = LearningConfig.DEFAULT.forgetAfterMillis
        assertSame(memory, memory.forgotten(NOW + limit - 1))
        val forgotten = memory.forgotten(NOW + limit)
        assertEquals(setOf(pair("まっぷ", MAPS), pair("ちず", CHIZU)), forgotten.records.keys)
        assertTrue(forgotten.isExpired(forgotten.records.getValue(pair("ちず", CHIZU)), NOW + limit))
    }

    @Test
    fun `学習を重ね直すと前の学習を置き換え、プロファイルで絞っても印は残る`() {
        val first = LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(pair("おかね", PAYPAY))), NOW)
        val second =
            LearnedMemory.EMPTY
                .recorded(
                    LearningDecision(null, listOf(pair("しはらい", PAYPAY))),
                    NOW,
                ).suppressed(pair("ちず", CHIZU), NOW)
        val index = BASE.withLearning(first).withLearning(second)
        val engine = SearchEngine(index.restrictedTo(setOf(0L)))
        assertEquals(emptyList<SearchResult>(), engine.search(SearchQuery.of("おかね")))
        assertEquals(listOf("PayPay"), engine.search(SearchQuery.of("しはらい")).map { it.label })
        assertFalse(engine.search(SearchQuery.of("ちず")).any { it.label == "地図" })
        assertEquals(
            1,
            index.apps
                .single { it.id == PAYPAY }
                .keys
                .count { it.kind == SearchKeyKind.LEARNED },
        )
    }

    @Test
    fun `索引にないアプリの記録は使わず、記録には残る`() {
        val gone = AppId("io.example.gone", "Main", 0)
        val memory = LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(pair("おかね", gone))), NOW).suppressed(pair("ちず", gone), NOW)
        assertEquals(emptyList<SearchResult>(), SearchEngine(BASE.withLearning(memory)).search(SearchQuery.of("おかね")))
        assertEquals(2, memory.records.size)
    }

    @Test
    fun `読み込みは今の学習と合わせ、回数は大きいほうを取り、印を残す`() {
        val current =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(pair("ちず", MAPS), emptyList()), NOW)
                .recorded(LearningDecision(pair("ちず", MAPS), emptyList()), NOW)
                .recorded(LearningDecision(pair("てんき", WEATHER), emptyList()), NOW - DAY)
                .suppressed(pair("ちず", CHIZU), NOW)
        val imported =
            listOf(
                LearnedRecord(NormalizedText("ちず"), MAPS, 1, NOW - DAY, rescued = true),
                LearnedRecord(NormalizedText("ちず"), CHIZU, 2, NOW - DAY, rescued = false),
                LearnedRecord(NormalizedText("おかね"), PAYPAY, 3, NOW - DAY, rescued = true, suppressed = true),
            )
        val at = NOW + DAY
        val merged = current.merged(imported, at).records

        // 回数は大きいほう（今の 2 回）。救済はファイルの側から残る
        assertEquals(LearnedRecord(NormalizedText("ちず"), MAPS, 2, at, rescued = true), merged.getValue(pair("ちず", MAPS)))
        // 今の印も、読み込んだ印も残る
        assertEquals(
            LearnedRecord(NormalizedText("ちず"), CHIZU, 2, at, rescued = false, suppressed = true),
            merged.getValue(pair("ちず", CHIZU)),
        )
        assertEquals(
            LearnedRecord(NormalizedText("おかね"), PAYPAY, 3, at, rescued = true, suppressed = true),
            merged.getValue(pair("おかね", PAYPAY)),
        )
        // ファイルにない記録は変えない
        assertEquals(current.records.getValue(pair("てんき", WEATHER)), merged.getValue(pair("てんき", WEATHER)))
        assertSame(current, current.merged(emptyList(), at))
    }

    @Test
    fun `同じファイルを 2 回読み込んでも回数は増えない`() {
        val imported = listOf(LearnedRecord(NormalizedText("ちず"), MAPS, 3, NOW, rescued = false))
        val once = LearnedMemory.EMPTY.merged(imported, NOW)
        val twice = once.merged(imported, NOW + DAY)
        assertEquals(3, once.records.getValue(pair("ちず", MAPS)).count)
        assertEquals(3, twice.records.getValue(pair("ちず", MAPS)).count)
    }

    @Test
    fun `古いファイルを読み込んでも、すぐには忘れない`() {
        val limit = LearningConfig.DEFAULT.forgetAfterMillis
        // 1 年前に書き出したファイル。記録の最後に使った時刻はさらに前
        val old = listOf(LearnedRecord(NormalizedText("ちず"), MAPS, 3, NOW - limit - 200 * DAY, rescued = false))
        val merged = LearnedMemory.EMPTY.merged(old, NOW)
        assertEquals(NOW, merged.records.getValue(pair("ちず", MAPS)).lastUsedAt)
        assertSame(merged, merged.forgotten(NOW + DAY))
        assertEquals(emptyMap<LearnedPair, LearnedRecord>(), merged.forgotten(NOW + limit).records)
    }

    @Test
    fun `読み込みで、今の記録の最後に使った時刻を戻さない`() {
        // 端末の時計が戻ったときなど、読み込んだ時刻が今の記録より前でも、新しいほうを残す
        val current = LearnedMemory.EMPTY.recorded(LearningDecision(pair("ちず", MAPS), emptyList()), NOW + DAY)
        val merged = current.merged(listOf(LearnedRecord(NormalizedText("ちず"), MAPS, 1, NOW, rescued = false)), NOW)
        assertEquals(NOW + DAY, merged.records.getValue(pair("ちず", MAPS)).lastUsedAt)
    }

    @Test
    fun `全消去で印も含めてすべて消える`() {
        val memory =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(pair("ちず", MAPS), emptyList()), NOW)
                .suppressed(pair("ちず", CHIZU), NOW)
        assertEquals(emptyMap<LearnedPair, LearnedRecord>(), memory.cleared().records)
        assertSame(LearnedMemory.EMPTY, LearnedMemory.EMPTY.cleared())
    }

    @Test
    fun `索引から表示名を引け、消えたアプリは null`() {
        assertEquals("PayPay", BASE.labelOf(PAYPAY))
        assertEquals(null, BASE.labelOf(AppId("io.example.gone", "Main", 0)))
    }

    @Test
    fun `64 コードポイントを超える語は選択も救済も記録しない`() {
        for (word in listOf("あ", "𠮷")) {
            val allowed = pair(word.repeat(64), PAYPAY)
            val tooLong = pair(word.repeat(65), PAYPAY)
            val memory = LearnedMemory.EMPTY.recorded(LearningDecision(tooLong, listOf(allowed, tooLong)), NOW)
            assertEquals(setOf(allowed), memory.records.keys)
        }
    }

    @Test
    fun `読み戻した古い長すぎる語を索引に重ねない`() {
        val longPair = pair("あ".repeat(10_000), PAYPAY)
        val memory = LearnedMemory(listOf(LearnedRecord(longPair.query, PAYPAY, 1, NOW, rescued = true)))
        assertFalse(engineWith(memory).search(SearchQuery.of(longPair.query.value)).any { it.reason.keyKind == SearchKeyKind.LEARNED })
    }

    @Test
    fun `学習の回数は最大値で止まり時刻は更新する`() {
        val learned = pair("おかね", PAYPAY)
        for (count in listOf(Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            val memory = LearnedMemory(listOf(LearnedRecord(learned.query, PAYPAY, count, NOW, rescued = true)))
            val next = memory.recorded(LearningDecision(learned, listOf(learned)), NOW + DAY).records.getValue(learned)
            assertEquals(Int.MAX_VALUE, next.count)
            assertEquals(NOW + DAY, next.lastUsedAt)
        }
    }

    @Test
    fun `読み込める最大回数でも加点の掛け算はあふれない`() {
        for (count in listOf(500_000_000, Int.MAX_VALUE)) {
            val memory = LearnedMemory(listOf(LearnedRecord(NormalizedText("おかね"), PAYPAY, count, NOW, rescued = true)))
            assertEquals(LearningConfig.DEFAULT.maxBoost, memory.learnedBoost(SearchQuery.of("おかね"), PAYPAY))
        }
    }

    @Test
    fun `学習のキーを毎日使えば 180 日後も残り、回数は入力した語の選択だけ増える`() {
        val learned = pair("おかね", PAYPAY)
        val prefix = pair("おか", PAYPAY)
        var memory = LearnedMemory.EMPTY.recorded(LearningDecision(null, listOf(learned)), NOW)
        for (day in 1..181) {
            memory =
                memory
                    .forgotten(NOW + day * DAY)
                    .recorded(LearningDecision(prefix, emptyList(), used = learned), NOW + day * DAY)
        }
        assertEquals(1, memory.records.getValue(learned).count)
        assertEquals(NOW + 181 * DAY, memory.records.getValue(learned).lastUsedAt)
        assertEquals(181, memory.records.getValue(prefix).count)
        // その後に 180 日使わなければ、通常どおり消える
        assertTrue(memory.forgotten(NOW + 361 * DAY).records.isEmpty())
    }

    @Test
    fun `学習の使用時刻だけの更新は印や別のプロファイルの記録を変えず、時計も戻さない`() {
        val learned = pair("おかね", PAYPAY)
        val marked = pair("しはらい", PAYPAY)
        val otherProfile = pair("おかね", PAYPAY.copy(profileSerial = 10))
        val selectionOnly = pair("ぺい", PAYPAY)
        val before =
            LearnedMemory.EMPTY
                .recorded(LearningDecision(selectionOnly, listOf(learned, marked, otherProfile)), NOW)
                .suppressed(marked, NOW)
        val after = before.recorded(LearningDecision(null, emptyList(), used = learned), NOW + DAY)
        assertEquals(1, after.records.getValue(learned).count)
        assertEquals(NOW + DAY, after.records.getValue(learned).lastUsedAt)
        for (pair in listOf(marked, otherProfile, selectionOnly)) assertEquals(before.records[pair], after.records[pair])
        for (pair in listOf(learned, marked, selectionOnly)) {
            assertEquals(after.records, after.recorded(LearningDecision(null, emptyList(), used = pair), NOW - DAY).records)
        }
        assertEquals(after.records, after.recorded(LearningDecision(null, emptyList(), used = marked), NOW + 2 * DAY).records)
    }

    private fun engineWith(memory: LearnedMemory): SearchEngine =
        SearchEngine(BASE.withLearning(memory), signals = LaunchHistory.EMPTY.asRankingSignals(NOW, memory))

    private fun labels(
        engine: SearchEngine,
        raw: String,
    ): List<String> =
        engine
            .search(SearchQuery.of(raw).let { if (it.needsReading) it.withReading(TestAnalyzers.kuromoji) else it })
            .map { it.label }

    private companion object {
        const val DAY = LearningConfig.DAY_MILLIS
        const val NOW = 1_000L * DAY
        val PAYPAY = AppId("io.example.paypay", "Main", 0)
        val WEATHER = AppId("io.example.weather", "Main", 0)
        val MAPS = AppId("io.example.maps", "Main", 0)
        val YAHOO_MAP = AppId("io.example.yahoomap", "Main", 0)
        val CHIZU = AppId("io.example.chizu", "Main", 0)
        val MAIL = AppId("io.example.gmail", "Main", 0)
        val MERCARI = AppId("io.example.mercari", "Main", 0)

        val BASE: SearchIndex by lazy {
            SearchIndex.build(
                listOf(
                    AppEntry(PAYPAY, "PayPay"),
                    AppEntry(WEATHER, "ウェザーニュース"),
                    AppEntry(MAPS, "Google マップ", genres = listOf(GenreId("maps"))),
                    AppEntry(YAHOO_MAP, "Yahoo!マップ", genres = listOf(GenreId("maps"))),
                    AppEntry(CHIZU, "地図"),
                    AppEntry(MAIL, "Gmail", genres = listOf(GenreId("email"))),
                    AppEntry(MERCARI, "メルカリ"),
                ),
                TestAnalyzers.generator,
            )
        }

        fun pair(
            query: String,
            app: AppId,
        ) = LearnedPair(NormalizedText(query), app)
    }
}
