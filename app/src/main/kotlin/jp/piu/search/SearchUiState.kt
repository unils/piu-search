// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.annotation.StringRes
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchRoute
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.MatchReason
import jp.piu.search.core.state.RescueNotice

/**
 * 結果の 1 行。
 *
 * @property app アプリの識別子
 * @property label 表示名
 * @property reason 出た理由。名前で一致したとき・空の検索語の一覧では null（ui.md の「理由は名前で一致しなかったときだけ」）
 * @property isWork 個人用と混ぜて出している中の仕事用の行。読み上げで個人用の同名のアプリと区別する
 */
data class ResultRow(
    val app: AppId,
    val label: String,
    val reason: MatchReason? = null,
    val isWork: Boolean = false,
)

/** 検索の画面の、検索窓の上に出すもの。 */
sealed interface SearchContent {
    /** 何も出さない。索引を読む前と、漢字の検索語の読みを待つ間（「見つかりません」を一瞬出さないため）。 */
    data object Waiting : SearchContent

    /**
     * 空の検索語のとき: 最近使ったアプリとよく使うアプリ。どちらも先頭が最も上位（検索窓に近い）。
     * どちらも空（まだ何も開いていない）なら、探し方の案内を出す。
     *
     * @property recent 最近使ったアプリ
     * @property frequent よく使うアプリ（[recent] にあるものは除く）
     */
    data class Suggestions(
        val recent: List<ResultRow>,
        val frequent: List<ResultRow>,
    ) : SearchContent

    /**
     * 検索の結果。
     *
     * @property rows 並べた順の行。先頭が 1 位で、検索窓の直上に出す
     */
    data class Results(
        val rows: List<ResultRow>,
    ) : SearchContent

    /**
     * 一致がない。
     *
     * @property workMatches 個人用を見ていて一致がないとき、仕事用に当たる数（ui.md の「仕事用に N 件」）。ほかは 0
     */
    data class NoMatch(
        val workMatches: Int = 0,
    ) : SearchContent

    /**
     * すべてのアプリ（ui.md の「すべてのアプリ」）。選んでいるプロファイルのアプリを名前の順に並べる。
     *
     * @property rows 並べた順の行。先頭が一覧の上端
     * @property sections 頭文字の索引。[rows] の順に並ぶ
     */
    data class AllApps(
        val rows: List<ResultRow>,
        val sections: List<AllAppsSection>,
    ) : SearchContent
}

/**
 * 「すべてのアプリ」の頭文字の索引の 1 つ。
 *
 * @property label 見出し（`A`・`あ`・`#` など）
 * @property firstRow その見出しの最初の行の、[SearchContent.AllApps.rows] での位置
 */
data class AllAppsSection(
    val label: String,
    val firstRow: Int,
)

/** 検索の対象のプロファイル（ui.md の「仕事用プロファイル」）。 */
enum class ProfileTab {
    /** 個人用。このアプリが動いているプロファイル。 */
    PERSONAL,

    /** 仕事用。同じプロファイルグループのほかのプロファイル。 */
    WORK,
}

/**
 * アプリを開いた経路。学習で、すべてのアプリから開いた（救済。learning.md の R2）かを見分けるのに使う。
 *
 * @property learning 学習の判定に渡す経路
 */
enum class OpenRoute(
    val learning: LaunchRoute,
) {
    /** 検索の結果（確定キーを含む）。 */
    SEARCH_RESULT(LaunchRoute.SEARCH_RESULT),

    /** 空の検索語の一覧（最近・よく使うアプリ）。 */
    SUGGESTION(LaunchRoute.SUGGESTION),

    /** すべてのアプリの一覧。 */
    ALL_APPS(LaunchRoute.ALL_APPS),
}

/**
 * 結果の行の長押しのメニューの項目（ui.md の「長押しのメニュー」）。[SearchViewModel.menuFor] で行ごとに出し分ける。
 *
 * @property label メニューに出す文字
 */
enum class RowAction(
    @param:StringRes val label: Int,
) {
    /** OS の設定画面の、そのアプリのページ。アンインストールもここから行う。 */
    APP_INFO(R.string.menu_app_info),

    /** この検索語ではこのアプリを出さない（learning.md の R4）。検索の結果の行だけ。 */
    SUPPRESS(R.string.menu_suppress),

    /** この行を出した学習の記録を消す。学習のキーで出ている行だけ。 */
    FORGET_LEARNING(R.string.menu_forget_learning),

    /** 学習の一覧を開く。どの行にも出す（設定画面の「学習」と同じ画面）。 */
    MANAGE_LEARNING(R.string.menu_manage_learning),
}

/**
 * アプリを開く頼み。開く前に、経路と検索語で学習を判定する（[SearchViewModel.open]）。
 *
 * @property app 開くアプリ
 * @property route 開いた経路
 * @property query 開いたときに検索窓にあった検索語（打ったまま。正規化しない）
 * @property learnedPair 行を出した学習のキーの組。起動の完了を待つ間に結果が変わっても、押した行の語を使う
 */
data class OpenRequest(
    val app: AppId,
    val route: OpenRoute,
    val query: String,
    val learnedPair: LearnedPair? = null,
)

/**
 * 検索の画面に出すものすべて（[SearchViewModel] が索引・記録・プロファイル・設定と自分の状態から作る）。
 *
 * @property query 打たれている検索語
 * @property content 検索窓の上に出すもの
 * @property showingAllApps すべてのアプリを出しているか
 * @property profileSwitch 「個人用｜仕事用」の切り替え。仕事用プロファイルがあり、設定が「切り替えて検索する」のときだけ
 * @property workPausedNotice 仕事用プロファイルが一時停止中で、そのアプリが今の結果に出うるか
 * @property searchBarOnTop 検索窓を上に置く（設定）
 * @property showReasons 出た理由を行に出す（設定）
 * @property onboardingShown 初回の案内を出すか
 * @property notices 出す救済の知らせ
 * @property indexNotice 索引の知らせ
 * @property openRecordFailed 前にアプリを開いたときの記録の保存に失敗した
 * @property recordsRebuilt 記録の DB が壊れていたので、新しく始めた
 * @property focusKey 呼び出し直されるたびに増える数。検索窓へ入力を向け直す合図
 */
data class SearchUiState(
    val query: String = "",
    val content: SearchContent = SearchContent.Waiting,
    val showingAllApps: Boolean = false,
    val profileSwitch: ProfileSwitch? = null,
    val workPausedNotice: Boolean = false,
    val searchBarOnTop: Boolean = false,
    val showReasons: Boolean = true,
    val onboardingShown: Boolean = false,
    val notices: List<RescueNotice> = emptyList(),
    val indexNotice: IndexNotice? = null,
    val openRecordFailed: Boolean = false,
    val recordsRebuilt: Boolean = false,
    val focusKey: Int = 0,
) {
    /** 確定キー（IME の「検索」）で開くアプリ。検索窓の直上の結果。結果がない・検索語が空なら null。 */
    val topResult: AppId? get() = (content as? SearchContent.Results)?.rows?.firstOrNull()?.app
}

/** Activity が作る、その画面 1 つを指す札。保存しない（回転で作り直した画面は別の札になり、古い起動の完了で閉じない）。 */
class ScreenToken
