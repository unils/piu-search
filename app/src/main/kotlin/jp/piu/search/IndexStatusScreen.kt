// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import jp.piu.search.data.IssueCategory
import jp.piu.search.data.MaterialStage
import jp.piu.search.data.ProfileReadStatus
import jp.piu.search.data.SyncOutcome

/** テストから「索引の状態」の画面を探すための印。 */
const val INDEX_STATUS_TAG = "index_status"

/** テストから「報告用の文を見る」を探すための印。 */
const val INDEX_STATUS_REPORT_TAG = "index_status_report"

/** テストから issue のページへのリンクを探すための印。 */
const val INDEX_STATUS_ISSUES_TAG = "index_status_issues"

/** テストから報告用の文の窓の文を探すための印。 */
const val INDEX_REPORT_TEXT_TAG = "index_report_text"

/** テストから報告用の文の「コピー」を探すための印。 */
const val INDEX_REPORT_COPY_TAG = "index_report_copy"

/** テストから報告用の文の「閉じる」を探すための印。 */
const val INDEX_REPORT_CLOSE_TAG = "index_report_close"

/** テストから読めていない情報の行を探すための印。 */
const val INDEX_ISSUE_ROW_TAG = "index_issue_row"

/**
 * 「索引の状態」の画面の操作。
 *
 * @property onShowReport 「報告用の文を見る」を押したとき
 * @property onCopyReport 報告用の文の窓の「コピー」を押したとき。引数は見せている文
 * @property onCloseReport 報告用の文の窓を閉じたとき
 * @property onOpenIssues issue のページへのリンクを押したとき
 */
class IndexStatusActions(
    val onShowReport: () -> Unit = {},
    val onCopyReport: (String) -> Unit = {},
    val onCloseReport: () -> Unit = {},
    val onOpenIssues: () -> Unit = {},
)

/**
 * 「索引の状態」の画面（ui.md の「索引の状態」）。概要・アプリの一覧・ジャンルの判定・読めていない情報・報告の順に並べる。
 * 件数が 0 の項目も「なし」と出して残し、問題がないことも見えるようにする。
 *
 * @param status 出す中身
 * @param savedAtText 最後に保存できた時刻の表示。保存したことがなければ null
 * @param report 報告用の文の窓に出す文。窓を閉じていれば null。見せる文とコピーする文は同じもの
 * @param actions 操作
 */
@Composable
fun IndexStatusScreen(
    status: IndexStatus,
    savedAtText: String?,
    report: String?,
    actions: IndexStatusActions,
    modifier: Modifier = Modifier,
) {
    if (report != null) ReportDialog(report, actions)
    Surface(modifier = modifier.fillMaxSize()) {
        ReadableWidth {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                        .testTag(INDEX_STATUS_TAG),
            ) {
                Text(
                    stringResource(R.string.index_status_title),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Line(stringResource(R.string.index_status_intro), secondary = true)

                Section(R.string.index_status_overview)
                Overview(status, savedAtText)

                Section(R.string.index_status_profiles)
                if (status.profiles.isEmpty()) {
                    Line(stringResource(R.string.index_status_not_yet))
                } else {
                    status.profiles.forEach { Line(labeled(profileName(it.role), stringResource(profileText(it.status)))) }
                }

                Section(R.string.index_status_genres)
                Line(
                    stringResource(
                        when {
                            !status.genresChecked -> R.string.index_status_not_yet
                            status.genreFailure == null -> R.string.index_status_genres_ok
                            else -> R.string.index_status_genres_failed
                        },
                    ),
                )

                Section(R.string.index_status_issues)
                Tally(status.counts)
                status.issues.forEach { IssueItem(it) }

                Section(R.string.index_status_report)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = actions.onShowReport,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(INDEX_STATUS_REPORT_TAG),
                    ) { Text(stringResource(R.string.index_status_report_show)) }
                    Text(stringResource(R.string.index_status_report_hint), style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = actions.onOpenIssues,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(INDEX_STATUS_ISSUES_TAG),
                    ) { Text(stringResource(R.string.index_status_open_issues)) }
                }
            }
        }
    }
}

@Composable
private fun Overview(
    status: IndexStatus,
    savedAtText: String?,
) {
    val multiple = status.apps.size > 1
    for ((role, count) in status.apps) {
        val apps = pluralStringResource(R.plurals.index_status_apps, count, count)
        Line(if (multiple) labeled(profileName(role), apps) else apps)
    }
    // 件数は同期の結果から数える。まだ終わった同期がなければ出さない（0 と区別がつかないため）
    status.archived?.let { archived ->
        Line(
            if (archived == 0) {
                stringResource(R.string.index_status_archived_none)
            } else {
                pluralStringResource(R.plurals.index_status_archived, archived, archived)
            },
            secondary = true,
        )
    }
    Line(stringResource(R.string.index_status_last_saved, savedAtText ?: stringResource(R.string.index_status_not_yet)))
    Line(stringResource(R.string.index_status_last_sync, stringResource(outcomeText(status.outcome))))
    if (status.running) Line(stringResource(R.string.index_status_running), secondary = true)
}

@Composable
private fun Tally(tally: IssueTally) {
    Line(labeled(stringResource(R.string.index_status_category_bug), countText(tally.bug)))
    val transient = countText(tally.transient)
    Line(
        labeled(
            stringResource(R.string.index_status_category_transient),
            if (tally.retry >
                0
            ) {
                pluralStringResource(R.plurals.index_status_retry_count, tally.retry, transient, tally.retry)
            } else {
                transient
            },
        ),
    )
    Line(labeled(stringResource(R.string.index_status_category_deterministic), countText(tally.deterministic)))
}

@Composable
private fun IssueItem(row: IssueRow) {
    val issue = row.issue
    val separator = stringResource(R.string.list_separator)
    val title = if (row.role.isPersonal) row.label else row.label + separator + profileName(row.role)
    val category =
        when (issue.category) {
            IssueCategory.SUSPECTED_BUG -> {
                stringResource(R.string.index_status_category_bug)
            }

            IssueCategory.DETERMINISTIC -> {
                stringResource(R.string.index_status_category_deterministic)
            }

            IssueCategory.TRANSIENT -> {
                val retry = if (issue.retryOnce) R.string.index_status_retry_next else R.string.index_status_retried
                stringResource(R.string.index_status_category_transient) + separator + stringResource(retry)
            }
        }
    // 1 行を 1 つの項目として読ませる（TalkBack）。名前・パッケージ名・中身・理由を行ごとに飛ばさずに聞けるようにするため
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {}
                .testTag(INDEX_ISSUE_ROW_TAG),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(issue.key.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(
                R.string.index_status_issue_detail,
                stringResource(stageText(issue.stage)) + separator + category,
                stringResource(effectText(issue.stage)),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.index_status_issue_cause, issue.cause, issue.firstFailedOn),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 報告用の文の窓。全画面に文をそのまま全部出し、「コピー」と「閉じる」を置く。
 * 利用者が貼る前に何が入っているかを確かめられるよう、コピーする文と同じものを見せる。
 */
@Composable
private fun ReportDialog(
    report: String,
    actions: IndexStatusActions,
) {
    Dialog(onDismissRequest = actions.onCloseReport, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Text(
                    stringResource(R.string.index_report_title),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.index_report_warning),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        report,
                        modifier =
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                                .testTag(INDEX_REPORT_TEXT_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = actions.onCloseReport,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(INDEX_REPORT_CLOSE_TAG),
                    ) { Text(stringResource(R.string.index_report_close)) }
                    Button(
                        onClick = { actions.onCopyReport(report) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(INDEX_REPORT_COPY_TAG),
                    ) { Text(stringResource(R.string.index_report_copy)) }
                }
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
private fun Line(
    text: String,
    secondary: Boolean = false,
) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = if (secondary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun labeled(
    label: String,
    value: String,
): String = stringResource(R.string.index_status_labeled, label, value)

@Composable
private fun countText(count: Int): String =
    if (count == 0) stringResource(R.string.index_status_none) else pluralStringResource(R.plurals.index_status_count, count, count)

/** プロファイルの呼び名（個人用・仕事用・仕事用 2…）。 */
@Composable
fun profileName(role: ProfileRole): String =
    when (role.workOrdinal) {
        0 -> stringResource(R.string.profile_personal)
        1 -> stringResource(R.string.profile_work)
        else -> stringResource(R.string.index_status_work_n, role.workOrdinal)
    }

@StringRes
private fun outcomeText(outcome: SyncOutcome?): Int =
    when (outcome) {
        SyncOutcome.OK -> R.string.index_status_outcome_ok
        SyncOutcome.ISSUES -> R.string.index_status_outcome_issues
        SyncOutcome.PARTIAL -> R.string.index_status_outcome_partial
        SyncOutcome.FAILED -> R.string.index_status_outcome_failed
        null -> R.string.index_status_not_yet
    }

@StringRes
private fun profileText(status: ProfileReadStatus): Int =
    when (status) {
        ProfileReadStatus.COMPLETE -> R.string.index_status_profile_complete
        ProfileReadStatus.UNAVAILABLE -> R.string.index_status_profile_unavailable
        ProfileReadStatus.FAILED -> R.string.index_status_profile_failed
    }

@StringRes
private fun stageText(stage: MaterialStage): Int =
    when (stage) {
        MaterialStage.ENTRY -> R.string.index_status_stage_entry
        MaterialStage.LABEL -> R.string.index_status_stage_label
        MaterialStage.SIGNER -> R.string.index_status_stage_signer
        MaterialStage.KEYS -> R.string.index_status_stage_keys
    }

@StringRes
private fun effectText(stage: MaterialStage): Int =
    when (stage) {
        MaterialStage.ENTRY -> R.string.index_status_effect_entry
        MaterialStage.LABEL -> R.string.index_status_effect_label
        MaterialStage.SIGNER -> R.string.index_status_effect_signer
        MaterialStage.KEYS -> R.string.index_status_effect_keys
    }

/** 設定の「索引の状態」の 1 行の要約（例:「182 個のアプリ・問題なし」）。 */
@Composable
fun indexSummaryText(
    apps: Int,
    issues: Int,
): String {
    val appsText = pluralStringResource(R.plurals.index_status_apps, apps, apps)
    val issuesText =
        if (issues == 0) {
            stringResource(R.string.index_status_no_issues)
        } else {
            pluralStringResource(R.plurals.index_status_issue_summary, issues, issues)
        }
    return appsText + stringResource(R.string.list_separator) + issuesText
}
