// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.piu.search.core.state.PackageKey
import jp.piu.search.data.Profiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「索引の状態」の画面の ViewModel。索引の写しと書き手の状態から [status] を作る。
 *
 * 報告用の文は押したときに [io] で作る（欠けのある組の事実を端末から読むため）。見せている文は向きを変えても、
 * プロセスを作り直しても残す（[savedState]）。
 *
 * @param facts 欠けのある組の事実を読む
 * @param device 端末とこのアプリの版
 */
class IndexStatusViewModel(
    private val index: IndexPort,
    profiles: StateFlow<Profiles>,
    private val savedState: SavedStateHandle,
    private val io: CoroutineDispatcher,
    private val facts: (List<PackageKey>) -> Map<PackageKey, PackageFacts>,
    private val device: () -> DeviceInfo,
) : ViewModel() {
    /** 画面に出す中身。 */
    val status: StateFlow<IndexStatus> =
        combine(index.snapshot, index.status, profiles) { snapshot, status, current -> IndexStatus.of(snapshot, status, current.own) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, IndexStatus())

    /** 見せている報告用の文。閉じていれば null。 */
    val report: StateFlow<String?> = savedState.getStateFlow<String?>(KEY_REPORT, null)

    /** 画面が前に出た。設定から開いたときは、このプロセスでまだ同期していないことがあるので、同期を頼む。 */
    fun onScreenStarted() {
        index.requestSync()
    }

    /** 報告用の文を作って見せる。 */
    fun showReport() {
        val snapshot = status.value
        viewModelScope.launch {
            val text = withContext(io) { IndexReport.text(snapshot, device(), facts(snapshot.issues.map { it.issue.key })) }
            savedState[KEY_REPORT] = text
        }
    }

    /** 報告用の文を閉じる。 */
    fun closeReport() {
        savedState[KEY_REPORT] = null
    }

    private companion object {
        const val KEY_REPORT = "report"
    }
}
