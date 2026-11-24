// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.UserHandle
import jp.piu.search.core.search.AppId
import jp.piu.search.core.state.DeviceObservation
import jp.piu.search.core.state.Failure
import jp.piu.search.core.state.GenreRequest
import jp.piu.search.core.state.GenreResult
import jp.piu.search.core.state.KeyObservation
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.MaterialJob
import jp.piu.search.core.state.MaterialRead
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.PackageObservation
import jp.piu.search.core.state.ProfileListing
import jp.piu.search.core.state.ProfileReadRequest
import jp.piu.search.core.state.ProfileReads
import jp.piu.search.core.state.Stage
import jp.piu.search.data.ActivityStamp
import jp.piu.search.data.AndroidDeviceApps
import jp.piu.search.data.DeviceApps
import jp.piu.search.data.ExistingProfiles
import jp.piu.search.data.MaterialCauses
import jp.piu.search.data.MaterialStage
import jp.piu.search.data.PackageRead
import jp.piu.search.data.ProfileRead
import jp.piu.search.data.ProfileReadStatus
import jp.piu.search.data.ReadFailure
import jp.piu.search.core.state.IssueCategory as StateCategory
import jp.piu.search.data.PackageKey as StoredKey

/**
 * 同期 1 回の、読み手が観測する環境。
 *
 * @property locale 端末の言語（`LocaleList.toLanguageTags` の形）
 * @property osBuild OS のビルド
 * @property bootCount 再起動の回数（`Settings.Global.BOOT_COUNT`）。取れなければ null
 */
public data class DeviceEnvironment(
    val locale: String,
    val osBuild: String,
    val bootCount: Int?,
)

/**
 * 索引の書き手（[IndexActor]）が端末に問い合わせること。結果は同期の規則（`IndexRules`）の入力の形で返し、
 * 一覧・材料・ジャンルの失敗は例外でなく結果の値で知らせる（失敗の単位はプロファイル・組・同期 1 回のジャンル）。
 *
 * 書き手が 1 本の列から順に呼ぶ。[readMaterials] と [genres] は、直前の [observe] の一覧を使う。
 */
public interface DeviceReader {
    /** 読み取りの部品の版。何を・どう読むかを変えたら上げる（材料の鍵に入り、上げると全件を読み直す）。 */
    public val readerVersion: Int

    /** 今の言語・OS のビルド・再起動の回数。 */
    public fun environment(): DeviceEnvironment

    /**
     * 一覧の観測。[known] は写しにあるプロファイルの番号で、そのうち存在しないと確かめられたものを
     * [DeviceObservation.removedProfiles] に入れる（問い合わせが失敗したら空）。
     */
    public fun observe(known: Set<Long>): DeviceObservation

    /** 知らせの通知元 [user] のシリアル番号。分からなければ null（次の同期で解決し直す）。 */
    public fun serialOf(user: UserHandle): Long?

    /** [request] の組の材料を読む。頼みの組の分をちょうど返す（読めないプロファイルなら組の結果なし）。 */
    public fun readMaterials(request: ProfileReadRequest): ProfileReads

    /** [request] の入口のジャンル。 */
    public fun genres(request: GenreRequest): GenreResult
}

/**
 * 0.2.0 の読み手（[DeviceApps]: `AppSource`・`InstalledAppReader`・`LocalizedLabelReader`・`SignerReader`・`GenreResolver`）を包む [DeviceReader]。
 * 何を・どう読むかは変えず、結果の形だけを同期の規則の入力に変える。
 *
 * ないことが決まっているもの（その言語に名前の資源がない `Resources.NotFoundException`・署名がない `NO_SIGNERS`・
 * API 30 以下で仕事用のプロファイルにしかないアプリの別の言語の名前）は、失敗でなく空の値で読めたとする。
 * 名前が資源でない・アーカイブ済みは、もとから読み手が失敗にしない。
 * それ以外の段の失敗は、段の順（ENTRY・LABEL・SIGNER）で最初のものを組の失敗にする。
 *
 * @param existingProfiles 存在するプロファイルの番号。問い合わせが失敗したら null
 * @param sdkInt 端末の API レベル。テストで差し替える
 * @param visibleToOwnProfile パッケージ名が自分のプロファイルの `PackageManager` から見えるか。問い合わせが失敗したら null
 */
internal class DeviceAppsReader(
    private val apps: DeviceApps,
    private val existingProfiles: () -> Set<Long>?,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val visibleToOwnProfile: (String) -> Boolean? = { null },
) : DeviceReader {
    constructor(context: Context) : this(context, null)

    /** 再起動の回数を [bootCountOverride] に差し替えて作る。計器テストが、再起動の代わりに回数を変える。 */
    constructor(context: Context, bootCountOverride: (() -> Int?)?) : this(
        AndroidDeviceApps(context.applicationContext, bootCountOverride = bootCountOverride),
        ExistingProfiles(context)::serials,
        visibleToOwnProfile = { packageName -> visibleTo(context.packageManager, packageName) },
    )

    override val readerVersion: Int = READER_VERSION

    // 書き手の列の中でだけ読み書きする。直前の一覧の入口で、材料とジャンルを読む
    private var stamps: Map<PackageKey, List<ActivityStamp>> = emptyMap()
    private var stampById: Map<AppId, ActivityStamp> = emptyMap()

    override fun environment(): DeviceEnvironment = DeviceEnvironment(apps.locales(), apps.buildFingerprint(), apps.bootCount())

    override fun serialOf(user: UserHandle): Long? = apps.profileSerial(user)

    override fun observe(known: Set<Long>): DeviceObservation {
        // 個人用の組も更新の時刻を毎回引く（鍵に入れ、0.2.0 の全件の見直しと同じ検出の範囲を保つ）
        val listing = apps.stampProfiles(withUpdateTime = true)
        val complete = listing.values.filter { it.status == ProfileReadStatus.COMPLETE }.flatMap { it.items }
        stamps = complete.groupBy { PackageKey(it.id.profileSerial, it.id.packageName) }
        stampById = complete.associateBy { it.id }
        val existing =
            try {
                existingProfiles()
            } catch (_: RuntimeException) {
                // 存在を確かめられない回は、どのプロファイルも消さない
                null
            }
        return DeviceObservation(
            listing.mapValues { (serial, read) -> listingOf(serial, read) },
            if (existing == null) emptySet() else known - existing,
        )
    }

    private fun listingOf(
        serial: Long,
        read: ProfileRead<ActivityStamp>,
    ): ProfileListing =
        when (read.status) {
            ProfileReadStatus.COMPLETE -> {
                ProfileListing(
                    ListingStatus.COMPLETE,
                    stamps
                        .filterKeys { it.profileSerial == serial }
                        .entries
                        .associate { (key, entries) ->
                            // 時刻と指紋はパッケージの値なので、どの入口から取っても同じ
                            val first = entries.first()
                            key.packageName to
                                PackageObservation(
                                    entries.mapTo(HashSet()) { it.id.activityName },
                                    entries.any { it.isArchived },
                                    first.apkFingerprint,
                                    first.packageUpdatedAt,
                                )
                        },
                )
            }

            ProfileReadStatus.UNAVAILABLE -> {
                ProfileListing(ListingStatus.UNAVAILABLE)
            }

            ProfileReadStatus.FAILED -> {
                ProfileListing(ListingStatus.FAILED)
            }
        }

    override fun readMaterials(request: ProfileReadRequest): ProfileReads =
        try {
            val targets = request.jobs.associate { StoredKey(it.key.profileSerial, it.key.packageName) to stamps.getValue(it.key) }
            val profile = apps.readPackages(targets)[request.serial] ?: ProfileRead(ProfileReadStatus.UNAVAILABLE)
            if (profile.status == ProfileReadStatus.COMPLETE) {
                val byKey = profile.items.associateBy { it.key }
                ProfileReads(
                    request,
                    ListingStatus.COMPLETE,
                    request.jobs.associateWith { job ->
                        materialOf(job, byKey.getValue(StoredKey(job.key.profileSerial, job.key.packageName)))
                    },
                )
            } else {
                ProfileReads(request, statusOf(profile.status), failure = profile.failure)
            }
        } catch (e: RuntimeException) {
            // 読み手の中の例外（組の段の境界の外）。プロファイルの単位の失敗にして、組を前の写しのまま保つ
            ProfileReads(request, ListingStatus.FAILED, failure = MaterialCauses.causeOf(e))
        }

    private fun materialOf(
        job: MaterialJob,
        read: PackageRead,
    ): MaterialRead {
        val before = KeyObservation(read.before.fingerprint, read.before.updatedAt)
        val after = KeyObservation(read.after.fingerprint, read.after.updatedAt)
        val labels =
            read.labels.entries
                .mapNotNull { (id, label) -> label?.let { id.activityName to it } }
                .toMap()
        read.entryFailure?.let { return MaterialRead.Failed(Stage.ENTRY, failureOf(MaterialStage.ENTRY, it), before, after, labels) }
        if (!job.auxiliary) return MaterialRead.Read(labels, before, after)
        read.labelFailure?.takeUnless { it.cause == NOT_FOUND || absentFromOwnProfile(job, it) }?.let {
            return MaterialRead.Failed(Stage.LABEL, failureOf(MaterialStage.LABEL, it), before, after, labels)
        }
        read.signerFailure?.takeUnless { it.cause == MaterialCauses.NO_SIGNERS }?.let {
            return MaterialRead.Failed(Stage.SIGNER, failureOf(MaterialStage.SIGNER, it), before, after, labels)
        }
        return MaterialRead.Read(
            labels,
            before,
            after,
            localized = read.localized.mapKeys { it.key.activityName },
            signerDns = read.signers,
        )
    }

    // API 30 以下は、別の言語の名前を自分のプロファイルのパッケージの資源から読む（LocalizedLabelReader）。仕事用にしかないアプリでは
    // createPackageContext が NameNotFoundException になり、APK が替わるまで同じ。読めた表示名と署名まで捨てて代わりのキーにしないため、
    // 別の言語の名前だけがないとする。自分のプロファイルから見えないと確かめられたときだけで、見える・確かめられないなら失敗のまま
    // （一覧の後に消えた・別の理由を隠さないため）。API 31 以上の NameNotFoundException は一覧の後に消えたことなので、一時的な失敗のまま
    private fun absentFromOwnProfile(
        job: MaterialJob,
        failure: ReadFailure,
    ): Boolean =
        sdkInt < Build.VERSION_CODES.S &&
            failure.cause == NAME_NOT_FOUND &&
            stamps[job.key]?.firstOrNull()?.isCurrentProfile == false &&
            visibleToOwnProfile(job.key.packageName) == false

    override fun genres(request: GenreRequest): GenreResult =
        try {
            val targets = request.targets.toSet()
            val found = apps.genres(request.targets.mapNotNull { stampById[it] })
            GenreResult.Found(request, found.filterKeys { it in targets })
        } catch (e: RuntimeException) {
            // 失敗の単位は同期 1 回のジャンルの問い合わせ。規則が既存のジャンルを保つ
            GenreResult.Failed(request, MaterialCauses.causeOf(e))
        }

    companion object {
        /** 読み取りの部品の版。 */
        const val READER_VERSION = 1

        // その言語に名前の資源がない（values-ja だけにある名前など）。ないことが決まっているので空で成功とする
        private val NOT_FOUND: String = Resources.NotFoundException::class.java.name
        private val NAME_NOT_FOUND: String = PackageManager.NameNotFoundException::class.java.name

        // API 30 以下でだけ呼ぶので、flags を Int で渡す古い形を使う
        @Suppress("DEPRECATION")
        private fun visibleTo(
            packageManager: PackageManager,
            packageName: String,
        ): Boolean? =
            try {
                packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: RuntimeException) {
                null
            }

        private fun statusOf(status: ProfileReadStatus): ListingStatus =
            when (status) {
                ProfileReadStatus.COMPLETE -> ListingStatus.COMPLETE
                ProfileReadStatus.UNAVAILABLE -> ListingStatus.UNAVAILABLE
                ProfileReadStatus.FAILED -> ListingStatus.FAILED
            }

        // 区分は 0.2.0 と同じく理由と段から引き、名前で写す（2 つの列挙は同じ並び）
        fun failureOf(
            stage: MaterialStage,
            failure: ReadFailure,
        ): Failure =
            Failure(
                failure.cause,
                StateCategory.valueOf(MaterialCauses.categoryOf(stage, failure.cause).name),
                failure.frames(stage),
            )
    }
}
