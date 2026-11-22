// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.state.RecordsFailure
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.data.Profiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 学習の一覧の画面の状態。
 *
 * @property learning 学習。読めていなければ null（一覧を出さない。「まだ何も覚えていません」を一瞬出さないため）
 * @property index 表示名を引く索引
 * @property ownSerial このアプリが動いているプロファイルの番号
 * @property outcome まだ出し終えていない操作の結果
 */
data class LearnedListState(
    val learning: LearnedMemory? = null,
    val index: SearchIndex = SearchIndex.EMPTY,
    val ownSerial: Long = 0,
    val outcome: ResultBox.Posted<BackupOutcome>? = null,
)

/**
 * 学習の一覧の ViewModel。記録の写し・索引・プロファイル・結果の箱を組み合わせる。
 *
 * 操作（削除・全消去・書き出し・読み込み）はアプリの寿命の scope で最後まで行い、結果を箱に入れる。画面を閉じた後に終わった結果も、
 * 次に一覧を開いたときに出すため（ui.md の「学習の操作結果」）。
 */
class LearnedListViewModel(
    private val records: RecordsPort,
    private val index: IndexPort,
    profiles: StateFlow<Profiles>,
    private val results: ResultBox<BackupOutcome>,
    private val transfer: LearningTransfer,
    private val appScope: CoroutineScope,
) : ViewModel() {
    /** 画面に出すもの。 */
    val state: StateFlow<LearnedListState> =
        combine(records.state, index.searchIndex, profiles, results.current) { recordsState, searchIndex, current, outcome ->
            LearnedListState(recordsState.records?.learned, searchIndex, current.own, outcome)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LearnedListState())

    /** 画面が前に出た。読めていなければ読み直し、読めなければ知らせる。索引は表示名を引くためだけに使う（設定から先に開いたときのため同期を頼む）。 */
    fun onScreenStarted() {
        index.requestSync()
        appScope.launch {
            records.refresh().await()
            if (records.state.value.loadFailure != null) results.post(BackupOutcome.Failed(RecordsFailure.NOT_LOADED))
        }
    }

    /** [pair] の学習を消す。 */
    fun delete(pair: LearnedPair) = submit(RecordsRequest.Remove(pair))

    /** 学習を全部消す（起動の記録は残す）。 */
    fun clearAll() = submit(RecordsRequest.ClearLearned)

    /** [uri] に書き出す。 */
    fun export(uri: Uri) {
        appScope.launch { results.post(transfer.export(uri)) }
    }

    /** [uri] から読み込む。 */
    fun import(uri: Uri) {
        appScope.launch { results.post(transfer.import(uri)) }
    }

    /** [posted] を出し終えた。新しく届いた結果は消費しない。 */
    fun messageShown(posted: ResultBox.Posted<BackupOutcome>) {
        results.done(posted)
    }

    private fun submit(request: RecordsRequest) {
        appScope.launch {
            val result = records.request(request)
            if (result is RecordsResult.Failure) results.post(BackupOutcome.Failed(result.reason))
        }
    }
}
