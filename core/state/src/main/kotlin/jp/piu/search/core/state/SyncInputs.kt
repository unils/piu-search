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

/** 組を作り直す（材料を読み、キーを作る）きっかけ。 */
public enum class ReadReason {
    /** まだ試していない。 */
    MISSING,

    /** 最後に材料を試した鍵が今の鍵と違う。 */
    KEY_CHANGED,

    /** まだ当てていない知らせがある。 */
    NOTICE,

    /** 再試行の枠が立っている。 */
    RETRY,

    /** 欠けを書いた版が今のこのアプリの版と違う。 */
    VERSION,

    /** 鍵のない組で、再起動の回数が最後に試したときと違う。 */
    BOOT,

    /**
     * 最後にキーを試した鍵が今の鍵と違い、欠けが残っている。前の材料からキーだけを作り直しても欠けは直らず、
     * 成功すれば欠けを消してしまうので、組を作り直す。
     */
    DERIVED_CHANGED,
}

/**
 * 組を作り直す組 1 つの頼み。
 *
 * @property reasons きっかけ
 * @property auxiliary 補助の材料（別の言語の名前・署名）を読むか。アーカイブ済みの組は APK がないので読まない（空で成功とする）
 */
public data class MaterialJob(
    val reasons: Set<ReadReason>,
    val auxiliary: Boolean,
) {
    /**
     * きっかけが再試行だけか。このときだけ再試行の枠を消費する（また一時的に失敗しても枠を立て直さない）。
     * 鍵の変化や知らせと重なった回は、その変化に対する 1 回目の試みなので、失敗すれば枠を立て直す。
     */
    val retryOnly: Boolean get() = reasons == setOf(ReadReason.RETRY)
}

/**
 * 同期 1 回の計画（[IndexRules.plan] の出力）。前の写し・観測・環境を中に束ね、[IndexRules.prepare] に渡し直す引数をなくす
 * （別の回のものを組み合わせても気づけないため）。
 *
 * @property reads 作り直す組（材料を読み、キーを作る）
 * @property keys 材料は読まずにキーだけを作り直す組（欠けのない組で、キーの鍵だけが違う）
 * @property removals 消す候補の組。一覧と材料の両方が有効なプロファイルの分だけを [IndexRules.apply] が消す
 * @property genreTargets ジャンルを問い合わせる入口（一覧を読めたプロファイルのすべての入口）
 * @property noticeTargets 知らせの番号 → 解決した組。解決できなければ null で、知らせは残る
 */
public class SyncPlan internal constructor(
    public val reads: Map<PackageKey, MaterialJob>,
    public val keys: Set<PackageKey>,
    public val removals: Set<PackageKey>,
    public val genreTargets: List<AppId>,
    public val noticeTargets: Map<Long, PackageKey?>,
    internal val previous: IndexSnapshot,
    internal val observation: DeviceObservation,
    internal val env: Environment,
) {
    /**
     * 材料を読むかキーを作る組があるか。偽でも、組の削除・ジャンル・知らせの消費があるので [IndexRules.apply] は要る。
     * 写しを保存するかは、最後に保存できた写しと次の写しを比べて決める（この値では決めない）。
     */
    public val hasBuildWork: Boolean get() = reads.isNotEmpty() || keys.isNotEmpty()

    override fun toString(): String = "SyncPlan(reads=$reads, keys=$keys, removals=$removals, noticeTargets=$noticeTargets)"
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
 * 組 1 つの材料の読み取りの結果。成功（空を含む）と本当の失敗に分け、部分の結果は持たない（組ごとに全部か無かで当てるため）。
 * ないことが決まっているもの（その言語の名前の資源がない・名前が資源でない・署名がない・アーカイブ済み）は、空の値の成功にする。
 */
public sealed interface MaterialRead {
    /**
     * すべての段を読めた。前後の鍵の比較は [IndexRules] が行う。
     *
     * @property labels 入口ごとの表示名。一覧のすべての入口の分がいる（取れなかったなら [Failed] にする）
     * @property before 読む前の鍵
     * @property after 読んだ後の鍵
     * @property localized 入口ごとの別の言語の名前
     * @property signerDns 署名の DN
     */
    public data class Read(
        val labels: Map<String, String>,
        val before: KeyObservation,
        val after: KeyObservation,
        val localized: Map<String, List<String>> = emptyMap(),
        val signerDns: List<String> = emptyList(),
    ) : MaterialRead

    /**
     * 本当の失敗があった（一時的な失敗・不具合の疑い・材料が替わるまで続く失敗）。組は前の状態を丸ごと保つ。
     *
     * @property stage 失敗した段（[Stage.KEYS] 以外）
     * @property failure 失敗
     * @property labels 失敗の前に取れた表示名。前の値のない入口の代わりのキーにだけ使う
     */
    public data class Failed(
        val stage: Stage,
        val failure: Failure,
        val labels: Map<String, String> = emptyMap(),
    ) : MaterialRead
}

/**
 * プロファイル 1 つの材料の読み取りの結果。
 *
 * @property status 読み終えた後に確かめたプロファイルの状態。[ListingStatus.COMPLETE] でなければ結果を使わない
 *   （一時停止を正常な空にしないため）。一時停止などの読めない状態と、問い合わせの失敗を分ける
 * @property packages パッケージ名 → 結果
 * @property failure [ListingStatus.FAILED] の理由
 */
public data class ProfileReads(
    val status: ListingStatus,
    val packages: Map<String, MaterialRead> = emptyMap(),
    val failure: String? = null,
) {
    /** 結果を使えるか。 */
    val complete: Boolean get() = status == ListingStatus.COMPLETE
}

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
 * 生成器に頼む入口 1 つ。頼んだ同期 1 回（[IndexRules.prepare] の呼び出し 1 回）に結び付き、別の回の頼みとは等しくならない。
 * 入力が同じでも生成器の版やブランド表の違う回の結果を、今の回の結果として受けないため。
 *
 * @property entry 生成器の入力（ジャンルは入れない。ジャンルのキーは写しが別に足す）
 */
public class KeyJob internal constructor(
    public val entry: AppEntry,
    private val round: Any,
) {
    override fun equals(other: Any?): Boolean = other is KeyJob && other.round === round && other.entry == entry

    override fun hashCode(): Int = 31 * System.identityHashCode(round) + entry.hashCode()

    override fun toString(): String = "KeyJob(${entry.id})"
}

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
 * @property appliedProfiles 一覧と材料の両方が有効で、結果を当てたプロファイル。写しの [IndexSnapshot.listings] には、
 *   一覧が読めても材料を読み終えた後に読めなくなったプロファイルを、その読み取りの状態で残す
 * @property consumableNotices この写しを保存できたら消してよい知らせの番号（当てた組の知らせと、不在を確定した組の知らせ）
 * @property staleIcons アイコンを失効させる組（作り直しを試したか消した組）。キーやジャンルだけが変わった組は入らないので、
 *   保存の判断には使わない（最後に保存できた写しと [snapshot] を比べる）
 */
public data class SyncOutcome(
    val snapshot: IndexSnapshot,
    val appliedProfiles: Set<Long>,
    val consumableNotices: Set<Long>,
    val staleIcons: Set<PackageKey>,
)
