// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.util.Log
import jp.piu.search.core.search.AppId

/**
 * 結果のアプリを開く（architecture.md の「起動」）。
 *
 * 仕事用プロファイルのアプリも同じ方法で開けるよう、`LauncherApps.startMainActivity` を使う。
 * 一時停止中の仕事用アプリは、OS が再開の確認を出してから開く（ui.md の「仕事用プロファイル」）。
 */
class AppLauncher(
    context: Context,
    private val users: ProfileUsers,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    /** [app] を開く。開けなかったら（消えた・無効にされた・プロファイルがない）false。 */
    fun launch(app: AppId): Boolean {
        val user = users.userFor(app.profileSerial) ?: return false
        return try {
            launcherApps.startMainActivity(ComponentName(app.packageName, app.activityName), user, null, null)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "開けなかった: ${e.javaClass.simpleName}")
            false
        } catch (e: SecurityException) {
            // 入口が書き出されていない・プロファイルが別のグループになった。索引が古い
            Log.w(TAG, "開けなかった: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * [app] の「アプリ情報」（OS の設定画面のそのアプリのページ）を開く（ui.md の「長押しのメニュー」）。開けなかったら false。
     *
     * 自分のプロファイルのアプリは `ACTION_APPLICATION_DETAILS_SETTINGS` を [from] から開く。新しいタスクにしないので、
     * 「戻る」で検索の画面に戻る。ほかのプロファイルのアプリは、その intent では自分のプロファイルの設定が開くので、
     * `LauncherApps.startAppDetailsActivity` で、そのプロファイルの設定を開く。
     */
    fun showDetails(
        app: AppId,
        from: Activity,
    ): Boolean {
        val user = users.userFor(app.profileSerial) ?: return false
        return try {
            if (user == Process.myUserHandle()) {
                from.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
            } else {
                launcherApps.startAppDetailsActivity(ComponentName(app.packageName, app.activityName), user, null, null)
            }
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "アプリ情報を開けなかった: ${e.javaClass.simpleName}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "アプリ情報を開けなかった: ${e.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        const val TAG = "PiuLaunch"
    }
}
