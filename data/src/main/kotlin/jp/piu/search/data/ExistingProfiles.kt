// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.os.Process
import android.os.UserManager

/**
 * 同じグループに存在するユーザーのシリアル番号。静止・ロック・LauncherApps の可視性で存在を決めない。
 * 自分のユーザーを含まない一覧や、番号を取得できない一覧は、問い合わせ失敗として null にする。
 */
public class ExistingProfiles(
    context: Context,
) {
    private val userManager = context.getSystemService(UserManager::class.java)

    /** UserManager で存在を確かめた番号。問い合わせが失敗した場合は呼び出し元で保留する。 */
    public fun serials(): Set<Long>? {
        val users = userManager.userProfiles
        if (Process.myUserHandle() !in users) return null
        val serials = users.mapTo(HashSet()) { userManager.getSerialNumberForUser(it) }
        return serials.takeUnless { it.any { serial -> serial < 0 } }
    }
}
