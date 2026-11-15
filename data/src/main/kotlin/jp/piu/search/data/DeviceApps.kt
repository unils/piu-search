// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId

/**
 * [IndexSync] が端末に問い合わせること。判断の部分を端末なしで（Robolectric と偽物で）テストできるよう分ける。
 */
internal interface DeviceApps {
    /** Callback の通知元を保存用のシリアル番号へ変える。分からなければ次の同期で再試行する。 */
    fun profileSerial(user: UserHandle): Long? = null

    /** OS のビルド。変われば資源も変わり得るため全件を作り直す。 */
    fun buildFingerprint(): String = Build.FINGERPRINT

    /** 再起動の回数（`Settings.Global.BOOT_COUNT`）。取れなければ null。 */
    fun bootCount(): Int?

    /** 端末の言語（`LocaleList.toLanguageTags` の形）。 */
    fun locales(): String

    /** [sequence] より後に変わったパッケージと、今の変更の番号（`PackageManager.getChangedPackages`）。 */
    fun changedPackages(sequence: Int): PackageChanges

    /**
     * 一覧の観測（入口の集合・アーカイブ済みか・鍵）をプロファイルごとに返す。[withUpdateTime] が false なら時刻は null。
     * 一覧を読めなかったプロファイルを正常な空にしない。
     */
    fun stampProfiles(withUpdateTime: Boolean): Map<Long, ProfileRead<ActivityStamp>>

    /**
     * 直前の [stampProfiles] の一覧で、[targets] の組の鍵（時刻と指紋）を一度に観測する（[InstalledAppReader.listedKeys]）。
     * 時刻を引かなかった一覧で、読む組の一覧の観測を確定する。ジャンルと詳細を読む前に呼ぶ。
     */
    fun listedKeys(targets: Map<PackageKey, List<ActivityStamp>>): Map<PackageKey, ApkKeys>

    /**
     * 直前の [stampProfiles] の一覧で、[targets] の組の詳細を段ごとに読む。
     *
     * @param listedKeys 組ごとの一覧の観測の鍵（[InstalledAppReader.readPackages]）
     */
    fun readPackages(
        targets: Map<PackageKey, List<ActivityStamp>>,
        listedKeys: Map<PackageKey, ApkKeys>,
    ): Map<Long, ProfileRead<PackageRead>>

    /** APK の変化によらないジャンルの材料を毎回問い合わせる。 */
    fun genres(stamps: List<ActivityStamp>): Map<AppId, List<GenreId>> = emptyMap()
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
    private val userManager = context.getSystemService(UserManager::class.java)
    private val resolver = context.contentResolver
    private val packageManager = context.packageManager
    private val genreResolver = GenreResolver(context.packageManager)
    private val ownPackage = context.packageName

    override fun profileSerial(user: UserHandle): Long? = userManager.getSerialNumberForUser(user).takeIf { it >= 0 }

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

    // 同期は 1 つずつ（IndexSync の syncLock）なので、直前の一覧を持っておき、詳細を読む組の表示名と鍵の観測に使う
    @Volatile private var listing: Listing? = null

    override fun stampProfiles(withUpdateTime: Boolean): Map<Long, ProfileRead<ActivityStamp>> {
        val next = reader.source.listing(withUpdateTime)
        listing = next
        return next.profiles.mapValues { (_, result) -> result.copy(items = result.items.filter { it.id.packageName != ownPackage }) }
    }

    override fun listedKeys(targets: Map<PackageKey, List<ActivityStamp>>): Map<PackageKey, ApkKeys> =
        reader.listedKeys(checkNotNull(listing) { "一覧の前に鍵を観測した" }, targets.filterKeys { it.packageName != ownPackage })

    override fun readPackages(
        targets: Map<PackageKey, List<ActivityStamp>>,
        listedKeys: Map<PackageKey, ApkKeys>,
    ): Map<Long, ProfileRead<PackageRead>> =
        reader.readPackages(
            checkNotNull(listing) { "一覧の前に詳細を読んだ" },
            targets.filterKeys { it.packageName != ownPackage },
            listedKeys,
        )

    override fun genres(stamps: List<ActivityStamp>): Map<AppId, List<GenreId>> = genreResolver.resolveStamps(stamps)
}
