// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.annotation.StringRes
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.data.index.IndexStatus as IndexWriterStatus

/**
 * 検索画面の索引の知らせ（ui.md の「索引の知らせ」）。アプリが検索に出ないか、古いままになる失敗だけ。
 *
 * @property text 1 行の文
 */
enum class IndexNotice(
    @StringRes val text: Int,
) {
    /** 同期全体の失敗で、前の索引がある。 */
    SYNC_FAILED(R.string.index_notice_sync),

    /** 同期全体の失敗で、索引がない。 */
    NO_INDEX(R.string.index_notice_no_index),

    /** 仕事用があるときの、個人用のプロファイルの失敗。 */
    PERSONAL_FAILED(R.string.index_notice_personal),

    /** 仕事用のプロファイルの失敗。 */
    WORK_FAILED(R.string.index_notice_work),

    ;

    /** 出す知らせの決め方。 */
    companion object {
        /**
         * 索引の書き手の状態 [status] と、写しの一覧の状態 [listings] から、出す知らせを 1 つ決める。なければ null。
         *
         * 同期全体の失敗をプロファイルの失敗より先にする。プロファイルが 1 つだけのときと、個人用と仕事用の両方が失敗したときも
         * 同期全体の文にする（「個人用」は仕事用があるときだけの言葉で、両方なら一覧全体が古いため）。
         * 仕事用を「出さない」設定なら、仕事用だけの失敗は出さない（「索引の状態」にだけ出す）。
         * プロファイルの失敗は、このプロセスで同期が終わってからだけ見る（写しの一覧の状態は保存されていて、前のプロセスの結果を含むため）。
         *
         * @param hasIndex 検索に使う索引に 1 件でもアプリがあるか
         * @param ownSerial 個人用（このアプリが動くプロファイル）のシリアル番号
         * @param workMode 設定の「仕事用プロファイルのアプリ」
         */
        fun of(
            status: IndexWriterStatus,
            listings: Map<Long, ListingStatus>,
            hasIndex: Boolean,
            ownSerial: Long,
            workMode: WorkProfileMode,
        ): IndexNotice? {
            val whole = if (hasIndex) SYNC_FAILED else NO_INDEX
            if (status.syncFailure != null) return whole
            if (status.lastSync == null) return null
            val failed = listings.filterValues { it == ListingStatus.FAILED }.keys
            if (failed.isEmpty()) return null
            val personalFailed = ownSerial in failed
            val workFailed = failed.any { it != ownSerial }
            return when {
                listings.size <= 1 || (personalFailed && workFailed) -> whole
                personalFailed -> PERSONAL_FAILED
                workMode == WorkProfileMode.HIDDEN -> null
                else -> WORK_FAILED
            }
        }
    }
}
