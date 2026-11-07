// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

/**
 * アプリを開いた経路（learning.md の R1・R2）。
 */
public enum class LaunchRoute {
    /** 検索の結果（確定キーを含む）。検索語が空でなければ選択（R1）になる。 */
    SEARCH_RESULT,

    /** 空の検索語の一覧（最近・よく使うアプリ）。 */
    SUGGESTION,

    /** すべてのアプリの一覧。検索語を残したまま開けば、その検索語が救済（R2）の候補になる。 */
    ALL_APPS,
}

/**
 * 検索画面のセッション（画面を開いてから、アプリを開くか画面を閉じるまで）の出来事 1 つ。[LearningPolicy] の入力。
 *
 * 時刻はエポックからのミリ秒で、外から渡す（テストで自由に進めるため）。出来事の列は時刻の順に並べる。
 */
public sealed interface SessionEvent {
    /** 出来事の時刻。 */
    public val at: Long

    /**
     * 検索語が [query] になり、結果の上位 [visibleResults] が画面に出た。
     *
     * [visibleResults] は「見えていた」とみなす上位（[LearningConfig.visibleCount] 件まで）。
     * 漢字の検索語に読みを付けて結果を出し直したときなど、同じ検索語で結果だけが変わったら、同じ [query] でもう一度送る（後の結果で置き換える）。
     *
     * @property query 打たれたままの検索語（正規化しない）
     */
    public data class QueryChanged(
        override val at: Long,
        val query: String,
        val visibleResults: List<AppId>,
    ) : SessionEvent

    /** 結果をスクロールした。今の規則（R2 の「見えていた」は最初に出た上位で決める）では判定を変えない。 */
    public data class Scrolled(
        override val at: Long,
    ) : SessionEvent

    /** 「すべてのアプリ」の一覧を開いた。開いた経路は [AppOpened.route] で見るので、今の規則では判定を変えない。 */
    public data class AllAppsShown(
        override val at: Long,
    ) : SessionEvent

    /**
     * アプリ [app] を [route] から開いた。セッションはここで終わる。
     *
     * @property query 開いたときに検索窓にあった検索語（打ったまま）
     */
    public data class AppOpened(
        override val at: Long,
        val app: AppId,
        val route: LaunchRoute,
        val query: String,
    ) : SessionEvent

    /** 何も開かずに画面を閉じた。セッションはここで終わり、何も記録しない。 */
    public data class Closed(
        override val at: Long,
    ) : SessionEvent
}
