// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.os.Process
import android.os.UserManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 保存した番号と UserManager の存在するユーザーを照合する。操作は共通の [DatabaseWriter] の仕事の中で呼ぶ。
 * [existingSerials] が null を返すか例外を投げた場合は、存在の判定ができないので何も消さない。
 */
public class ProfileReconciler(
    private val store: () -> ProfileStorage,
    private val existingSerials: () -> Set<Long>?,
) {
    private val listeners = CopyOnWriteArrayList<(Set<Long>) -> Unit>()
    private val removed = HashSet<Long>()
    private var lastExisting: Set<Long>? = null

    /** 削除後の写しを差し替える [listener]。保存が確定した後、同じ書き手の仕事の中で呼ぶ。 */
    public fun addRemovalListener(listener: (Set<Long>) -> Unit) {
        listeners += listener
    }

    /** 存在しないことが確定した番号への遅い書き込みを拒む。書き手の中から呼ぶ。 */
    public fun isPresent(serial: Long): Boolean {
        if (serial in removed) return false
        if (lastExisting?.contains(serial) == true) return true
        // 照合後に追加された番号もあり得る。古い一覧だけで新しい記録を捨てず、問い合わせ失敗は保留する
        val existing = readExisting() ?: return true
        lastExisting = lastExisting.orEmpty() + existing
        return serial in existing
    }

    /** 書き手の外の計算には変更されない写しを渡し、削除と計算の並行実行で集合を共有しない。 */
    internal fun snapshot(): ProfileSnapshot = ProfileSnapshot(lastExisting?.toSet(), removed.toSet())

    /** 3 種の保存データを照合して、削除が確定した番号を返す。保存の失敗は呼び出し元に返して再試行させる。 */
    public fun reconcile(): Set<Long> {
        val existing = readExisting() ?: return emptySet()
        val missing = store().serials() - existing
        if (missing.isEmpty()) {
            // 行のない番号への遅い初回記録も拒むため、削除対象が空でも存在の照合を残す
            lastExisting = existing
            return emptySet()
        }
        store().delete(missing)
        lastExisting = existing
        removed += missing
        listeners.forEach { it(missing) }
        return missing
    }

    private fun readExisting(): Set<Long>? =
        try {
            existingSerials()
        } catch (e: RuntimeException) {
            Log.w(TAG, "ユーザーの照合に失敗した: ${e.javaClass.simpleName}")
            null
        }

    private companion object {
        const val TAG = "PiuProfiles"
    }
}

/** 計算の前提にした存在の一覧。保存前に最新の写しと比べ、変わっていれば計算をやり直す。 */
internal data class ProfileSnapshot(
    val existing: Set<Long>?,
    val removed: Set<Long>,
) {
    fun isPresent(serial: Long): Boolean = serial !in removed && existing?.contains(serial) != false
}

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
