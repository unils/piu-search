// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId

/**
 * 組 1 つの詳細の読み取り結果（architecture.md の「材料の欠け」）。段ごとの結果と失敗、読む前後の鍵の観測を別々に持つ。
 * 成否の判断（一覧の鍵との比較による `CHANGED_DURING_READ` を含む）は同期の規則（`IndexRules`）が行う。
 *
 * @property key プロファイルとパッケージの組
 * @property listed 一覧の入口（一覧の観測）
 * @property before 詳細を読む前の鍵
 * @property after 詳細を読んだ後の鍵
 * @property labels 一覧の表示名。取得が例外になった入口は null
 * @property entryFailure `ENTRY` の段の最初の失敗（詳細の問い合わせ・表示名の取得の例外、`VANISHED`）。前後の鍵の比較は含まない
 * @property localized 入口ごとの別の言語の名前
 * @property labelFailure `LABEL` の段の最初の失敗
 * @property signers 署名の DN
 * @property signerFailure `SIGNER` の段の失敗
 */
internal class PackageRead(
    val key: PackageKey,
    val listed: List<ActivityStamp>,
    val before: ApkKeys,
    val after: ApkKeys,
    val labels: Map<AppId, String?>,
    val entryFailure: ReadFailure?,
    val localized: Map<AppId, List<String>>,
    val labelFailure: ReadFailure?,
    val signers: List<String>,
    val signerFailure: ReadFailure?,
)

/**
 * 組 1 つを段ごとに読む。各段の catch は段の境界で、元の例外を [ReadFailure] に包んで運ぶ（architecture.md の「失敗の分け方」）。
 * 端末の部品は関数で渡す。偽の端末のテストも、この組み立てと分類を通すため。
 *
 * `Error` は捕まえない（続けて動かせる状態ではない）。
 */
internal fun readPackage(
    key: PackageKey,
    listed: List<ActivityStamp>,
    labelOf: (AppId) -> String,
    observeKeys: () -> ApkKeys,
    details: () -> List<LaunchableActivity>,
    readLabels: (LaunchableActivity) -> LabelRead,
    readSigners: (LaunchableActivity) -> List<String>,
): PackageRead {
    var entryFailure: ReadFailure? = null
    val labels = LinkedHashMap<AppId, String?>()
    for (stamp in listed) {
        labels[stamp.id] =
            try {
                labelOf(stamp.id)
            } catch (e: RuntimeException) {
                entryFailure = entryFailure ?: ReadFailure.of(e)
                null
            }
    }
    val before = observeKeys()
    val activities =
        try {
            details().associateBy { it.id }
        } catch (e: Exception) {
            entryFailure = entryFailure ?: ReadFailure.of(e)
            null
        }
    // 一覧にあった入口が詳細にない。競合かもしれず、削除とは決めない
    if (activities != null && listed.any { it.id !in activities }) {
        entryFailure = entryFailure ?: ReadFailure.code(MaterialCauses.VANISHED)
    }
    val archived = listed.any { it.isArchived }
    val localized = LinkedHashMap<AppId, List<String>>()
    var labelFailure: ReadFailure? = null
    var signers = emptyList<String>()
    var signerFailure: ReadFailure? = null
    val present = activities?.let { found -> listed.mapNotNull { found[it.id] } }.orEmpty()
    // アーカイブ済みの組は APK がないので補助の材料を読まない（正常な不在）。詳細を読めなかった組も、どの版の材料か分からないので読まない
    val auxiliaryRead = activities != null && !archived && present.isNotEmpty()
    if (auxiliaryRead) {
        for (activity in present) {
            val read =
                try {
                    readLabels(activity)
                } catch (e: Exception) {
                    LabelRead(emptyList(), e)
                }
            localized[activity.id] = read.names
            if (read.failure != null) labelFailure = labelFailure ?: ReadFailure.of(read.failure)
        }
        signers =
            try {
                readSigners(present.first())
            } catch (e: Exception) {
                signerFailure = ReadFailure.of(e)
                emptyList()
            }
    }
    val after = observeKeys()
    return PackageRead(
        key = key,
        listed = listed,
        before = before,
        after = after,
        labels = labels,
        entryFailure = entryFailure,
        localized = localized,
        labelFailure = labelFailure,
        signers = signers,
        signerFailure = signerFailure,
    )
}

/**
 * 端末のアプリから、索引の材料を読む。
 *
 * [AppSource] の一覧に、[LocalizedLabelReader] の別の言語の名前と [SignerReader] の署名者の DN を足す。
 * アプリの資源を開くので、画面のスレッドでは呼ばない。
 */
public class InstalledAppReader(
    /** 入口の一覧。 */
    public val source: AppSource,
    private val labels: LocalizedLabelReader,
    private val genres: GenreResolver,
    private val signers: SignerReader,
) {
    /** 端末の部品で作る。 */
    public constructor(context: Context) : this(
        AppSource(context),
        LocalizedLabelReader(context),
        GenreResolver(context.packageManager),
        SignerReader(context.packageManager),
    )

    /**
     * 読めるプロファイルの起動できるアプリ。入口ごとに 1 件。計器テストと調べもの用で、段の失敗は読めた材料だけで返す。
     */
    public fun readAll(): List<AppEntry> {
        val listing = source.listing(withUpdateTime = false)
        val stamps = listing.profiles.values.flatMap { it.items }
        val targets = stamps.groupBy { PackageKey(it.id.profileSerial, it.id.packageName) }
        val genreMap = genres.resolveStamps(stamps)
        return readPackages(listing, targets).values.flatMap { profile ->
            profile.items.flatMap { read ->
                read.listed.map {
                    AppEntry(
                        it.id,
                        read.labels[it.id] ?: it.id.packageName,
                        localizedLabels = read.localized[it.id].orEmpty(),
                        genres = genreMap[it.id].orEmpty(),
                        signerNames = read.signers,
                    )
                }
            }
        }
    }

    /**
     * [targets] の組を、一覧 [listing] の情報で読む。対象はプロファイルとパッケージの組で、ほかのプロファイルの同じパッケージは読まない。
     *
     * 読み終えた後にプロファイルを確かめ直し、読めなくなっていたら結果を捨てる（読み取り中の一時停止を正常な空にしないため）。
     */
    internal fun readPackages(
        listing: Listing,
        targets: Map<PackageKey, List<ActivityStamp>>,
    ): Map<Long, ProfileRead<PackageRead>> =
        targets.entries.groupBy { it.key.profileSerial }.mapValues { (serial, packages) ->
            val profile = listing.profile(serial) ?: return@mapValues ProfileRead(ProfileReadStatus.UNAVAILABLE)
            val reads =
                packages.map { (key, listed) ->
                    val info = checkNotNull(listing.info(listed.first().id)) { "一覧にない入口" }
                    readPackage(
                        key = key,
                        listed = listed,
                        labelOf = { id -> checkNotNull(listing.info(id)).label.toString() },
                        observeKeys = { source.observeKeys(info, profile.isCurrent) },
                        details = { source.details(key.packageName, profile) },
                        readLabels = labels::read,
                        readSigners = signers::read,
                    )
                }
            val status = source.profileReadStatus(serial)
            if (status == ProfileReadStatus.COMPLETE) ProfileRead(status, reads) else ProfileRead(status)
        }
}
