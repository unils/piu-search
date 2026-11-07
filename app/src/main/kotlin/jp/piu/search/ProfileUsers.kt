// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * プロファイルのシリアル番号（索引と記録に保存する番号）から、OS のプロファイルの識別子を引く。仕事用プロファイルの状態も引く。
 *
 * 起動とアイコンの読み込みには `UserHandle` が要る。番号で保存するのは、消したプロファイルの番号が使い回されないため
 * （architecture.md の「プロファイル」）。
 */
class ProfileUsers(
    context: Context,
) {
    private val userManager = context.getSystemService(UserManager::class.java)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    /** [serial] のプロファイル。消えたプロファイルなら null。 */
    fun userFor(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    /** このアプリが動いているプロファイルの番号。 */
    val ownSerial: Long get() = userManager.getSerialNumberForUser(Process.myUserHandle())

    /** 学習の書き出しと読み込みで、個人用・仕事用を読み替えるための今の番号。 */
    fun backupProfiles(): BackupProfiles = BackupProfiles(ownSerial, workProfileState().serials)

    /**
     * 今の仕事用プロファイルの状態。
     *
     * 自分のプロファイル以外の `LauncherApps.getProfiles` をすべて仕事用とみなす（索引に入れる範囲と同じ。data の `AppSource`）。
     * 仕事用プロファイルかを確かめる API（`UserManager.isManagedProfile(int)`）はシステムのアプリにしか使えない。
     * 一時停止は `isQuietModeEnabled` で見る。権限は要らない。
     */
    fun workProfileState(): WorkProfileState {
        val me = Process.myUserHandle()
        val others = launcherApps.profiles.filter { it != me }
        return WorkProfileState(
            serials = others.mapTo(HashSet()) { userManager.getSerialNumberForUser(it) },
            paused = others.any { userManager.isQuietModeEnabled(it) },
        )
    }
}
