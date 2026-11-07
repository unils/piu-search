// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * クイック設定のタイル。押すと通知の領域を閉じて検索の画面を開く（ui.md の「呼び出し」）。
 *
 * 状態を持たない入口なので、タイルの表示（オン・オフ）は OS の既定のままにする。
 * 鍵がかかっているときは、OS が解錠を求めてから開く（`startActivityAndCollapse` の中で SystemUI が扱う）。
 */
class SearchTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = searchIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // target SDK 34 以上では、Intent 版は UnsupportedOperationException を投げる（android-platform.md の「クイック設定タイル」）
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startWithIntent(intent)
        }
    }

    // API 33 以下には PendingIntent 版がないので、非推奨の Intent 版を使う。警告はここだけで抑える
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startWithIntent(intent: Intent) = startActivityAndCollapse(intent)

    /** タイルから開く intent。 */
    companion object {
        /** タイルから検索の画面を開く intent。サービスには積む先のタスクがないので、新しいタスクを指定する。 */
        fun searchIntent(context: Context): Intent = Intent(context, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
