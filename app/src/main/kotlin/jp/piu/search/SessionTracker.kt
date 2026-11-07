// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRoute
import jp.piu.search.core.search.LearningDecision
import jp.piu.search.core.search.LearningPolicy
import jp.piu.search.core.search.SessionEvent

/**
 * 検索の画面のセッション（開いてから、アプリを開くか画面を閉じるまで）の出来事を集め、アプリを開いたら [LearningPolicy] で判定する。
 *
 * 出来事の列（打った文字の列を含む）はメモリにだけ置き、判定したら・画面を閉じたら捨てる（learning.md の「保存と持ち出し」）。
 * 画面の保存（`onSaveInstanceState`）にも入れない。画面のスレッドからだけ呼ぶ。
 */
class SessionTracker(
    private val policy: LearningPolicy = LearningPolicy(),
) {
    private val events = ArrayList<SessionEvent>()

    /** 集めている出来事の数。テストで、捨てたことを確かめる。 */
    val size: Int get() = events.size

    /** 検索語が [query] になり、結果の上位 [visible] が画面に出た。 */
    fun queryShown(
        at: Long,
        query: String,
        visible: List<AppId>,
    ) {
        add(SessionEvent.QueryChanged(at, query, visible))
    }

    /** 結果をスクロールした。続けて届くので、前の出来事もスクロールなら足さない。 */
    fun scrolled(at: Long) {
        if (events.lastOrNull() !is SessionEvent.Scrolled) add(SessionEvent.Scrolled(at))
    }

    /** すべてのアプリの一覧を開いた。 */
    fun allAppsShown(at: Long) {
        add(SessionEvent.AllAppsShown(at))
    }

    /** [app] を [route] から開いた。記録する組を返し、出来事を捨てる。 */
    fun opened(
        at: Long,
        app: AppId,
        route: LaunchRoute,
        query: String,
    ): LearningDecision {
        add(SessionEvent.AppOpened(at, app, route, query))
        val decision = policy.decide(events)
        events.clear()
        return decision
    }

    /** 何も開かずにセッションが終わった（画面を離れた・呼び出し直された）。出来事を捨てる。 */
    fun closed() {
        events.clear()
    }

    private fun add(event: SessionEvent) {
        // 画面を開いたまま長く打ち続けても膨らまないよう、古いものから捨てる。救済は 60 秒以内の試行しか見ないので、判定は変わらない
        if (events.size >= MAX_EVENTS) events.removeAt(0)
        events.add(event)
    }

    private companion object {
        const val MAX_EVENTS = 500
    }
}
