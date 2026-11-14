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
import java.io.File

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
 * 入口の識別子と、そのパッケージの最後の更新の時刻（[AppSource.stamps]）。
 *
 * @property id 入口の識別子
 * @property packageUpdatedAt パッケージの最後の更新の時刻（ミリ秒）。引かなかったときは 0
 * @property apkFingerprint base と split のパスと時刻。取得不能なら null
 * @property isCurrentProfile 自分のプロファイルなら変更番号を使う
 * @property category ジャンルを毎回判定するためのカテゴリ
 */
public data class ActivityStamp(
    val id: AppId,
    val packageUpdatedAt: Long,
    val apkFingerprint: String? = null,
    val isCurrentProfile: Boolean = true,
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
)

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
    public fun readableProfiles(): List<Profile> =
        profiles().filter { !userManager.isQuietModeEnabled(it.user) && userManager.isUserUnlocked(it.user) }

    /**
     * すべてのプロファイルの起動の入口。1 つのアプリが入口を 2 つ持てば 2 件になる。
     * [packages] を渡せば、そのパッケージの入口だけ（null ならすべて）。
     */
    public fun activities(packages: Set<String>? = null): List<LaunchableActivity> =
        launcherEntries(packages).map { (info, profile) -> toLaunchable(info, profile) }

    /**
     * すべてのプロファイルの入口の識別子と、パッケージの最後の更新の時刻（[packageUpdatedAt]）。
     * 表示名は読まないので [activities] より軽い。[withUpdateTime] が false なら時刻は引かず 0 にする。
     */
    public fun stamps(withUpdateTime: Boolean): List<ActivityStamp> {
        val profiles = stampProfiles(withUpdateTime)
        if (profiles.values.any { it.status == ProfileReadStatus.FAILED }) throw MaterialReadException()
        return profiles.values.flatMap { it.items }
    }

    /** 一覧の失敗を空のプロファイルにしない。仕事用の APK 属性は変更番号によらず毎回読む。 */
    public fun stampProfiles(withUpdateTime: Boolean): Map<Long, ProfileRead<ActivityStamp>> =
        enumerate(null) { infos, profile ->
            val fingerprints = HashMap<String, String?>()
            val times = HashMap<String, Long>()
            infos.map { info ->
                val id = AppId(info.componentName.packageName, info.componentName.className, profile.serial)
                ActivityStamp(
                    id,
                    if (withUpdateTime) {
                        times.getOrPut(
                            id.packageName,
                        ) { updatedAt(id.packageName, info.applicationInfo, profile.isCurrent) }
                    } else {
                        0
                    },
                    fingerprints.getOrPut(id.packageName) { apkFingerprint(info.applicationInfo) },
                    profile.isCurrent,
                    info.applicationInfo.category,
                )
            }
        }

    /** 詳細の変換失敗をパッケージごとに残し、ほかのパッケージを読み続ける。 */
    internal fun launcherProfiles(packages: Set<String>?): Map<Long, ProfileRead<LauncherActivityInfo>> =
        enumerate(packages) { infos, _ -> infos }

    internal fun launchable(
        info: LauncherActivityInfo,
        serial: Long,
    ): LaunchableActivity = toLaunchable(info, Profile(info.user, serial, info.user == Process.myUserHandle()))

    private fun <T> enumerate(
        packages: Set<String>?,
        convert: (List<LauncherActivityInfo>, Profile) -> List<T>,
    ): Map<Long, ProfileRead<T>> =
        profiles().associate { profile ->
            val result =
                try {
                    if (!isReadable(profile)) {
                        ProfileRead<T>(ProfileReadStatus.UNAVAILABLE)
                    } else {
                        val infos =
                            launcherApps
                                .getActivityList(null, profile.user)
                                .filter { isAppEntry(it) && (packages == null || it.componentName.packageName in packages) }
                        if (!isReadable(profile)) {
                            ProfileRead(ProfileReadStatus.UNAVAILABLE)
                        } else {
                            ProfileRead(ProfileReadStatus.COMPLETE, convert(infos, profile))
                        }
                    }
                } catch (_: RuntimeException) {
                    ProfileRead<T>(ProfileReadStatus.FAILED)
                }
            profile.serial to result
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
     * 比べるのは同じ式で求めた値どうしなので、変わったかが分かればよい。
     */
    public fun packageUpdatedAt(activity: LaunchableActivity): Long =
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
    // 通常の更新時刻の比較用。仕事用の差分では別に base と split の指紋を毎回比べる
    private fun updatedAt(
        packageName: String,
        applicationInfo: ApplicationInfo,
        isCurrentProfile: Boolean,
    ): Long {
        if (isCurrentProfile) {
            try {
                return packageInfo(packageName).lastUpdateTime
            } catch (_: PackageManager.NameNotFoundException) {
                // 一覧を取った後に消えた。次の同期で消える
            }
        }
        return applicationInfo.sourceDir?.let { File(it).lastModified() } ?: 0L
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

/** ファイルの不在や時刻 0 は成功した指紋として保存しない。順序の揺れは変化に数えない。 */
internal fun apkFingerprint(info: ApplicationInfo): String? {
    val base = info.sourceDir?.takeIf { it.isNotBlank() } ?: return null
    val paths = listOf(base) + info.splitSourceDirs.orEmpty().sorted()
    return try {
        val attributes = ArrayList<String>()
        for (path in paths) {
            val file = File(path)
            val time = file.lastModified()
            if (!file.isFile || time == 0L) return null
            attributes += "${path.length}:$path:$time"
        }
        attributes.joinToString("|")
    } catch (_: SecurityException) {
        null
    }
}
