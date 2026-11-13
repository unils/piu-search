// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import jp.piu.search.core.search.AppId
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

/** テストから検索窓を探すための印。 */
const val SEARCH_FIELD_TAG = "search_field"

/** テストから結果の一覧を探すための印。 */
const val RESULTS_TAG = "results"

/** テストから「見つかりません」を探すための印。 */
const val NO_MATCH_TAG = "no_match"

/** テストから「すべてのアプリ」のボタン（検索窓の横）を探すための印。 */
const val ALL_APPS_BUTTON_TAG = "all_apps_button"

/** テストから「すべてのアプリ」の一覧を探すための印。 */
const val ALL_APPS_LIST_TAG = "all_apps_list"

/** テストから頭文字の索引を探すための印。 */
const val SECTION_INDEX_TAG = "section_index"

/** テストから「仕事用に N 件」のボタンを探すための印。 */
const val WORK_MATCHES_TAG = "work_matches"

/** テストから「個人用｜仕事用」の切り替えを探すための印。 */
const val PROFILE_SWITCH_TAG = "profile_switch"

/** テストから一時停止中の知らせを探すための印。 */
const val WORK_PAUSED_TAG = "work_paused"

/** テストから設定のボタン（検索窓の中）を探すための印。 */
const val SETTINGS_BUTTON_TAG = "settings_button"

/** テストから救済の知らせを探すための印。 */
const val RESCUE_NOTICE_TAG = "rescue_notice"

/** テストから長押しのメニューの項目を探すための印。 */
fun rowActionTag(action: RowAction): String = "row_action_${action.name}"

/**
 * 「個人用｜仕事用」の切り替えの状態。仕事用プロファイルがあるときだけ作る。
 *
 * @property selected 選んでいるプロファイル
 * @property workPaused 仕事用プロファイルが一時停止中か
 */
data class ProfileSwitch(
    val selected: ProfileTab,
    val workPaused: Boolean,
)

/**
 * 検索の画面の中身。既定では結果の一覧の下に検索窓を置く。
 *
 * 片手で届く位置に置くため下にする（ui.md・要件 U8）。safeDrawing はキーボード（IME）の高さを含むので、
 * キーボードが開くと検索窓がその上に上がる。結果は下から上へ並べ、1 位を検索窓の直上に置く。
 * 設定で検索窓を上に置いたときは、結果を上から下へ並べ、1 位を検索窓の直下に置く（どちらでも 1 位は検索窓の隣）。
 *
 * @param content 検索窓の上に出すもの
 * @param icons 結果の行のアイコン
 * @param onOpen 結果の行を押したとき
 * @param onSearchAction 確定キー（IME の「検索」）を押したとき
 * @param showingAllApps すべてのアプリを出しているか（検索窓の横のボタンの状態）
 * @param onShowAllApps すべてのアプリのボタンを押したとき。引数は出すか
 * @param profileSwitch 「個人用｜仕事用」の切り替え。仕事用プロファイルがなければ null で、出さない
 * @param onSelectProfile 切り替えか「仕事用に N 件」を押したとき
 * @param autoFocus 開いたら検索窓に入力を向け、キーボードを出す。画面のテストでは、点滅するカーソルを写さないよう切る
 * @param rowMenu 結果の行の長押しのメニューに出す項目。空なら長押しに応じない
 * @param onRowAction 長押しのメニューの項目を選んだとき
 * @param onboarding 初回の案内。出すときだけ渡す。検索語が空の間、最近・よく使うアプリの代わりに出す
 * @param focusKey 変わるたびに、検索窓へ入力を向け直してキーボードを出す（呼び出し直されたとき）
 * @param notices 出す救済の知らせ。先頭から 1 つずつ、［取り消す］つきで数秒出す。打ち始めたら下げる（結果の 1 位に重なるため）
 * @param onUndoNotice 知らせの［取り消す］を押したとき
 * @param onNoticeDone 知らせを下げたとき（押した・時間が過ぎた・打ち始めた）。呼ばれたら [notices] から除く
 * @param onRowCapacity 結果の一覧に収まる行の数が決まったとき・変わったとき（学習の「見えていた」上位の件数に使う）
 * @param onScrolled 結果の一覧をスクロールしたとき
 * @param searchBarOnTop 検索窓を画面の上に置く（設定）
 * @param showReasons 出た理由を行に出す（設定）。出さなくても、長押しのメニューの出し分けには理由を使う
 * @param workPausedNotice 仕事用アプリが一時停止中である知らせを出す。既定は、切り替えで仕事用を選んでいて一時停止中のとき
 * @param onOpenSettings 設定のボタンを押したとき。null ならボタンを出さない。ボタンは検索語が空の間だけ、検索窓の中に小さく出す
 */
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    content: SearchContent,
    icons: AppIcons,
    onOpen: (AppId) -> Unit,
    onSearchAction: () -> Unit,
    modifier: Modifier = Modifier,
    showingAllApps: Boolean = false,
    onShowAllApps: (Boolean) -> Unit = {},
    profileSwitch: ProfileSwitch? = null,
    onSelectProfile: (ProfileTab) -> Unit = {},
    autoFocus: Boolean = true,
    rowMenu: (ResultRow) -> List<RowAction> = { emptyList() },
    onRowAction: (ResultRow, RowAction) -> Unit = { _, _ -> },
    onboarding: OnboardingActions? = null,
    focusKey: Int = 0,
    notices: List<RescueNotice> = emptyList(),
    onUndoNotice: (RescueNotice) -> Unit = {},
    onNoticeDone: (RescueNotice) -> Unit = {},
    onRowCapacity: (Int) -> Unit = {},
    onScrolled: () -> Unit = {},
    searchBarOnTop: Boolean = false,
    showReasons: Boolean = true,
    workPausedNotice: Boolean = profileSwitch?.let { it.selected == ProfileTab.WORK && it.workPaused } ?: false,
    onOpenSettings: (() -> Unit)? = null,
) {
    val rows = RowHandlers(icons, onOpen, rowMenu, onRowAction, onScrolled, showReasons, searchBarOnTop)
    val snackbar = remember { SnackbarHostState() }
    RescueNoticeEffect(notices.firstOrNull(), snackbar, onUndoNotice, onNoticeDone)
    // 打ち始めたら知らせを下げる。知らせは検索窓の直上（結果の 1 位の位置）に出るため
    LaunchedEffect(query) { if (query.isNotEmpty()) snackbar.currentSnackbarData?.dismiss() }
    // testTag を画面の外（UiAutomator）から resource-id として引けるようにする。
    // R8 を通した版の計器テスト（:release-test）と起動の計測（:benchmark）が、アプリの外から検索窓を探すため
    Surface(modifier = modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        // 広い画面では幅を抑えて中央に置く（ReadableWidth）
        ReadableWidth(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(modifier = Modifier.fillMaxSize()) {
                val bar: @Composable () -> Unit = {
                    SearchBarRow(query, onQueryChange, onSearchAction, showingAllApps, onShowAllApps, autoFocus, focusKey, onOpenSettings)
                }
                val profile: @Composable () -> Unit = {
                    if (searchBarOnTop) {
                        profileSwitch?.let { ProfileSwitchRow(it.selected, onSelectProfile) }
                        if (workPausedNotice) WorkPausedNotice()
                    } else {
                        if (workPausedNotice) WorkPausedNotice()
                        profileSwitch?.let { ProfileSwitchRow(it.selected, onSelectProfile) }
                    }
                }
                // 検索窓とプロファイルの切り替えを、結果の一覧を挟んで上か下に置く。検索窓に近い側から結果を並べる
                if (searchBarOnTop) {
                    bar()
                    profile()
                }
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = if (searchBarOnTop) Alignment.TopCenter else Alignment.BottomCenter,
                ) {
                    val capacity = (maxHeight / ROW_MIN_HEIGHT).toInt()
                    val currentCapacity by rememberUpdatedState(onRowCapacity)
                    LaunchedEffect(capacity) { currentCapacity(capacity) }
                    when {
                        // 案内は検索語が空のときだけ出す。打てば結果に替わり、消せばまた出る（閉じるまで）
                        onboarding != null && query.isEmpty() && !showingAllApps -> OnboardingCard(onboarding)

                        else -> Content(content, rows, onShowAllApps, onSelectProfile)
                    }
                    SnackbarHost(snackbar, modifier = Modifier.testTag(RESCUE_NOTICE_TAG))
                }
                if (!searchBarOnTop) {
                    profile()
                    bar()
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchAction: () -> Unit,
    showingAllApps: Boolean,
    onShowAllApps: (Boolean) -> Unit,
    autoFocus: Boolean,
    focusKey: Int,
    onOpenSettings: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchField(query, onQueryChange, onSearchAction, showingAllApps, autoFocus, focusKey, onOpenSettings, Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        // 検索窓の横に常に置く。下から並べる一覧の端に置くと画面の上端になり、親指が届かないため（ui.md）
        FilledTonalIconToggleButton(
            checked = showingAllApps,
            onCheckedChange = onShowAllApps,
            modifier = Modifier.size(48.dp).testTag(ALL_APPS_BUTTON_TAG),
        ) {
            Icon(painterResource(R.drawable.ic_apps), contentDescription = stringResource(R.string.all_apps))
        }
    }
}

/** 結果の行の表示と操作。一覧の部品へまとめて渡す。 */
private class RowHandlers(
    val icons: AppIcons,
    val onOpen: (AppId) -> Unit,
    val menuFor: (ResultRow) -> List<RowAction>,
    val onAction: (ResultRow, RowAction) -> Unit,
    val onScrolled: () -> Unit,
    val showReasons: Boolean,
    val fromTop: Boolean,
)

/**
 * 救済の知らせ [notice] を［取り消す］つきで [NOTICE_MILLIS] だけ出す。
 *
 * Material の Snackbar の長さは 4 秒か 10 秒しか選べないので、期限なしで出して自分で下げる。
 */
@Composable
private fun RescueNoticeEffect(
    notice: RescueNotice?,
    snackbar: SnackbarHostState,
    onUndo: (RescueNotice) -> Unit,
    onDone: (RescueNotice) -> Unit,
) {
    if (notice == null) return
    val message = stringResource(R.string.rescue_learned, notice.pair.query.value, notice.label)
    val undo = stringResource(R.string.rescue_undo)
    val currentUndo by rememberUpdatedState(onUndo)
    val currentDone by rememberUpdatedState(onDone)
    LaunchedEffect(notice.undoKey) {
        val result =
            withTimeoutOrNull(NOTICE_MILLIS) {
                snackbar.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Indefinite)
            }
        if (result == SnackbarResult.ActionPerformed) currentUndo(notice)
        currentDone(notice)
    }
}

@Composable
private fun Content(
    content: SearchContent,
    rows: RowHandlers,
    onShowAllApps: (Boolean) -> Unit,
    onSelectProfile: (ProfileTab) -> Unit,
) {
    when (content) {
        SearchContent.Waiting -> {}

        is SearchContent.NoMatch -> {
            NoMatch(
                content.workMatches,
                onShowAllApps = { onShowAllApps(true) },
                onShowWork = { onSelectProfile(ProfileTab.WORK) },
            )
        }

        is SearchContent.AllApps -> {
            AllAppsList(content, rows)
        }

        is SearchContent.Results -> {
            ResultList(content.rows, rows)
        }

        is SearchContent.Suggestions -> {
            SuggestionList(content, rows)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchAction: () -> Unit,
    showingAllApps: Boolean,
    autoFocus: Boolean,
    focusKey: Int,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            modifier
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .testTag(SEARCH_FIELD_TAG),
        placeholder = { Text(stringResource(R.string.search_hint)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.clear_query))
                }
            } else if (onOpenSettings != null) {
                // 設定の入口。目立たせないよう、検索語が空の間だけ検索窓の中に、文字より薄い色で置く。
                // 呼び出し直すと検索語は空に戻るので、いつでも 1 回の操作で届く
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(SETTINGS_BUTTON_TAG)) {
                    Icon(
                        painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.settings_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
    )
    if (autoFocus) {
        // 検索窓に焦点が来た（打てるようになった）ときを、画面を描き終えた時刻として OS に知らせる（reportFullyDrawn）。
        // 起動の計測（:benchmark）が「呼び出してから打てるまで」を、最初の描画とは別に測るため。1 つの画面で 1 回だけ効く
        ReportDrawnWhen { focused }
        // 開いたとき・すべてのアプリから戻ったときは打てるようにし、すべてのアプリを出したら一覧が見えるようにキーボードを下げる
        LaunchedEffect(showingAllApps, focusKey) {
            if (showingAllApps) {
                focusManager.clearFocus()
                keyboard?.hide()
            } else {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }
    }
}

@Composable
private fun ProfileSwitchRow(
    selected: ProfileTab,
    onSelect: (ProfileTab) -> Unit,
) {
    val labels = mapOf(ProfileTab.PERSONAL to R.string.profile_personal, ProfileTab.WORK to R.string.profile_work)
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .testTag(PROFILE_SWITCH_TAG),
    ) {
        ProfileTab.entries.forEachIndexed { position, tab ->
            SegmentedButton(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(position, ProfileTab.entries.size),
            ) {
                Text(stringResource(labels.getValue(tab)))
            }
        }
    }
}

@Composable
private fun WorkPausedNotice() {
    // 再開の操作はこのアプリではできない（既定のホームアプリだけ）。開こうとすれば OS が再開を尋ねることを伝える
    Text(
        stringResource(R.string.work_paused),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .testTag(WORK_PAUSED_TAG),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun NoMatch(
    workMatches: Int,
    onShowAllApps: () -> Unit,
    onShowWork: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp).testTag(NO_MATCH_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.no_match),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (workMatches > 0) {
            OutlinedButton(onClick = onShowWork, modifier = Modifier.heightIn(min = 48.dp).testTag(WORK_MATCHES_TAG)) {
                Text(pluralStringResource(R.plurals.work_matches, workMatches, workMatches), textAlign = TextAlign.Center)
            }
        }
        // 検索で出なかったアプリを選ぶ入口（学習の材料）。大きめにする（ui.md の「検索画面」）
        FilledTonalButton(onClick = onShowAllApps, modifier = Modifier.heightIn(min = 56.dp)) {
            Icon(painterResource(R.drawable.ic_apps), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.all_apps), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AllAppsList(
    content: SearchContent.AllApps,
    rows: RowHandlers,
) {
    if (content.rows.isEmpty()) {
        CenteredMessage(stringResource(R.string.all_apps_empty))
        return
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 見出しの行を挟むので、行の位置を一覧の項目の位置に直しておく（見出し i 個目の前には i 個の見出しがある）
    val itemOfSection = content.sections.mapIndexed { i, section -> section.firstRow + i }
    Row(modifier = Modifier.fillMaxSize()) {
        // 名前の順の一覧は、上から読む向きのまま並べる（下から並べる検索の結果と違い、順に探すため）
        LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight().testTag(ALL_APPS_LIST_TAG), state = state) {
            content.sections.forEachIndexed { i, section ->
                val end = content.sections.getOrNull(i + 1)?.firstRow ?: content.rows.size
                item(key = "section/${section.label}") { SectionTitle(section.label) }
                resultItems(content.rows.subList(section.firstRow, end), rows)
            }
        }
        SectionIndex(
            labels = content.sections.map { it.label },
            onJump = { section -> scope.launch { state.scrollToItem(itemOfSection[section]) } },
        )
    }
}

/**
 * 右端の頭文字の索引。押すか、なぞった位置の見出しへ飛ぶ。
 *
 * 字の大きさは高さに合わせる（キーボードを出したままでも収まるように）。入りきらなければ、何個かおきに出す。
 * 1 字ずつの高さは 48 dp に届かないので、索引の全体を 1 つの部品（なぞって選ぶ帯）にし、幅を 48 dp 取る（ui.md の「押せる部分は 48 dp 以上」）。
 * TalkBack では 1 字ずつを押させず、索引を 1 つの項目として読ませ、見出しへ飛ぶ操作を「操作」の一覧に並べる
 * （何個かおきに出したときも、すべての見出しへ飛べる）。見出しの行は heading なので、見出しの間を移る TalkBack の操作でも飛べる。
 */
@Composable
private fun SectionIndex(
    labels: List<String>,
    onJump: (Int) -> Unit,
) {
    val current = rememberUpdatedState(onJump)
    val description = stringResource(R.string.section_index)
    val resources = LocalResources.current
    val actions =
        remember(labels, resources) {
            labels.mapIndexed { i, label ->
                CustomAccessibilityAction(resources.getString(R.string.section_jump, label)) {
                    current.value(i)
                    true
                }
            }
        }
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(MIN_TOUCH_DP.dp)
                .padding(vertical = 4.dp)
                .testTag(SECTION_INDEX_TAG)
                .clearAndSetSemantics {
                    contentDescription = description
                    customActions = actions
                },
    ) {
        val step = ceil(MIN_INDEX_ITEM_DP / (maxHeight.value / labels.size)).toInt().coerceAtLeast(1)
        val shown = labels.indices.filter { it % step == 0 }
        // 文字の大きさの設定に左右されない大きさにする（索引は高さに収めることが先）
        val fontSize = with(LocalDensity.current) { minOf(maxHeight / shown.size * 0.7f, MAX_INDEX_FONT_DP.dp).toSp() }
        Column(
            modifier =
                Modifier.fillMaxSize().pointerInput(shown) {
                    var last = -1

                    fun jumpAt(y: Float) {
                        val slot = (y / size.height * shown.size).toInt().coerceIn(0, shown.size - 1)
                        if (slot != last) current.value(shown[slot])
                        last = slot
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        last = -1
                        jumpAt(down.position.y)
                        do {
                            val event = awaitPointerEvent()
                            event.changes.filter { it.pressed }.forEach {
                                jumpAt(it.position.y)
                                it.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            for (section in shown) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(labels[section], fontSize = fontSize, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ResultList(
    results: List<ResultRow>,
    rows: RowHandlers,
) {
    val state = rememberLazyListState()
    // 打ち直したら、1 位（検索窓の直上）が見える位置に戻す
    LaunchedEffect(results) { state.scrollToItem(0) }
    val onScrolled by rememberUpdatedState(rows.onScrolled)
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.filter { it }.collect { onScrolled() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag(RESULTS_TAG),
        state = state,
        reverseLayout = !rows.fromTop,
    ) {
        resultItems(results, rows)
    }
}

@Composable
private fun SuggestionList(
    content: SearchContent.Suggestions,
    rows: RowHandlers,
) {
    if (content.recent.isEmpty() && content.frequent.isEmpty()) {
        CenteredMessage(stringResource(R.string.suggestions_none))
        return
    }
    val recentTitle = stringResource(R.string.suggestions_recent)
    val frequentTitle = stringResource(R.string.suggestions_frequent)
    LazyColumn(modifier = Modifier.fillMaxWidth().testTag(RESULTS_TAG), reverseLayout = !rows.fromTop) {
        // 下から上へ並べるときは、見出しを各組の後に置くと上に出る。上から並べるときは前に置く
        val fromTop = rows.fromTop
        if (fromTop && content.recent.isNotEmpty()) item(key = "recent_title") { SectionTitle(recentTitle) }
        resultItems(content.recent, rows)
        if (!fromTop && content.recent.isNotEmpty()) item(key = "recent_title") { SectionTitle(recentTitle) }
        if (fromTop && content.frequent.isNotEmpty()) item(key = "frequent_title") { SectionTitle(frequentTitle) }
        resultItems(content.frequent, rows)
        if (!fromTop && content.frequent.isNotEmpty()) item(key = "frequent_title") { SectionTitle(frequentTitle) }
    }
}

private fun LazyListScope.resultItems(
    results: List<ResultRow>,
    rows: RowHandlers,
) {
    // 鍵は Bundle に入る型にする（一覧の位置を保存するため）
    items(results, key = { "${it.app.profileSerial}/${it.app.packageName}/${it.app.activityName}" }) { row ->
        ResultRowItem(row, rows)
    }
}

@Composable
private fun ResultRowItem(
    row: ResultRow,
    rows: RowHandlers,
) {
    val resources = LocalResources.current
    val reason = row.reason?.takeIf { rows.showReasons }?.let { describeReason(it, resources) }
    val actions = rows.menuFor(row)
    var menuOpen by remember { mutableStateOf(false) }
    // メニューは行のすぐ下に出す。Box で行とメニューの位置を揃える
    Box {
        // combinedClickable は子の文をまとめて読ませるので、TalkBack では「名前、出た理由」と読まれる。
        // 長押しは TalkBack の操作の一覧にも「メニュー」として出る
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_MIN_HEIGHT)
                    .combinedClickable(
                        onLongClickLabel = stringResource(R.string.row_menu),
                        onLongClick = if (actions.isEmpty()) null else ({ menuOpen = true }),
                        onClick = { rows.onOpen(row.app) },
                    ).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowContent(row, reason, rows.icons)
        }
        // 行の左端の余白に揃える
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = DpOffset(16.dp, 0.dp)) {
            for (action in actions) {
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    onClick = {
                        menuOpen = false
                        rows.onAction(row, action)
                    },
                    modifier = Modifier.testTag(rowActionTag(action)),
                )
            }
        }
    }
}

@Composable
private fun RowContent(
    row: ResultRow,
    reason: String?,
    icons: AppIcons,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(row.app, icons)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (reason != null) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AppIcon(
    app: AppId,
    icons: AppIcons,
) {
    val modifier = Modifier.size(LauncherAppIcons.ICON_SIZE_DP.dp)
    val bitmap by produceState(icons.cached(app), app) { if (value == null) value = icons.load(app) }
    val current = bitmap
    // 読み込む前も同じ大きさを取り、読み込んだときに名前の位置が動かないようにする
    if (current == null) Box(modifier) else Image(current, contentDescription = null, modifier = modifier)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** 結果の 1 行の高さの下限。一覧に収まる行の数を見積もる。 */
private val ROW_MIN_HEIGHT = 56.dp

/** 救済の知らせを出す時間（ミリ秒）。「数秒」（ui.md）。読んで［取り消す］を押せる長さにした（仮）。 */
private const val NOTICE_MILLIS = 6_000L

/** 押せる部分の大きさの下限（dp。ui.md）。 */
private const val MIN_TOUCH_DP = 48

/** 頭文字の索引の 1 字に取る高さの下限（dp）。これより低くなるなら、何個かおきに出す。 */
private const val MIN_INDEX_ITEM_DP = 16f

/** 頭文字の索引の字の大きさの上限（dp）。 */
private const val MAX_INDEX_FONT_DP = 14
