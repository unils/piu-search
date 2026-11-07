// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** テストから設定画面を探すための印。 */
const val SETTINGS_TAG = "settings"

/** テストから「出た理由の表示」の切り替えを探すための印。 */
const val SETTINGS_SHOW_REASONS_TAG = "settings_show_reasons"

/** テストから「学習の一覧」を探すための印。 */
const val SETTINGS_LEARNED_TAG = "settings_learned"

/** テストから「案内をもう一度見る」を探すための印。 */
const val SETTINGS_ONBOARDING_AGAIN_TAG = "settings_onboarding_again"

/** テストからソースの URL を探すための印。 */
const val SETTINGS_SOURCE_TAG = "settings_source"

/** テストから「ライセンス」を探すための印。 */
const val SETTINGS_LICENSES_TAG = "settings_licenses"

/** テストから検索窓の位置の選択肢を探すための印。 */
fun searchBarOptionTag(position: SearchBarPosition): String = "settings_search_bar_${position.name}"

/** テストから仕事用プロファイルの扱いの選択肢を探すための印。 */
fun workProfileOptionTag(mode: WorkProfileMode): String = "settings_work_${mode.name}"

/**
 * 設定画面の操作。
 *
 * @property onSearchSettingsChange 検索の画面の設定を変えたとき。すぐ保存する
 * @property onOpenLearned 「学習の一覧」を押したとき
 * @property onOpenAssistantSettings 既定のアシスタントを選ぶ OS の設定画面を開く
 * @property onRequestTile タイルを足すよう OS に頼む。頼めない版（Android 12 以前）なら null
 * @property onShowOnboardingAgain 「案内をもう一度見る」を押したとき
 * @property onOpenSource ソースの URL を押したとき
 * @property onOpenLicenses 「ライセンス」を押したとき
 */
class SettingsActions(
    val onSearchSettingsChange: (SearchSettings) -> Unit,
    val onOpenLearned: () -> Unit,
    val onOpenAssistantSettings: () -> Unit,
    val onRequestTile: (() -> Unit)?,
    val onShowOnboardingAgain: () -> Unit,
    val onOpenSource: () -> Unit,
    val onOpenLicenses: () -> Unit,
)

/**
 * 設定画面（ui.md の「設定画面」）: 検索窓の位置・出た理由の表示・仕事用プロファイルのアプリ・学習・呼び出しの設定・このアプリについて。
 *
 * 選んだらすぐ保存し、確定のボタンは置かない。検索の画面に戻ると、その場で効く。
 *
 * @param settings 今の検索の画面の設定
 * @param version 版の表示（`0.0.1 (1)` など）
 * @param sourceUrl ソースの URL
 * @param actions 操作
 */
@Composable
fun SettingsScreen(
    settings: SearchSettings,
    version: String,
    sourceUrl: String,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        ReadableWidth {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                        .testTag(SETTINGS_TAG),
            ) {
                Text(
                    stringResource(R.string.settings_title),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )

                Section(R.string.settings_search_bar)
                Choices(
                    options = SearchBarPosition.entries,
                    selected = settings.searchBarPosition,
                    label = {
                        if (it == SearchBarPosition.BOTTOM) R.string.settings_search_bar_bottom else R.string.settings_search_bar_top
                    },
                    tag = ::searchBarOptionTag,
                    onSelect = { actions.onSearchSettingsChange(settings.copy(searchBarPosition = it)) },
                )

                Section(R.string.settings_reasons)
                SwitchRow(
                    title = stringResource(R.string.settings_show_reasons),
                    detail = stringResource(R.string.settings_show_reasons_detail),
                    checked = settings.showReasons,
                    onChange = { actions.onSearchSettingsChange(settings.copy(showReasons = it)) },
                    modifier = Modifier.testTag(SETTINGS_SHOW_REASONS_TAG),
                )

                Section(R.string.settings_work)
                Note(stringResource(R.string.settings_work_note))
                Choices(
                    options = WorkProfileMode.entries,
                    selected = settings.workProfileMode,
                    label = {
                        when (it) {
                            WorkProfileMode.SWITCH -> R.string.settings_work_switch
                            WorkProfileMode.MIXED -> R.string.settings_work_mixed
                            WorkProfileMode.HIDDEN -> R.string.settings_work_hidden
                        }
                    },
                    tag = ::workProfileOptionTag,
                    onSelect = { actions.onSearchSettingsChange(settings.copy(workProfileMode = it)) },
                )

                Section(R.string.settings_learning)
                LinkRow(
                    title = stringResource(R.string.learned_title),
                    detail = stringResource(R.string.settings_learning_detail),
                    onClick = actions.onOpenLearned,
                    modifier = Modifier.testTag(SETTINGS_LEARNED_TAG),
                )

                Section(R.string.settings_entry)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EntrySetupGuide(actions.onOpenAssistantSettings, actions.onRequestTile)
                    Text(stringResource(R.string.settings_onboarding_again_detail), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = actions.onShowOnboardingAgain,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(SETTINGS_ONBOARDING_AGAIN_TAG),
                    ) {
                        Text(stringResource(R.string.settings_onboarding_again))
                    }
                }

                Section(R.string.settings_about)
                InfoRow(stringResource(R.string.settings_version), version)
                LinkRow(
                    title = stringResource(R.string.settings_source),
                    detail = sourceUrl,
                    onClick = actions.onOpenSource,
                    modifier = Modifier.testTag(SETTINGS_SOURCE_TAG),
                )
                LinkRow(
                    title = stringResource(R.string.licenses_title),
                    detail = stringResource(R.string.settings_licenses_detail),
                    onClick = actions.onOpenLicenses,
                    modifier = Modifier.testTag(SETTINGS_LICENSES_TAG),
                )
            }
        }
    }
}

@Composable
private fun Section(
    @StringRes title: Int,
) {
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    Text(
        stringResource(title),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 1 つを選ぶ選択肢。行のどこを押しても選べる（TalkBack では「ラジオボタン」として 1 行で読まれる）。 */
@Composable
private fun <T> Choices(
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        for (option in options) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag(tag(option)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(label(option)), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LinkRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(
    title: String,
    value: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = 56.dp,
                ).padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
