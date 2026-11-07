// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText

/**
 * 学習した組 1 つの記録（learning.md の「何を覚えるか」）。
 *
 * 選択と救済を別の行にせず、組ごとに 1 行にまとめる。同じ組が選択にも救済にもなる（「てんき」の例）ので、
 * 救済になったことがあるかを [rescued] で持つ。
 *
 * @property query 検索語の厳密形
 * @property app アプリ
 * @property count 記録した回数（1 つのセッションで選択と救済の両方になっても 1 回）
 * @property lastUsedAt 最後に記録した時刻（エポックからのミリ秒）。R5 の忘却に使う
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
    public val records: Map<LearnedPair, LearnedRecord> = records.associateBy { it.pair }

    // 照合の中で結果のアプリごとに引くので、アプリごとにまとめておく
    private val activeByApp: Map<AppId, List<LearnedRecord>> =
        this.records.values
            .filterNot { it.suppressed }
            .groupBy { it.app }

    /**
     * [decision] を時刻 [at] に記録したもの。印のある組は変えない（ユーザーが出さないと決めた組を、学習で戻さない）。
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
            val old = next[pair]
            if (old?.suppressed == true) continue
            next[pair] =
                LearnedRecord(
                    query = pair.query,
                    app = pair.app,
                    count = (old?.count ?: 0) + 1,
                    lastUsedAt = maxOf(at, old?.lastUsedAt ?: at),
                    rescued = (old?.rescued ?: false) || pair in rescued,
                )
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
        return minOf(maxCount * config.boostPerUse, config.maxBoost)
    }

    /** 定数。 */
    public companion object {
        /** 記録のない状態。 */
        public val EMPTY: LearnedMemory = LearnedMemory(emptyList())
    }
}
