// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.SearchKey

/**
 * プロファイル 1 つの一覧の観測。
 *
 * @property status 読み取りの状態
 * @property packages パッケージ名 → 観測。[ListingStatus.COMPLETE] のときだけ意味を持つ
 */
public data class ProfileListing(
    val status: ListingStatus,
    val packages: Map<String, PackageObservation> = emptyMap(),
)

/**
 * 同期 1 回の観測。一覧の時点の 1 回だけ取る。
 *
 * @property profiles プロファイルごとの一覧。観測に出ないプロファイルの組は前の写しのまま保つ
 * @property removedProfiles 存在しないと確認できたプロファイル（有効な一覧で不在を確かめたものだけ）
 */
public data class DeviceObservation(
    val profiles: Map<Long, ProfileListing>,
    val removedProfiles: Set<Long> = emptySet(),
)

/**
 * パッケージの追加・変更・削除の知らせ 1 つ（`LauncherApps.Callback`）。
 *
 * @property seq 届いた順に増える番号
 * @property packageName パッケージ名
 * @property profileSerial 受けた時点で分かったシリアル番号。分からなければ null で、同期のときに [userHandle] から解決する
 * @property userHandle 通知元のユーザーの識別子（`UserHandle` の番号）
 */
public data class Notice(
    val seq: Long,
    val packageName: String,
    val profileSerial: Long?,
    val userHandle: Int,
)

/** 材料を読み直すきっかけ。 */
public enum class ReadReason {
    /** まだ材料がない。 */
    MISSING,

    /** 材料の鍵が今の鍵と違う。 */
    KEY_CHANGED,

    /** まだ当てていない知らせがある。 */
    NOTICE,

    /** `retryOnce` の欠けがある。 */
    RETRY,

    /** 欠けを書いた版が今のこのアプリの版と違う。 */
    VERSION,

    /** 鍵のない組で、再起動の回数が読んだときと違う。 */
    BOOT,
}

/** キーを作り直すきっかけ（材料を読み直さない組の分）。 */
public enum class KeyReason {
    /** キーの鍵が今の鍵と違う（生成器の版・ブランド表）。 */
    KEY_CHANGED,

    /** `KEYS` の欠けが一時的で、まだ再試行していない。 */
    RETRY,

    /** `KEYS` の欠けを書いた版が今のこのアプリの版と違う。 */
    VERSION,
}

/**
 * 材料を読む組 1 つの頼み。
 *
 * @property reasons 読むきっかけ
 * @property auxiliary 補助の材料（別の言語の名前・署名）を読むか。アーカイブ済みの組は APK がないので読まない
 */
public data class MaterialJob(
    val reasons: Set<ReadReason>,
    val auxiliary: Boolean,
) {
    /**
     * きっかけが再試行だけか。このときだけ再試行の印を消費する（また一時的に欠けても印を立て直さない）。
     * 鍵の変化などと重なった回は、その変化に対する 1 回目の読み取りなので印を残す。
     */
    val retryOnly: Boolean get() = reasons == setOf(ReadReason.RETRY)
}

/**
 * 同期 1 回の計画（[IndexRules.plan] の出力）。
 *
 * @property reads 材料を読む組。読んだ組はキーも作り直す
 * @property keys 材料は読まずにキーだけを作り直す組
 * @property removals 消す候補の組。一覧と材料の両方が有効なプロファイルの分だけを [IndexRules.apply] が消す
 * @property genreTargets ジャンルを問い合わせる入口（一覧を読めたプロファイルのすべての入口）
 * @property noticeTargets 知らせの番号 → 解決した組。解決できなければ null で、知らせは残る
 */
public data class SyncPlan(
    val reads: Map<PackageKey, MaterialJob>,
    val keys: Map<PackageKey, Set<KeyReason>>,
    val removals: Set<PackageKey>,
    val genreTargets: List<AppId>,
    val noticeTargets: Map<Long, PackageKey?>,
) {
    /** 材料もキーも作らない計画か。 */
    public val isIdle: Boolean get() = reads.isEmpty() && keys.isEmpty()
}

/**
 * 鍵の観測（材料を読む前か後）。
 *
 * @property apkKey [PackageObservation.apkKey]
 * @property updatedAt [PackageObservation.updatedAt]
 */
public data class KeyObservation(
    val apkKey: String?,
    val updatedAt: Long?,
)

/**
 * 組 1 つの材料の読み取りの結果。段ごとの値と失敗を別々に持ち、成否の判断（前後の鍵の比較を含む）は [IndexRules] が行う。
 *
 * @property labels 入口ごとの表示名。取れなかった入口は null（そのときは [entryFailure] がある）
 * @property entryFailure `ENTRY` の段の失敗。前後の鍵の比較は含まない
 * @property before 読む前の鍵
 * @property after 読んだ後の鍵
 * @property localized 入口ごとの別の言語の名前。読めた分だけ
 * @property labelFailure `LABEL` の段の失敗
 * @property signerDns 署名の DN
 * @property signerFailure `SIGNER` の段の失敗
 */
public data class MaterialRead(
    val labels: Map<String, String?>,
    val entryFailure: Failure?,
    val before: KeyObservation,
    val after: KeyObservation,
    val localized: Map<String, List<String>> = emptyMap(),
    val labelFailure: Failure? = null,
    val signerDns: List<String> = emptyList(),
    val signerFailure: Failure? = null,
)

/**
 * プロファイル 1 つの材料の読み取りの結果。
 *
 * @property complete 読み終えた後もプロファイルを読めたか。読めなくなっていたら結果を使わない（一時停止を正常な空にしないため）
 * @property packages パッケージ名 → 結果
 */
public data class ProfileReads(
    val complete: Boolean,
    val packages: Map<String, MaterialRead> = emptyMap(),
)

/** ジャンルの問い合わせの結果。「成功した空」と「失敗」を分ける。 */
public sealed interface GenreResult {
    /**
     * 問い合わせが成功した。
     *
     * @property byEntry 入口 → ジャンル。ない入口はジャンルなし
     */
    public data class Found(
        val byEntry: Map<AppId, List<GenreId>>,
    ) : GenreResult

    /**
     * 問い合わせが失敗した。既存のジャンルを保つ。
     *
     * @property cause 理由
     */
    public data class Failed(
        val cause: String,
    ) : GenreResult
}

/**
 * キーを作る入口 1 つの頼み。
 *
 * @property entry 生成器の入力（ジャンルは入れない。ジャンルのキーは写しが別に足す）
 * @property substitute 生成器を通さず、表示名を正規化しただけのキー（[SubstituteKeys]）にするか。
 *   `ENTRY` が欠けた組の新しい入口で、どの版の APK の材料か分からないため
 */
public data class KeyJob(
    val entry: AppEntry,
    val substitute: Boolean,
)

/** 入口 1 つのキーの生成の結果。 */
public sealed interface KeyOutcome {
    /**
     * 作れた。
     *
     * @property keys キー
     */
    public data class Built(
        val keys: List<SearchKey>,
    ) : KeyOutcome

    /**
     * 作れなかった。
     *
     * @property failure 失敗
     */
    public data class Failed(
        val failure: Failure,
    ) : KeyOutcome
}

/**
 * 同期 1 回の適用の結果（[IndexRules.apply] の出力）。
 *
 * @property snapshot 次の写し
 * @property appliedProfiles 一覧と材料の両方が有効で、結果を当てたプロファイル
 * @property consumableNotices この写しを保存できたら消してよい知らせの番号（当てた組の知らせと、不在を確定した組の知らせ）
 * @property changedPackages 材料を読み直したか消した組（アイコンを失効させる）
 */
public data class SyncOutcome(
    val snapshot: IndexSnapshot,
    val appliedProfiles: Set<Long>,
    val consumableNotices: Set<Long>,
    val changedPackages: Set<PackageKey>,
)
