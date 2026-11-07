// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.TextNormalizer

/**
 * 検索語とアプリの組 1 つ。検索語は正規化した厳密形。
 *
 * @property query 検索語の厳密形
 * @property app アプリ
 */
public data class LearnedPair(
    val query: NormalizedText,
    val app: AppId,
)

/**
 * 1 つのセッションから記録する組（[LearningPolicy.decide] の出力）。
 *
 * @property selection 選択（R1）。アプリを開かなかった・検索の結果から開かなかった・検索語が空なら null
 * @property rescues 救済（R2）。見つかった順（古い試行が先）
 */
public data class LearningDecision(
    val selection: LearnedPair?,
    val rescues: List<LearnedPair>,
) {
    /** 記録するものがないか。 */
    public val isEmpty: Boolean get() = selection == null && rescues.isEmpty()

    /** 救済の知らせで「取り消す」を選んだとき（learning.md の R2）: [pair] の救済を除いたもの。選択は残す。 */
    public fun withoutRescue(pair: LearnedPair): LearningDecision = copy(rescues = rescues - pair)

    /** 定数。 */
    public companion object {
        /** 何も記録しない。 */
        public val NONE: LearningDecision = LearningDecision(null, emptyList())
    }
}

/**
 * 学習の判定（learning.md の「規則」の R1・R2）。セッションの出来事の列から、記録する組を返す純粋な関数。
 *
 * 試行の区切り（learning.md の「言葉の定義」）:
 *
 * - 検索語の厳密形が今の試行の代表で始まる（書き足した・同じ）なら同じ試行で、長くなれば代表を置き換える
 * - 代表の前の部分に戻った（文字を消した途中）なら同じ試行で、代表と「見えていた」結果はそのまま残す
 * - それ以外（消して別の語を打った）と、検索語が空になった後は、次の試行に移る
 *
 * 試行の終わりの時刻は、その試行での最後の入力（[SessionEvent.QueryChanged]）の時刻にした。
 * 画面を眺めているだけの時間を数えないと、検索語を残したまま長く一覧を探してから開いたアプリまで結びつけてしまうため。
 *
 * @property config 判定の数値（仮）
 */
public class LearningPolicy(
    private val config: LearningConfig = LearningConfig.DEFAULT,
) {
    /**
     * [events] から、記録する組を返す。最初の [SessionEvent.AppOpened] か [SessionEvent.Closed] でセッションが終わり、その後の出来事は見ない。
     * アプリを開かずに終わったら [LearningDecision.NONE]。
     */
    public fun decide(events: List<SessionEvent>): LearningDecision {
        val trials = ArrayList<Trial>()
        var current: Trial? = null
        for (event in events) {
            when (event) {
                is SessionEvent.QueryChanged -> current = onQuery(event, current, trials)
                is SessionEvent.AppOpened -> return decideOpen(event, current, trials)
                is SessionEvent.Closed -> return LearningDecision.NONE
                is SessionEvent.Scrolled, is SessionEvent.AllAppsShown -> Unit
            }
        }
        return LearningDecision.NONE
    }

    /** 検索語の変化を試行に振り分け、変化の後の今の試行を返す（検索語が空なら null）。 */
    private fun onQuery(
        event: SessionEvent.QueryChanged,
        current: Trial?,
        trials: MutableList<Trial>,
    ): Trial? {
        val text = TextNormalizer.strict(event.query).value
        if (text.isEmpty()) return null
        if (current == null || !(text.startsWith(current.representative) || current.representative.startsWith(text))) {
            return Trial(text, event.visibleResults.toSet(), event.at).also { trials.add(it) }
        }
        // 書き足したか同じなら代表を進め、消している途中なら代表と見えていた結果を残す
        if (text.startsWith(current.representative)) {
            current.representative = text
            current.visible = event.visibleResults.toSet()
        }
        current.lastInputAt = event.at
        return current
    }

    private fun decideOpen(
        event: SessionEvent.AppOpened,
        current: Trial?,
        trials: List<Trial>,
    ): LearningDecision {
        val query = TextNormalizer.strict(event.query)
        // すべてのアプリの一覧から開いたのは、結果で見つけて選んだのではないので選択にしない（learning.md の「例」の「ぎんこう」）
        val selection = if (event.route == LaunchRoute.SEARCH_RESULT && !query.isEmpty) LearnedPair(query, event.app) else null
        val rescues =
            trials
                .filter { trial ->
                    event.app !in trial.visible &&
                        trial.representative.codePointCount(0, trial.representative.length) >= config.minRescueLength &&
                        event.at - trial.lastInputAt <= config.rescueWindowMillis &&
                        // 前の試行が開いたときの検索語の前の部分なら、打ち直しの途中にすぎない（R2 の 4 つ目）。
                        // 今の試行（検索語を残したまま一覧やスクロールで開いた）にはこの条件をかけない（「てんき」「ぎんこう」の例）
                        (trial === current || !query.value.startsWith(trial.representative))
                }.map { it.representative }
                .distinct()
                .map { LearnedPair(NormalizedText(it), event.app) }
        return LearningDecision(selection, rescues)
    }

    /** 試行 1 つ。[visible] は代表での結果の「見えていた」上位。 */
    private class Trial(
        var representative: String,
        var visible: Set<AppId>,
        var lastInputAt: Long,
    )
}
