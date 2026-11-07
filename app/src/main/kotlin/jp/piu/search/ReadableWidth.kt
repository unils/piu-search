// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 画面の中身の幅の上限。Material の「中くらい」の幅（600〜840 dp）の画面でも、1 列の一覧として読める幅にした。 */
val MAX_CONTENT_WIDTH: Dp = 640.dp

/**
 * 中身の幅を [MAX_CONTENT_WIDTH] までにして、画面の中央に置く。狭い画面（スマホの縦）では何もしない。
 *
 * タブレットや横向きの広い画面で幅いっぱいに広げると、名前とスイッチ・ボタン・索引が離れすぎて、目と指の移動が長くなるため。
 */
@Composable
fun ReadableWidth(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize(), content = content)
    }
}
