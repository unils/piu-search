// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import jp.piu.search.core.search.AppId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * 索引に入れるプロファイル 1 つ。
 */
public data class Profile(
    /** OS のプロファイルの識別子。起動（`LauncherApps.startMainActivity`）に使う。 */
    val user: UserHandle,
    /** `UserManager.getSerialNumberForUser` の番号。データベースにはこちらを保存する（architecture.md の「プロファイル」）。 */
    val serial: Long,
    /** このアプリが動いているプロファイル（ふつうは個人用）か。 */
    val isCurrent: Boolean,
)

/**
 * 一覧の観測の、入口 1 つ（[AppSource.stampProfiles]）。組の入口の集合・アーカイブ済みか・鍵を、保存した値と比べるのに使う。
 *
 * @property id 入口の識別子
 * @property packageUpdatedAt パッケージの最後の更新の時刻（ミリ秒）。引かなかった・読めなかったときは null。0 は有効な時刻
 * @property apkFingerprint base と split のパス・時刻・大きさ。作れなければ null（失敗ではない）
 * @property isCurrentProfile 自分のプロファイルなら変更番号を使う
 * @property category ジャンルを毎回判定するためのカテゴリ
 * @property isArchived アーカイブ済みか（`ApplicationInfo.isArchived`。API 34 以下は常に偽）
 */
public data class ActivityStamp(
    val id: AppId,
    val packageUpdatedAt: Long?,
    val apkFingerprint: String? = null,
    val isCurrentProfile: Boolean = true,
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
    val isArchived: Boolean = false,
)

/**
 * 組の変化の検出の鍵（architecture.md の「鍵の比べ方」）。作れなかった値は null で、失敗ではない。
 *
 * @property updatedAt パッケージの最後の更新の時刻（ミリ秒）。0 は有効な時刻
 * @property fingerprint base と split の APK の指紋
 */
public data class ApkKeys(
    val updatedAt: Long?,
    val fingerprint: String?,
)

/**
 * 一覧の観測と、その入口の `LauncherActivityInfo`。詳細を読む組の表示名と鍵の観測は、一覧の段の情報から取る。
 *
 * @property profiles プロファイルごとの一覧
 */
internal class Listing(
    val profiles: Map<Long, ProfileRead<ActivityStamp>>,
    private val infos: Map<AppId, LauncherActivityInfo>,
    private val users: Map<Long, Profile>,
) {
    fun info(id: AppId): LauncherActivityInfo? = infos[id]

    fun profile(serial: Long): Profile? = users[serial]
}

/**
 * ランチャーに並ぶ Activity 1 つ（起動の入口）。[AppSource] が作る。
 */
public class LaunchableActivity(
    /** 入口の識別子。 */
    public val id: AppId,
    /** 端末の言語での表示名（ホーム画面に出る名前）。 */
    public val label: String,
    /** 入口のあるプロファイル。 */
    public val user: UserHandle,
    /** 入口を持つアプリの情報。資源の読み込みと `category` に使う。 */
    public val applicationInfo: ApplicationInfo,
    /**
     * Activity 自身の表示名の資源 id。0 はアプリの表示名を受け継ぐこと。
     * null は分からないこと（資源でない名前を持つ、または API 30 以前で仕事用プロファイルにしかないアプリ）。
     */
    public val activityLabelRes: Int?,
) {
    /** 起動に使うコンポーネント名。 */
    public val componentName: ComponentName get() = ComponentName(id.packageName, id.activityName)

    override fun toString(): String = "LaunchableActivity($id, $label)"
}

/**
 * 端末の起動できるアプリの一覧（architecture.md の「プロファイル」）。
 *
 * `LauncherApps` で、主プロファイルと同じプロファイルグループの仕事用プロファイルの入口を列挙する。
 * 見えるのは `<queries>` の `MAIN`＋`LAUNCHER` に当たるアプリで、`QUERY_ALL_PACKAGES` は要らない。
 * プライベートスペースは OS が見せないので出ない（android-platform.md）。
 */
public class AppSource(
    context: Context,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val packageManager = context.packageManager

    /** 索引に入れるプロファイル。自分のプロファイルが先頭。 */
    public fun profiles(): List<Profile> {
        val me = Process.myUserHandle()
        return launcherApps.profiles
            .map { Profile(it, userManager.getSerialNumberForUser(it), it == me) }
            .sortedByDescending { it.isCurrent }
    }

    /** 一時停止・ロック中の一覧は正常な空と区別するため、読み取りの対象から外す。 */
    public fun readableProfiles(): List<Profile> = profiles().filter(::isReadable)

    /**
     * すべてのプロファイルの起動の入口。1 つのアプリが入口を 2 つ持てば 2 件になる。
     * [packages] を渡せば、そのパッケージの入口だけ（null ならすべて）。
     */
    public fun activities(packages: Set<String>? = null): List<LaunchableActivity> =
        launcherEntries(packages).map { (info, profile) -> toLaunchable(info, profile) }

    /**
     * すべてのプロファイルの一覧の観測。表示名は読まないので [activities] より軽い。[withUpdateTime] が false なら時刻は引かず null にする。
     * 一覧を読めなかったプロファイルがあれば [IllegalStateException]。
     */
    public fun stamps(withUpdateTime: Boolean): List<ActivityStamp> {
        val profiles = stampProfiles(withUpdateTime)
        check(profiles.values.none { it.status == ProfileReadStatus.FAILED }) { "入口の一覧を読めないプロファイルがある" }
        return profiles.values.flatMap { it.items }
    }

    /** 一覧の失敗を空のプロファイルにしない。仕事用の APK の指紋は変更番号によらず毎回読む。 */
    public fun stampProfiles(withUpdateTime: Boolean): Map<Long, ProfileRead<ActivityStamp>> = listing(withUpdateTime).profiles

    internal fun listing(withUpdateTime: Boolean): Listing {
        val infos = HashMap<AppId, LauncherActivityInfo>()
        val users = HashMap<Long, Profile>()
        val profiles =
            profiles().associate { profile ->
                users[profile.serial] = profile
                profile.serial to enumerate(profile) { list -> list.map { stamp(it, profile, withUpdateTime, infos) } }
            }
        return Listing(profiles, infos, users)
    }

    private fun stamp(
        info: LauncherActivityInfo,
        profile: Profile,
        withUpdateTime: Boolean,
        infos: MutableMap<AppId, LauncherActivityInfo>,
    ): ActivityStamp {
        val id = AppId(info.componentName.packageName, info.componentName.className, profile.serial)
        infos[id] = info
        val applicationInfo = info.applicationInfo
        return ActivityStamp(
            id,
            if (withUpdateTime) updatedAt(id.packageName, applicationInfo, profile.isCurrent) else null,
            apkFingerprint(applicationInfo),
            profile.isCurrent,
            applicationInfo.category,
            isArchived(applicationInfo),
        )
    }

    /** 一覧の段の情報から、組の鍵を観測する。読み取りの前後の比較と、一覧で時刻を引かなかった組に使う。 */
    internal fun observeKeys(
        info: LauncherActivityInfo,
        isCurrentProfile: Boolean,
    ): ApkKeys =
        ApkKeys(
            updatedAt(info.componentName.packageName, info.applicationInfo, isCurrentProfile),
            apkFingerprint(info.applicationInfo),
        )

    /** パッケージの入口の詳細。一覧を取った後に消えたら空。例外は呼び出し側（組の `ENTRY` の段）で扱う。 */
    internal fun details(
        packageName: String,
        profile: Profile,
    ): List<LaunchableActivity> =
        launcherApps
            .getActivityList(packageName, profile.user)
            .filter(::isAppEntry)
            .map { toLaunchable(it, profile) }

    // 失敗の単位はプロファイル。一覧そのものの例外だけをここで捕まえる。組の鍵の例外は鍵を「なし」にするので、ここまで来ない
    private fun enumerate(
        profile: Profile,
        convert: (List<LauncherActivityInfo>) -> List<ActivityStamp>,
    ): ProfileRead<ActivityStamp> =
        try {
            if (!isReadable(profile)) {
                ProfileRead(ProfileReadStatus.UNAVAILABLE)
            } else {
                val infos = launcherApps.getActivityList(null, profile.user).filter(::isAppEntry)
                if (!isReadable(profile)) {
                    ProfileRead(ProfileReadStatus.UNAVAILABLE)
                } else {
                    ProfileRead(ProfileReadStatus.COMPLETE, convert(infos))
                }
            }
        } catch (e: RuntimeException) {
            ProfileRead(ProfileReadStatus.FAILED, failure = MaterialCauses.causeOf(e))
        }

    /** 材料の読み取り中の一時停止も、正常な空として保存しないために再確認する。 */
    internal fun profileReadStatus(serial: Long): ProfileReadStatus =
        try {
            val profile = profiles().firstOrNull { it.serial == serial }
            if (profile != null && isReadable(profile)) ProfileReadStatus.COMPLETE else ProfileReadStatus.UNAVAILABLE
        } catch (_: RuntimeException) {
            ProfileReadStatus.FAILED
        }

    private fun isReadable(profile: Profile): Boolean =
        !userManager.isQuietModeEnabled(profile.user) && userManager.isUserUnlocked(profile.user)

    /**
     * [activity] のパッケージの最後の更新の時刻（ミリ秒）。索引の `packageUpdatedAt` に入れ、全件の見直しで比べる。
     * 比べるのは同じ式で求めた値どうしなので、変わったかが分かればよい。読めなければ null。
     */
    public fun packageUpdatedAt(activity: LaunchableActivity): Long? =
        updatedAt(activity.id.packageName, activity.applicationInfo, activity.user == Process.myUserHandle())

    private fun launcherEntries(packages: Set<String>?): List<Pair<LauncherActivityInfo, Profile>> =
        readableProfiles().flatMap { profile ->
            val infos =
                if (packages == null) {
                    launcherApps.getActivityList(null, profile.user)
                } else {
                    packages.sorted().flatMap { launcherApps.getActivityList(it, profile.user) }
                }
            infos.filter(::isAppEntry).map { it to profile }
        }

    // AOSP LauncherAppsService.getHiddenAppActivityInfo（android14-release）は、対象パッケージにこのクラスの入口を合成する。
    // アプリを開く入口ではなく設定のアプリ情報へ転送するため、名前やアイコンを読まずに除く（根拠は architecture.md）
    private fun isAppEntry(info: LauncherActivityInfo): Boolean = info.componentName.className != "android.app.AppDetailsActivity"

    // 自分のプロファイルは PackageInfo.lastUpdateTime（architecture.md の「データの保存」）。
    // 仕事用プロファイルのパッケージは PackageManager から引けないので、APK のファイルの更新の時刻を使う。
    // 鍵が作れないことは失敗にしない（architecture.md の「鍵の比べ方」）。ここの例外をプロファイル全体の失敗に広げると、
    // 1 つの組の時刻のためにプロファイルのすべての組が古いまま残るので、組の時刻を「なし」にして続ける
    private fun updatedAt(
        packageName: String,
        applicationInfo: ApplicationInfo,
        isCurrentProfile: Boolean,
    ): Long? {
        if (isCurrentProfile) {
            try {
                return packageInfo(packageName).lastUpdateTime
            } catch (_: PackageManager.NameNotFoundException) {
                // 一覧を取った後に消えたか、アーカイブ済み（既定の問い合わせには出ない）。ファイルの時刻に落ちる
            } catch (_: RuntimeException) {
                return null
            }
        }
        return applicationInfo.sourceDir?.let { apkAttributes(it)?.lastModifiedTime()?.toMillis() }
    }

    private fun packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }

    private fun toLaunchable(
        info: LauncherActivityInfo,
        profile: Profile,
    ): LaunchableActivity {
        val component = info.componentName
        return LaunchableActivity(
            id = AppId(component.packageName, component.className, profile.serial),
            label = info.label.toString(),
            user = profile.user,
            applicationInfo = info.applicationInfo,
            activityLabelRes = activityLabelRes(info),
        )
    }

    private fun activityLabelRes(info: LauncherActivityInfo): Int? {
        val activityInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.activityInfo
            } else {
                // API 30 以前は LauncherActivityInfo から ActivityInfo が取れない。PackageManager は自分のプロファイルしか
                // 引けないので、仕事用プロファイルの入口には、主プロファイルにある同じパッケージの情報を使う（同じ APK の見込み。
                // 別の言語の名前を読むときも、API 30 以前は主プロファイルのパッケージから読む）
                try {
                    @Suppress("DEPRECATION")
                    packageManager.getActivityInfo(info.componentName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        return when {
            activityInfo == null -> null
            activityInfo.labelRes != 0 -> activityInfo.labelRes
            activityInfo.nonLocalizedLabel != null -> null
            else -> 0
        }
    }
}

/** アーカイブは API 35 から。それより前は常に偽。 */
internal fun isArchived(info: ApplicationInfo): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && info.isArchived

/**
 * base と split の APK のパス・時刻・大きさを並べた指紋。ファイルの不在や読み取りの失敗では作らない（null。失敗ではない）。
 * 順序の揺れは変化に数えない。大きさは、時刻が 0 に固定される APEX の中の APK でも、版の違いを拾う見込みが高いため。
 */
internal fun apkFingerprint(info: ApplicationInfo): String? {
    val base = info.sourceDir?.takeIf { it.isNotBlank() } ?: return null
    val paths = listOf(base) + info.splitSourceDirs.orEmpty().sorted()
    val attributes = ArrayList<String>()
    for (path in paths) {
        val file = apkAttributes(path) ?: return null
        attributes += "${path.length}:$path:${file.lastModifiedTime().toMillis()}:${file.size()}"
    }
    return attributes.joinToString("|")
}

// 時刻は例外で失敗を知らせる NIO で読む。File.lastModified は失敗も 0 で返すが、APEX の中の APK
// （例: Android 15 以降のターミナル）は本当に時刻が 0 で、失敗と区別できないと毎回の同期で読み直し続けるため。
// 鍵が作れないことは失敗にしないので、例外の型によらず「なし」にする
private fun apkAttributes(path: String): BasicFileAttributes? =
    try {
        Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java).takeIf { it.isRegularFile }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
