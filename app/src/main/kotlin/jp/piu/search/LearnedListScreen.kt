// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.SearchIndex

/** テストから学習の一覧を探すための印。 */
const val LEARNED_LIST_TAG = "learned_list"

/** テストから「書き出す」を探すための印。 */
const val LEARNED_EXPORT_TAG = "learned_export"

/** テストから「読み込む」を探すための印。 */
const val LEARNED_IMPORT_TAG = "learned_import"

/** テストから「すべて消す」を探すための印。 */
const val LEARNED_CLEAR_TAG = "learned_clear"

/** テストから全消去の確かめの「消す」を探すための印。 */
const val LEARNED_CLEAR_CONFIRM_TAG = "learned_clear_confirm"

/**
 * 学習の一覧の 1 行（ui.md の設定画面の「学習」）。
 *
 * @property pair 組。消すときに使う
 * @property label アプリの表示名。索引にない（消えた）アプリならパッケージ名
 * @property installed 索引にあるアプリか
 * @property work 仕事用プロファイルのアプリか
 * @property count 記録した回数
 * @property lastUsed 最後に使った日（表示用の文字列）
 * @property rescued 救済になったことがあるか（名前で出なくても、この語でアプリが出る）
 * @property suppressed 「この語では出さない」の印
 */
data class LearnedEntry(
    val pair: LearnedPair,
    val label: String,
    val installed: Boolean,
    val work: Boolean,
    val count: Int,
    val lastUsed: String,
    val rescued: Boolean,
    val suppressed: Boolean,
)

/**
 * 学習 [memory] を一覧の行にする。新しく使った順（誤った組は、覚えた直後に気づくことが多いため）。
 *
 * @param index アプリの表示名を引く索引
 * @param ownSerial このアプリが動いているプロファイルの番号。ほかは仕事用として印を出す
 * @param formatDate 時刻（エポックからのミリ秒）を日付の文字列にする
 */
fun learnedEntries(
    memory: LearnedMemory,
    index: SearchIndex,
    ownSerial: Long,
    formatDate: (Long) -> String,
): List<LearnedEntry> =
    memory.records.values
        .sortedWith(compareByDescending<LearnedRecord> { it.lastUsedAt }.thenBy { it.query.value })
        .map { record ->
            val label = index.labelOf(record.app)
            LearnedEntry(
                pair = record.pair,
                label = label ?: record.app.packageName,
                installed = label != null,
                work = record.app.profileSerial != ownSerial,
                count = record.count,
                lastUsed = formatDate(record.lastUsedAt),
                rescued = record.rescued,
                suppressed = record.suppressed,
            )
        }

/**
 * 学習の一覧の画面: 組の一覧・個別の削除・全消去（確かめつき）・書き出し・読み込み。
 *
 * 操作のボタンは片手で届くよう下に置く（検索画面と同じ）。
 *
 * @param entries 行。読み込む前は null で、何も出さない（「まだ何も覚えていません」を一瞬出さないため）
 * @param onDelete 行の削除を押したとき。確かめない（1 件で、誤りに気づいて消す操作のため）
 * @param onClearAll 全消去を確かめたとき
 * @param onExport 「書き出す」を押したとき
 * @param onImport 「読み込む」を押したとき
 * @param message 下に短く出す知らせ（書き出し・読み込みの結果）。出し終えたら [onMessageShown] を呼ぶ
 */
@Composable
fun LearnedListScreen(
    entries: List<LearnedEntry>?,
    onDelete: (LearnedEntry) -> Unit,
    onClearAll: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val currentShown by rememberUpdatedState(onMessageShown)
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            currentShown()
        }
    }
    // 向きを変えても確かめの窓を残す
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxSize()) {
        ReadableWidth {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // 見出しと説明も一覧と一緒に送る（文字を大きくしたときに、一覧の場所を食わないため）
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag(LEARNED_LIST_TAG)) {
                        item(key = "header") { Header() }
                        when {
                            entries == null -> {}

                            entries.isEmpty() -> {
                                item(key = "empty") {
                                    Text(
                                        stringResource(R.string.learned_empty),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }

                            else -> {
                                items(entries, key = { entryKey(it) }) {
                                    LearnedRow(it, onDelete)
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                }
                Actions(
                    hasEntries = !entries.isNullOrEmpty(),
                    loaded = entries != null,
                    onExport = onExport,
                    onImport = onImport,
                    onClear = { confirmingClear = true },
                )
            }
        }
    }
    if (confirmingClear) {
        ClearDialog(
            count = entries?.size ?: 0,
            onConfirm = {
                confirmingClear = false
                onClearAll()
            },
            onDismiss = { confirmingClear = false },
        )
    }
}

private fun entryKey(entry: LearnedEntry): String =
    entry.pair.app.let { "${it.profileSerial}/${it.packageName}/${it.activityName}/${entry.pair.query.value}" }

@Composable
private fun Header() {
    Column {
        Text(
            stringResource(R.string.learned_title),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.learned_intro),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LearnedRow(
    entry: LearnedEntry,
    onDelete: (LearnedEntry) -> Unit,
) {
    val query = stringResource(R.string.learned_query, entry.pair.query.value)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // TalkBack で 1 行をまとめて読ませる
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(query, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                appLine(entry),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detailLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.suppressed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onDelete(entry) }) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.learned_delete, entry.pair.query.value, entry.label),
            )
        }
    }
}

@Composable
private fun appLine(entry: LearnedEntry): String {
    val notes =
        listOfNotNull(
            stringResource(R.string.profile_work).takeIf { entry.work },
            stringResource(R.string.learned_not_installed).takeIf { !entry.installed },
        )
    return (listOf(entry.label) + notes).joinToString(stringResource(R.string.list_separator))
}

@Composable
private fun detailLine(entry: LearnedEntry): String {
    // 印だけの組（回数 0）は、回数と日付より印を先に伝える
    if (entry.suppressed) return stringResource(R.string.learned_suppressed)
    return listOfNotNull(
        pluralStringResource(R.plurals.learned_count, entry.count, entry.count),
        stringResource(R.string.learned_last_used, entry.lastUsed),
        stringResource(R.string.learned_rescued).takeIf { entry.rescued },
    ).joinToString(stringResource(R.string.list_separator))
}

@Composable
private fun Actions(
    hasEntries: Boolean,
    loaded: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClear: () -> Unit,
) {
    // 文字を大きくしても切れないよう、入りきらなければ折り返す
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onClear,
            enabled = hasEntries,
            modifier = Modifier.heightIn(min = 48.dp).testTag(LEARNED_CLEAR_TAG),
            contentPadding = BUTTON_PADDING,
        ) { Text(stringResource(R.string.learned_clear)) }
        // 読み込みは、読み込みが済むまで押させない（今の学習と合わせるため。押しても記録のスレッドで順に待つが、空の一覧の上で押させない）
        OutlinedButton(
            onClick = onImport,
            enabled = loaded,
            modifier = Modifier.heightIn(min = 48.dp).testTag(LEARNED_IMPORT_TAG),
            contentPadding = BUTTON_PADDING,
        ) { Text(stringResource(R.string.learned_import)) }
        FilledTonalButton(
            onClick = onExport,
            enabled = hasEntries,
            modifier = Modifier.heightIn(min = 48.dp).testTag(LEARNED_EXPORT_TAG),
            contentPadding = BUTTON_PADDING,
        ) { Text(stringResource(R.string.learned_export)) }
    }
}

@Composable
private fun ClearDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.learned_clear_title)) },
        text = { Text(pluralStringResource(R.plurals.learned_clear_message, count, count)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(LEARNED_CLEAR_CONFIRM_TAG)) {
                Text(stringResource(R.string.learned_clear_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.learned_clear_cancel)) } },
    )
}

// 既定（左右 24 dp）では 360 dp の幅に 3 つ並ばないので詰める
private val BUTTON_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
