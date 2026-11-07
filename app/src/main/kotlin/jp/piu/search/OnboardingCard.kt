// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** テストから初回の案内を探すための印。 */
const val ONBOARDING_TAG = "onboarding"

/** テストから初回の案内の「閉じる」を探すための印。 */
const val ONBOARDING_CLOSE_TAG = "onboarding_close"

/** テストから「アシスタントの設定を開く」を探すための印。 */
const val ONBOARDING_ASSISTANT_TAG = "onboarding_assistant"

/** テストから「タイルを追加」を探すための印。 */
const val ONBOARDING_TILE_TAG = "onboarding_tile"

/**
 * 初回の案内のボタンの操作。案内を出すときだけ作る。
 *
 * @property onOpenAssistantSettings 既定のアシスタントを選ぶ OS の設定画面を開く
 * @property onRequestTile タイルを足すよう OS に頼む。頼めない版（Android 12 以前）なら null で、足し方の文だけを出す
 * @property onDismiss 閉じる。二度と出さない
 */
class OnboardingActions(
    val onOpenAssistantSettings: () -> Unit,
    val onRequestTile: (() -> Unit)?,
    val onDismiss: () -> Unit,
)

/**
 * 初回の案内（1 枚）。デジタルアシスタントにする方法と、クイック設定のタイルを足す方法（ui.md の「呼び出し」）。
 *
 * 強制はしないので、検索窓とキーボードはそのまま使える。キーボードの上の狭い所にも収まるよう、縦に送れるようにする。
 */
@Composable
fun OnboardingCard(
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(ONBOARDING_TAG),
    ) {
        // 本文だけを縦に送り、「閉じる」は常に見えるところに置く（文字を大きくしたときやキーボードの上でも閉じられるように）
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            EntrySetupGuide(actions.onOpenAssistantSettings, actions.onRequestTile)
        }
        TextButton(
            onClick = actions.onDismiss,
            modifier =
                Modifier
                    .align(Alignment.End)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .heightIn(min = 48.dp)
                    .testTag(ONBOARDING_CLOSE_TAG),
        ) {
            Text(stringResource(R.string.onboarding_close))
        }
    }
}

/**
 * 呼び出しの入口の設定の仕方: 既定のアシスタントにする方法とボタン、タイルを足す方法とボタン。
 * 初回の案内と設定画面の「呼び出しの設定」で同じものを出す。
 *
 * @param onOpenAssistantSettings 既定のアシスタントを選ぶ OS の設定画面を開く
 * @param onRequestTile タイルを足すよう OS に頼む。頼めない版（Android 12 以前）なら null で、足し方の文だけを出す
 */
@Composable
fun EntrySetupGuide(
    onOpenAssistantSettings: () -> Unit,
    onRequestTile: (() -> Unit)?,
) {
    Text(stringResource(R.string.onboarding_assistant), style = MaterialTheme.typography.bodyMedium)
    FilledTonalButton(
        onClick = onOpenAssistantSettings,
        modifier = Modifier.heightIn(min = 48.dp).testTag(ONBOARDING_ASSISTANT_TAG),
    ) {
        Text(stringResource(R.string.onboarding_assistant_button))
    }
    Text(
        stringResource(if (onRequestTile != null) R.string.onboarding_tile else R.string.onboarding_tile_manual),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (onRequestTile != null) {
        OutlinedButton(onClick = onRequestTile, modifier = Modifier.heightIn(min = 48.dp).testTag(ONBOARDING_TILE_TAG)) {
            Text(stringResource(R.string.onboarding_tile_button))
        }
    }
}
