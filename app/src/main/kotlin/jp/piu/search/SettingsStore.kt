// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 検索窓の位置（ui.md の「設定画面」）。 */
enum class SearchBarPosition {
    /** 画面の下。片手で届く（既定。要件 U8）。結果は下から上へ並べる。 */
    BOTTOM,

    /** 画面の上。結果は上から下へ並べる。 */
    TOP,
}

/** 仕事用プロファイルのアプリの扱い（ui.md の「設定画面」）。 */
enum class WorkProfileMode {
    /** 「個人用｜仕事用」を切り替えて検索する（既定）。 */
    SWITCH,

    /** 個人用と混ぜて検索する。切り替えは出さない。 */
    MIXED,

    /** 仕事用のアプリを出さない。 */
    HIDDEN,
}

/**
 * 検索の画面の振る舞いを決める設定。
 *
 * @property searchBarPosition 検索窓の位置
 * @property showReasons 出た理由（「読み: …」など）を結果の行に出すか
 * @property workProfileMode 仕事用プロファイルのアプリの扱い
 */
data class SearchSettings(
    val searchBarPosition: SearchBarPosition = SearchBarPosition.BOTTOM,
    val showReasons: Boolean = true,
    val workProfileMode: WorkProfileMode = WorkProfileMode.SWITCH,
)

/**
 * 利用者の設定の全体。
 *
 * @property search 検索の画面の設定
 * @property onboardingDismissed 初回の案内を閉じたか
 */
data class Settings(
    val search: SearchSettings = SearchSettings(),
    val onboardingDismissed: Boolean = false,
)

/**
 * 利用者の設定（SharedPreferences）。設定画面の項目と、初回の案内を閉じたか。今の値は [settings] で流す。
 *
 * 索引の表（作り直せる写し）とは分け、アプリを消すかデータを消すまで残す。値が少なく小さいので、
 * 依存を足す DataStore ではなく SharedPreferences にした。作るときに同期で読むので、案内や検索窓の位置が最初の描画で一瞬違って見えることもない。
 * 書き手はこの部品だけなので、書いたその場で流れも変える（`OnSharedPreferenceChangeListener` は使わない）。画面のスレッドから書く。
 * 列挙は名前で保存し、知らない名前（後の版で消した値など）は既定に戻す。
 */
class SettingsStore(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    /** 今の設定。画面はこれを購読するので、設定画面から戻ったときに読み直さなくてよい。 */
    val settings: StateFlow<Settings> = state.asStateFlow()

    /** 初回の案内を閉じたか。閉じたら、設定画面で戻すまで出さない（ui.md の「呼び出し」）。 */
    val onboardingDismissed: Boolean get() = state.value.onboardingDismissed

    /** 初回の案内を閉じたことを覚える。 */
    fun dismissOnboarding() {
        preferences.edit { putBoolean(KEY_ONBOARDING_DISMISSED, true) }
        state.update { it.copy(onboardingDismissed = true) }
    }

    /** 次に検索の画面を開いたとき、初回の案内をもう一度出す（設定画面の「案内をもう一度見る」）。 */
    fun showOnboardingAgain() {
        preferences.edit { remove(KEY_ONBOARDING_DISMISSED) }
        state.update { it.copy(onboardingDismissed = false) }
    }

    /** 検索の画面の設定。 */
    var search: SearchSettings
        get() = state.value.search
        set(value) {
            preferences.edit {
                putString(KEY_SEARCH_BAR, value.searchBarPosition.name)
                putBoolean(KEY_SHOW_REASONS, value.showReasons)
                putString(KEY_WORK_PROFILE, value.workProfileMode.name)
            }
            state.update { it.copy(search = value) }
        }

    private fun read(): Settings =
        Settings(
            search =
                SearchSettings(
                    searchBarPosition = enumOf(KEY_SEARCH_BAR, SearchBarPosition.BOTTOM),
                    showReasons = preferences.getBoolean(KEY_SHOW_REASONS, true),
                    workProfileMode = enumOf(KEY_WORK_PROFILE, WorkProfileMode.SWITCH),
                ),
            onboardingDismissed = preferences.getBoolean(KEY_ONBOARDING_DISMISSED, false),
        )

    private inline fun <reified E : Enum<E>> enumOf(
        key: String,
        default: E,
    ): E {
        val name = preferences.getString(key, null) ?: return default
        return enumValues<E>().firstOrNull { it.name == name } ?: default
    }

    private companion object {
        const val FILE = "settings"
        const val KEY_ONBOARDING_DISMISSED = "onboarding_dismissed"
        const val KEY_SEARCH_BAR = "search_bar_position"
        const val KEY_SHOW_REASONS = "show_reasons"
        const val KEY_WORK_PROFILE = "work_profile_mode"
    }
}
