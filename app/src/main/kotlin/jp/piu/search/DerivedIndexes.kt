// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.SearchIndex

/**
 * 派生の索引の入力。等しさは、索引は参照で、学習は中身で比べる。
 *
 * 索引の写しは書き手が変化のない同期では流さないので、参照が同じなら同じ索引。学習の写し（[LearnedMemory]）は、
 * 記録の書き手が起動の記録だけを変えた回も同じ中身の別の値になりうるので、中身で比べる（起動の記録だけの変化で作り直さないため）。
 *
 * @property index もとの索引
 * @property learning 学習
 * @property own このアプリが動いているプロファイルの番号
 * @property work 仕事用の番号
 * @property mode 仕事用プロファイルのアプリの扱い
 */
class DerivationInput(
    val index: SearchIndex,
    val learning: LearnedMemory,
    val own: Long,
    val work: Set<Long>,
    val mode: WorkProfileMode,
) {
    override fun equals(other: Any?): Boolean =
        other is DerivationInput &&
            index === other.index &&
            learning.records == other.learning.records &&
            own == other.own &&
            work == other.work &&
            mode == other.mode

    override fun hashCode(): Int = System.identityHashCode(index)
}

/**
 * 派生の索引（学習を重ね、プロファイルで絞ったもの）。
 *
 * @property input 作ったときの入力。画面は採る直前に今の入力と比べ、違えば捨てる
 * @property personal 個人用の側で引く索引（混ぜるときは仕事用も含む）
 * @property work 仕事用の側で引く索引。切り替えて検索するときだけ
 */
class DerivedIndexes(
    val input: DerivationInput,
    val personal: SearchIndex,
    val work: SearchIndex,
)

/**
 * 派生の索引を作る。1 本の裏のスレッドからだけ呼ぶ（前回の結果を持つため）。
 *
 * 学習を重ねた索引は、索引と学習が前回と同じなら作り直さない（プロファイルや設定だけの変化で再利用する）。
 * 個人用は「自分の番号」で絞る。0.2.0 は「仕事用を除く」で作ったが、v0.3 では消えたプロファイルの組が次の同期まで索引に残るため。
 */
class IndexDeriver {
    private var learnedFor: DerivationInput? = null
    private var learnedIndex = SearchIndex.EMPTY

    /** [input] から作る。 */
    fun build(input: DerivationInput): DerivedIndexes {
        val previous = learnedFor
        if (previous == null || previous.index !== input.index || previous.learning.records != input.learning.records) {
            learnedIndex = input.index.withLearning(input.learning)
            learnedFor = input
        }
        val learned = learnedIndex
        val personal = learned.restrictedTo(personalSerials(input.own, input.work, input.mode))
        val work =
            if (input.mode == WorkProfileMode.SWITCH &&
                input.work.isNotEmpty()
            ) {
                learned.restrictedTo(input.work)
            } else {
                SearchIndex.EMPTY
            }
        return DerivedIndexes(input, personal, work)
    }

    /** 作り方の決まり。 */
    companion object {
        /** 個人用の側に出す番号。混ぜるときだけ仕事用を含む。 */
        fun personalSerials(
            own: Long,
            work: Set<Long>,
            mode: WorkProfileMode,
        ): Set<Long> = if (mode == WorkProfileMode.MIXED) work + own else setOf(own)
    }
}
