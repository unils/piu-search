// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.res.Resources
import android.provider.Settings

/**
 * [IndexSync] が端末に問い合わせること。判断の部分を端末なしで（Robolectric と偽物で）テストできるよう分ける。
 */
internal interface DeviceApps {
    /** 再起動の回数（`Settings.Global.BOOT_COUNT`）。取れなければ null。 */
    fun bootCount(): Int?

    /** 端末の言語（`LocaleList.toLanguageTags` の形）。 */
    fun locales(): String

    /** [sequence] より後に変わったパッケージと、今の変更の番号（`PackageManager.getChangedPackages`）。 */
    fun changedPackages(sequence: Int): PackageChanges

    /** 今回読めるプロファイルのシリアル番号。一覧にないことは、ユーザーが消えたことを意味しない。 */
    fun profileSerials(): Set<Long>

    /** すべての入口の識別子。[withUpdateTime] が false なら時刻は 0。 */
    fun stamps(withUpdateTime: Boolean): List<ActivityStamp>

    /** [packages] のパッケージの入口を、索引の材料にして読む（null ならすべて）。 */
    fun read(packages: Set<String>?): List<InstalledApp>
}

/**
 * `getChangedPackages` の結果。
 *
 * @property sequence 今の変更の番号。次はこれを渡す
 * @property packages 渡した番号より後に追加・更新・削除されたパッケージ。変わっていなければ空
 */
internal data class PackageChanges(
    val sequence: Int,
    val packages: Set<String>,
)

/**
 * 本物の端末の [DeviceApps]。このアプリ自身は索引に入れない（ui.md の「このアプリ自身は結果に出さない」）。
 *
 * @param bootCountOverride 再起動の回数を差し替える。計器テストで、再起動の代わりに全件の見直しを起こすため
 */
internal class AndroidDeviceApps(
    context: Context,
    private val reader: InstalledAppReader = InstalledAppReader(context),
    private val bootCountOverride: (() -> Int?)? = null,
) : DeviceApps {
    private val resolver = context.contentResolver
    private val packageManager = context.packageManager
    private val ownPackage = context.packageName

    override fun bootCount(): Int? = bootCountOverride?.invoke() ?: systemBootCount()

    private fun systemBootCount(): Int? =
        try {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT)
        } catch (_: Settings.SettingNotFoundException) {
            null
        }

    // アプリの名前は端末の言語で読むので、このアプリの言語（アプリごとの言語の設定）ではなく端末の設定を見る
    override fun locales(): String =
        Resources
            .getSystem()
            .configuration.locales
            .toLanguageTags()

    override fun changedPackages(sequence: Int): PackageChanges {
        // 変わったものがなければ null。そのときは番号も進んでいないので、渡した番号を使い続ける
        val changed = packageManager.getChangedPackages(sequence) ?: return PackageChanges(sequence, emptySet())
        return PackageChanges(changed.sequenceNumber, changed.packageNames.toSet())
    }

    override fun profileSerials(): Set<Long> = reader.source.readableProfiles().mapTo(LinkedHashSet()) { it.serial }

    override fun stamps(withUpdateTime: Boolean): List<ActivityStamp> =
        reader.source.stamps(withUpdateTime).filter { it.id.packageName != ownPackage }

    override fun read(packages: Set<String>?): List<InstalledApp> =
        reader.read(packages?.minus(ownPackage)).filter { it.entry.id.packageName != ownPackage }
}
