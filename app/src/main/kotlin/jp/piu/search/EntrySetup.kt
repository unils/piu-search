// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Activity
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi

/**
 * 呼び出しの入口（デジタルアシスタント・クイック設定のタイル）を、利用者に OS で設定してもらう操作。初回の案内のボタンから使う。
 *
 * 既定のアシスタントは、アプリから頼んで変えられない（PermissionController の roles.xml で ASSISTANT は requestable="false"。
 * `RoleManager.createRequestRoleIntent` は断られる）。そのため設定の画面を開き、利用者に選んでもらう。
 */
class EntrySetup(
    private val activity: Activity,
) {
    /**
     * 既定のアシスタントを選ぶ画面を開く。開けなかったら false。
     *
     * 1. `Settings.ACTION_VOICE_INPUT_SETTINGS`: AOSP の Settings の `ManageAssistActivity`（「アシストと音声入力」）。
     *    先頭が「デジタル アシスタント アプリ」の項目
     * 2. `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS`: 「デフォルトのアプリ」の一覧（Android 10 以降は PermissionController）
     * 3. 設定の最初の画面
     *
     * アシスタントの画面そのもの（PermissionController の `MANAGE_DEFAULT_APP`）は MANAGE_ROLE_HOLDERS の権限が要り、開けない。
     * 端末の作り手が画面を外していることがあるので、順に試す。
     */
    fun openAssistantSettings(): Boolean {
        val candidates =
            listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
        for (action in candidates) {
            try {
                activity.startActivity(Intent(action))
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "設定の画面がない: $action: ${e.javaClass.simpleName}")
            } catch (e: SecurityException) {
                // 書き出されていない画面を、ほかのアプリが同じ action で受けている場合
                Log.i(TAG, "設定の画面を開けない: $action: ${e.javaClass.simpleName}")
            }
        }
        return false
    }

    /** タイルを足すよう OS に頼めるか（Android 13 以降の `StatusBarManager.requestAddTileService`）。 */
    val canRequestTile: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * クイック設定にタイルを足すよう OS に頼む。OS が確認の画面を出し、利用者が決める。権限は要らない。
     * すでに足してあれば、そのことを知らせる。[canRequestTile] が false なら何もしない。
     */
    fun requestAddTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestAddTileApi33()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAddTileApi33() {
        val statusBar = activity.getSystemService(StatusBarManager::class.java) ?: return
        statusBar.requestAddTileService(
            ComponentName(activity, SearchTileService::class.java),
            activity.getString(R.string.tile_label),
            Icon.createWithResource(activity, R.drawable.ic_tile),
            activity.mainExecutor,
        ) { result ->
            Log.i(TAG, "タイルの追加の結果: $result")
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Toast.makeText(activity, R.string.tile_already_added, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val TAG = "PiuEntrySetup"
    }
}
