// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.os.UserHandle
import android.os.UserManager

/**
 * プロファイルのシリアル番号（索引と記録に保存する番号）から、OS のプロファイルの識別子を引く。
 *
 * 起動とアイコンの読み込みには `UserHandle` が要る。番号で保存するのは、消したプロファイルの番号が使い回されないため
 * （architecture.md の「プロファイル」）。仕事用プロファイルの状態は data の `ProfileWatcher` が流す。
 */
class ProfileUsers(
    context: Context,
) {
    private val userManager = context.getSystemService(UserManager::class.java)

    /** [serial] のプロファイル。消えたプロファイルなら null。 */
    fun userFor(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)
}
