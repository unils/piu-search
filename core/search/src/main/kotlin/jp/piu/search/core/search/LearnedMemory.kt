// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.RomajiConverter
import jp.piu.search.core.text.RomajiMode
import jp.piu.search.core.text.containsLatin

/**
 * 学習した組 1 つの記録（learning.md の「何を覚えるか」）。
 *
 * 選択と救済を別の行にせず、組ごとに 1 行にまとめる。同じ組が選択にも救済にもなる（「てんき」の例）ので、
 * 救済になったことがあるかを [rescued] で持つ。
 *
 * @property query 検索語の厳密形
 * @property app アプリ
 * @property count 記録した回数（1 つのセッションで選択と救済の両方になっても 1 回）
 * @property lastUsedAt 最後に記録したか、学習のキーで当たったアプリを開いた時刻（エポックからのミリ秒）。R5 の忘却に使う
 * @property rescued 救済になったことがあるか。あれば [query] がアプリの学習のキーになる（R3）
 * @property suppressed 「この語では出さない」の印（R4）。印があれば、この検索語ではアプリを出さず、忘却もしない
 */
public data class LearnedRecord(
    val query: NormalizedText,
    val app: AppId,
    val count: Int,
    val lastUsedAt: Long,
    val rescued: Boolean,
    val suppressed: Boolean = false,
) {
    /** 組。 */
    val pair: LearnedPair get() = LearnedPair(query, app)
}

/**
 * 学習の記録の全体（変更しない）。[LaunchHistory] と同じく、変えたら新しいものを作って差し替える。
 *
 * 照合への効き方（R3・R4）は 2 つに分ける:
 *
 * - 学習のキーと印: [SearchIndex.withLearning] で索引に重ねる（照合の中で平たい配列として比べるため）
 * - 加点: [learnedBoost]。[LaunchHistory.asRankingSignals] から照合に渡す
 * - 段階を上げる幅: [stageLift]（R6）。加点と同じく照合に渡す
 *
 * 記録は索引の表とつながず、アプリを識別子の組で持つ。索引にない（消えた）アプリの記録も残る。
 *
 * @property records 組 → 記録
 * @property config 加点と忘却の数値（仮）
 */
public class LearnedMemory(
    records: Collection<LearnedRecord>,
    private val config: LearningConfig = LearningConfig.DEFAULT,
) {
    /** 組 → 記録。 */
    public val records: Map<LearnedPair, LearnedRecord> = records.filter { LearningLimits.accepts(it.query) }.associateBy { it.pair }

    // 照合の中で結果のアプリごとに引くので、アプリごとにまとめておく
    private val activeByApp: Map<AppId, List<LearnedRecord>> =
        this.records.values
            .filterNot { it.suppressed }
            .groupBy { it.app }

    // 段階を上げる判定（R6）で比べる記録の語のローマ字。照合の中で毎回変換しないよう、初めて使うときに作る
    private val liftKeysByApp: Map<AppId, List<LiftKey>> by lazy {
        activeByApp.mapValues { (_, list) -> list.map(::LiftKey) }
    }

    /**
     * [decision] を時刻 [at] に記録したもの。使用した学習のキーは時刻だけ更新する。長すぎる語と印のある組は変えない（ユーザーが出さないと決めた組を、学習で戻さない）。
     */
    public fun recorded(
        decision: LearningDecision,
        at: Long,
    ): LearnedMemory {
        if (decision.isEmpty) return this
        val next = records.toMutableMap()
        val rescued = decision.rescues.toSet()
        val pairs = (listOfNotNull(decision.selection) + decision.rescues).distinct()
        for (pair in pairs) {
            if (!LearningLimits.accepts(pair.query)) continue
            val old = next[pair]
            if (old?.suppressed == true) continue
            next[pair] =
                LearnedRecord(
                    query = pair.query,
                    app = pair.app,
                    count = ((old?.count ?: 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    lastUsedAt = maxOf(at, old?.lastUsedAt ?: at),
                    rescued = (old?.rescued ?: false) || pair in rescued,
                )
        }
        // 入力した語の選択（R1）と区別する。前方一致やローマ字で使った元の救済語の回数は増やさず、忘却の時刻だけ進める
        decision.used?.let { pair ->
            next[pair]?.takeIf { it.rescued && !it.suppressed }?.let { old ->
                next[pair] = old.copy(lastUsedAt = maxOf(at, old.lastUsedAt))
            }
        }
        return LearnedMemory(next.values, config)
    }

    /**
     * [pair] に「この語では出さない」の印を付けたもの（R4）。記録がなければ、回数 0 の記録を作って印を付ける（名前で一致したアプリにも付けられる）。
     */
    public fun suppressed(
        pair: LearnedPair,
        at: Long,
    ): LearnedMemory {
        if (!LearningLimits.accepts(pair.query)) return this
        val old = records[pair]
        val marked = old?.copy(suppressed = true) ?: LearnedRecord(pair.query, pair.app, 0, at, rescued = false, suppressed = true)
        return LearnedMemory(records.values.filterNot { it.pair == pair } + marked, config)
    }

    /**
     * 救済の知らせで「取り消す」を選んだとき（learning.md の R2）: [pair] の記録を、記録する前の [previous]（なければ記録なし）に戻したもの。
     *
     * 同じセッションで同じ組が選択（[selection]）にもなっていたら、選択の分は時刻 [at] で記録し直す（「てんき」の例。取り消すのは救済だけ）。
     * 記録した後に印を付けていたら、印を残す（取り消しで出さない組を戻さない）。
     */
    public fun rescueUndone(
        pair: LearnedPair,
        previous: LearnedRecord?,
        selection: LearnedPair?,
        at: Long,
    ): LearnedMemory {
        if (records[pair]?.suppressed == true) return this
        val restored = records.values.filterNot { it.pair == pair } + listOfNotNull(previous)
        val again = LearningDecision(selection?.takeIf { it == pair }, emptyList())
        return LearnedMemory(restored, config).recorded(again, at)
    }

    /**
     * 書き出した記録 [imported] を時刻 [at] に読み込んで合わせたもの（learning.md の「保存と持ち出し」）。
     *
     * 同じ組があれば、回数は大きいほう、救済と印はどちらかにあれば残す（印は消さない）。
     * 回数を足さないのは、同じファイルを 2 回読み込んでも回数が増えないようにするため。
     * 読み込んだ組の最後に使った時刻は [at] にそろえる。古いファイルを読み込んだ直後に忘却（R5）で消えないようにするため。
     * ファイルにない今の記録は変えない。
     */
    public fun merged(
        imported: Collection<LearnedRecord>,
        at: Long,
    ): LearnedMemory {
        if (imported.isEmpty()) return this
        val next = records.toMutableMap()
        for (record in imported) {
            if (!LearningLimits.accepts(record.query)) continue
            val old = next[record.pair]
            next[record.pair] =
                LearnedRecord(
                    query = record.query,
                    app = record.app,
                    count = maxOf(old?.count ?: 0, record.count),
                    // 端末の時計が戻っていても、今の記録の時刻より古くしない
                    lastUsedAt = maxOf(at, old?.lastUsedAt ?: at),
                    rescued = (old?.rescued ?: false) || record.rescued,
                    suppressed = (old?.suppressed ?: false) || record.suppressed,
                )
        }
        return LearnedMemory(next.values, config)
    }

    /** すべての記録を消したもの（管理画面の全消去）。印も消える。 */
    public fun cleared(): LearnedMemory = if (records.isEmpty()) this else LearnedMemory(emptyList(), config)

    /** [pair] の記録を消したもの（「この語での学習を消す」・管理画面の削除）。印も消える。 */
    public fun removed(pair: LearnedPair): LearnedMemory =
        if (pair in records) LearnedMemory(records.values.filterNot { it.pair == pair }, config) else this

    /**
     * 時刻 [now] で、最後に使ってから [LearningConfig.forgetAfterMillis] 以上たった記録を消したもの（R5）。印のある組は消さない。
     */
    public fun forgotten(now: Long): LearnedMemory {
        val kept = records.values.filter { it.suppressed || !isExpired(it, now) }
        return if (kept.size == records.size) this else LearnedMemory(kept, config)
    }

    /** [record] が時刻 [now] で忘れる時期を過ぎたか（R5）。印は見ない。 */
    public fun isExpired(
        record: LearnedRecord,
        now: Long,
    ): Boolean = now - record.lastUsedAt >= config.forgetAfterMillis

    /**
     * 検索語 [query] で [app] に付ける学習の加点（R3）。記録の検索語が [query] の厳密形で始まる記録のうち、回数の最も多いものから決める。
     *
     * 回数 × [LearningConfig.boostPerUse] で、[LearningConfig.maxBoost] で頭を打つ。印のある組は加点しない。
     * 記録ごとに足さず最も多いものを使うのは、「て」と打ったときに「てんき」「てれび」…の記録が積み上がって上限まで行くのを避けるため。
     */
    public fun learnedBoost(
        query: SearchQuery,
        app: AppId,
    ): Int {
        val list = activeByApp[app] ?: return 0
        val q = query.strict.value
        if (q.isEmpty()) return 0
        var maxCount = 0
        for (record in list) {
            if (record.query.value.startsWith(q) && record.count > maxCount) maxCount = record.count
        }
        return minOf(maxCount.toLong() * config.boostPerUse, config.maxBoost.toLong()).toInt()
    }

    /**
     * 検索語 [query] で [app] を学習で上げる段階の数（R6）。時刻 [now] から見た新しさを使う。
     *
     * 記録の語が検索語と同じか、英字を含む検索語が記録の語の最後の 1 音を打ちかけた形（「めーる」に対する「めーr」・`mer`・`meru`）のときだけ効く。
     * 語の頭だけを打った検索語（「めー」）では上げない。短い語で、別の語の学習が段階の並びを崩さないようにするため（加点は同じ段階の中で効く）。
     *
     * 幅は、回数から [LearningConfig.freeSelections] を引き、救済になったことがあれば [LearningConfig.rescueStageLift] を足し、
     * 最後に使ってから [LearningConfig.staleStageLiftMillis] たっていれば 1 段減らしたもの。当たる記録が複数あれば大きいほう。
     * 段階の数で頭を打つ。印のある組は上げない（[SearchEngine] がその検索語では出さない）。
     */
    public fun stageLift(
        query: SearchQuery,
        app: AppId,
        now: Long,
    ): Int {
        val keys = liftKeysByApp[app] ?: return 0
        val q = query.strict
        if (q.isEmpty) return 0
        // 英字を含む検索語だけ、ローマ字で比べる。かなだけの途中の形（「めー」）は、語の頭を打っただけとみなす
        val typed = if (q.value.containsLatin()) RomajiConverter.toStandard(q, RomajiMode.PREFIX).value else null
        var best = 0
        for (key in keys) {
            if (!key.matches(q.value, typed)) continue
            val record = key.record
            var lift = record.count.toLong() - config.freeSelections + if (record.rescued) config.rescueStageLift else 0
            if (now - record.lastUsedAt >= config.staleStageLiftMillis) lift--
            best = maxOf(best, lift.coerceIn(0L, MAX_STAGE_LIFT.toLong()).toInt())
        }
        return best
    }

    /**
     * [stageLift] で比べる記録 1 件。ローマ字は、記録の語の全体（[full]）と、最後の 1 字を除いた頭（[head]）。
     * どちらかが英数字だけの形にならない語（漢字を含む）は、ローマ字では比べない。
     */
    private class LiftKey(
        val record: LearnedRecord,
    ) {
        private val full: String?
        private val head: String?

        init {
            val word = record.query.value
            val lastStart = if (word.isEmpty()) 0 else word.offsetByCodePoints(word.length, -1)
            full = RomajiConverter.toStandard(record.query, RomajiMode.COMPLETE).value.takeIf { it.isRomajiOnly() }
            head = RomajiConverter.toStandard(NormalizedText(word.substring(0, lastStart)), RomajiMode.COMPLETE).value
        }

        /**
         * 厳密形 [strict] が記録の語と同じか、英字を含む検索語のローマ字 [typed] が、最後の 1 字の音に入っていて記録の語のローマ字の頭にあたるか。
         * 「めー」の `me` で止まる形（`me`）は、最後の音を打ち始めていないので数えない。
         */
        fun matches(
            strict: String,
            typed: String?,
        ): Boolean {
            if (strict == record.query.value) return true
            if (typed == null || full == null || head == null) return false
            return typed.length > head.length && full.startsWith(typed)
        }
    }

    /** 定数。 */
    public companion object {
        /** 記録のない状態。 */
        public val EMPTY: LearnedMemory = LearnedMemory(emptyList())

        // 最上段（完全一致）から最下段（提供元）までの差。これより上げても並びは変わらない
        private val MAX_STAGE_LIFT = MatchStage.entries.size - 1
    }
}
