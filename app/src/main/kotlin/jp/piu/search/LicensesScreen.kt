// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** テストからライセンスの画面の一覧を探すための印。 */
const val LICENSES_LIST_TAG = "licenses_list"

/** テストからライセンスの全文の画面を探すための印。 */
const val LICENSE_TEXT_TAG = "license_text"

/** テストから「このアプリのライセンス」を探すための印。 */
const val APP_LICENSE_TAG = "app_license"

/** テストから依存のライセンスの全文の行を探すための印。 */
fun licenseTextTag(identifier: String): String = "license_text_$identifier"

/**
 * ライセンスの画面（ui.md の設定画面の「このアプリについて」）。
 *
 * このアプリのライセンス（Apache-2.0 の全文へ）、依存のライセンスの全文、APK に入るすべての依存（名前・座標・ライセンス）、
 * NOTICE の全文（Kuromoji と IPADIC の注意書き）を 1 本の一覧に並べる。全文は同じ画面の中で開き、「戻る」で一覧に戻る。
 * 依存の行からはブラウザを開かない（URL は文字で出す）。押してすぐ外へ出ないようにするため。
 *
 * @param licenses 出すもの。読み込む前は null で、見出しだけを出す
 */
@Composable
fun LicensesScreen(
    licenses: OssLicenses?,
    modifier: Modifier = Modifier,
) {
    // 開いている全文。APP_KEY ならこのアプリのライセンス、ほかは SPDX の識別子。向きを変えても残す
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = shown != null) { shown = null }
    Surface(modifier = modifier.fillMaxSize()) {
        ReadableWidth {
            val text =
                shown?.let { key ->
                    if (key == APP_KEY) licenses?.appLicense else licenses?.licenseTexts?.get(key)
                }
            if (text != null) {
                FullText(text)
            } else {
                LicenseList(licenses, onShow = { shown = it })
            }
        }
    }
}

@Composable
private fun LicenseList(
    licenses: OssLicenses?,
    onShow: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag(LICENSES_LIST_TAG)) {
        item(key = "header") {
            Column {
                Title(stringResource(R.string.licenses_title))
                Text(
                    stringResource(R.string.licenses_intro),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (licenses == null) return@LazyColumn
        item(key = "app") {
            LinkRow(
                title = stringResource(R.string.licenses_app),
                detail = stringResource(R.string.licenses_app_detail),
                modifier = Modifier.testTag(APP_LICENSE_TAG),
                onClick = { onShow(APP_KEY) },
            )
        }
        // 依存が使っていて、全文を入れたライセンスだけを出す
        val used = licenses.libraries.flatMap { it.licenses }.distinctBy { it.identifier }
        items(used.filter { it.identifier in licenses.licenseTexts }, key = { "text/${it.identifier}" }) { license ->
            val id = license.identifier.orEmpty()
            LinkRow(
                title = stringResource(R.string.licenses_full_text, license.name),
                detail = license.url,
                modifier = Modifier.testTag(licenseTextTag(id)),
                onClick = { onShow(id) },
            )
        }
        item(key = "libraries_title") {
            Section(pluralStringResource(R.plurals.licenses_libraries, licenses.libraries.size, licenses.libraries.size))
        }
        items(licenses.libraries, key = { "lib/${it.coordinates}" }) { library ->
            Column {
                LibraryRow(library)
                HorizontalDivider()
            }
        }
        item(key = "notice_title") {
            Column {
                Section(stringResource(R.string.licenses_notice))
                Text(
                    stringResource(R.string.licenses_notice_intro),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        paragraphs("notice", licenses.notice)
    }
}

@Composable
private fun FullText(text: String) {
    LazyColumn(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag(LICENSE_TEXT_TAG)) {
        paragraphs("text", text)
    }
}

/** [text] を段落ごとの項目にする（長い全文を 1 つの Text にすると、開くときに全体を組むため）。 */
private fun LazyListScope.paragraphs(
    keyPrefix: String,
    text: String,
) {
    itemsIndexed(reflowParagraphs(text), key = { i, _ -> "$keyPrefix/$i" }) { _, paragraph ->
        Text(
            paragraph,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LibraryRow(library: OssLibrary) {
    // TalkBack で 1 行をまとめて読ませる
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
        Text(library.title, style = MaterialTheme.typography.bodyLarge)
        Text(library.coordinates, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            library.licenses.joinToString { it.name },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LinkRow(
    title: String,
    detail: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * ライセンスの文を、画面の幅で折り返せる段落にする。
 *
 * 元の文は 70〜80 字で改行してあり、狭い画面ではそのまま出すと行が不揃いに折れる。空行で段落に分け、段落の中の改行を空白にする。
 * 箇条書き（`-`・`*`・`#` で始まる行）は前の行とつながず、新しい行にする。字句は変えず、空白と改行だけを詰める。
 */
fun reflowParagraphs(text: String): List<String> =
    text
        .replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { paragraph ->
            paragraph
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .fold(StringBuilder()) { out, line ->
                    when {
                        out.isEmpty() -> out.append(line)
                        line.first() in LIST_MARKERS -> out.append('\n').append(line)
                        else -> out.append(' ').append(line)
                    }
                }.toString()
        }.filter { it.isNotEmpty() }

private const val APP_KEY = "app"

private val LIST_MARKERS = setOf('-', '*', '#')
