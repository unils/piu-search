// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle

/**
 * パッケージの追加・変更・削除の知らせ（`LauncherApps.Callback`）を、アプリの寿命で受ける。
 *
 * 知らせは [onChanged] に渡す（索引の書き手が溜め、検索画面が前面のときだけ同期する。裏で同期しないのは電池と速さのため）。
 * 同じ知らせで [invalidateIcons] も呼ぶ。同期を待たずに、更新したアプリの古いアイコンを出さないため（0.2.0 と同じ）。
 *
 * @param launcherApps 登録先。テストでは Robolectric の影を通す
 */
class PackageWatcher(
    private val launcherApps: LauncherApps,
    private val onChanged: (Collection<String>, UserHandle) -> Unit,
    private val invalidateIcons: (Set<String>) -> Unit,
) {
    private var started = false

    private val callback =
        object : LauncherApps.Callback() {
            override fun onPackageRemoved(
                packageName: String,
                user: UserHandle,
            ) = changed(setOf(packageName), user)

            override fun onPackageAdded(
                packageName: String,
                user: UserHandle,
            ) = changed(setOf(packageName), user)

            override fun onPackageChanged(
                packageName: String,
                user: UserHandle,
            ) = changed(setOf(packageName), user)

            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = changed(packageNames.toSet(), user)

            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = changed(packageNames.toSet(), user)
        }

    /** 受け取りを始める。2 度呼んでも 1 つしか登録しない。画面のスレッドから呼ぶ。 */
    fun start() {
        if (started) return
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        started = true
    }

    /** 受け取りを終える。テストで片付けるために使う。 */
    fun close() {
        if (!started) return
        launcherApps.unregisterCallback(callback)
        started = false
    }

    private fun changed(
        packages: Set<String>,
        user: UserHandle,
    ) {
        invalidateIcons(packages)
        onChanged(packages, user)
    }
}
