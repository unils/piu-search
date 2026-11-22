// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.records

import android.content.Context
import android.util.Log
import jp.piu.search.core.state.ProfileCheck
import jp.piu.search.data.ExistingProfiles

/** その場で OS にプロファイルの存在を問い合わせる。[RecordsActor] が頼みを処理するたびに、列の中から呼ぶ。 */
public fun interface ProfileProbe {
    /** 今の存在。確かめられなければ [ProfileCheck.Unknown]。 */
    public fun check(): ProfileCheck
}

/**
 * UserManager に問い合わせる [ProfileProbe]。存在の読み方は 0.2.0 の [ExistingProfiles] と同じ（静止・ロック・ランチャーの可視性で決めない）。
 * 問い合わせの失敗は [ProfileCheck.Unknown] にする（最後に確認した状態を保ち、確認済みの削除は忘れない）。
 */
public class DeviceProfileProbe(
    context: Context,
) : ProfileProbe {
    private val existing = ExistingProfiles(context)

    override fun check(): ProfileCheck =
        try {
            // 自分を含まない・番号の取れない項目がある一覧は、ExistingProfiles が null にする（一部の一覧で存在するプロファイルの記録を消さないため）
            existing.serials()?.let { ProfileCheck.Existing(it) } ?: ProfileCheck.Unknown
        } catch (e: RuntimeException) {
            // binder の失敗（DeadObjectException を原因に持つ RuntimeException など）。型を残し、確かめられなかった扱いにする
            Log.w(TAG, "プロファイルを問い合わせられない: ${e.javaClass.name}")
            ProfileCheck.Unknown
        }

    private companion object {
        const val TAG = "PiuRecordsProfiles"
    }
}
