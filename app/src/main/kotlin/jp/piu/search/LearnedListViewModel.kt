// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** ファイル操作などの結果の表示状態。画面終了後の完了も残す箱と、構成の変更をまたぐ表示をつなぐ。 */
class LearnedListViewModel(
    private val results: ResultBox<BackupOutcome>,
) : ViewModel() {
    private var shown: ResultBox.Posted<BackupOutcome>? = null

    /** 学習の一覧でまだ出し終えていない操作の結果。プロセスの保存状態には入れない。 */
    var outcome: BackupOutcome? by mutableStateOf(null)
        private set

    init {
        viewModelScope.launch {
            results.current.collect {
                shown = it
                outcome = it?.value
            }
        }
    }

    /** 今の結果を出し終えた。新しく届いた結果は消費しない。 */
    fun messageShown() {
        shown?.let(results::done)
    }
}
