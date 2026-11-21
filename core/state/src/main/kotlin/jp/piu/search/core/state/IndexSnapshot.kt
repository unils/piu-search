// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText

/**
 * 組: プロファイル（のシリアル番号）とパッケージ名の対。索引を作り直す単位。
 *
 * @property profileSerial プロファイルのシリアル番号
 * @property packageName パッケージ名
 */
public data class PackageKey(
    val profileSerial: Long,
    val packageName: String,
)

/**
 * 一覧の時点の組 1 つの観測。同期のたびに読む安い値で、すべて [MaterialKey] に入る。
 *
 * @property entries 入口（ランチャーに並ぶ Activity）の名前の集合
 * @property archived アーカイブ済みか
 * @property apkKey base と split の APK のパス・時刻・大きさから作る値。作れなければ null（失敗ではない）
 * @property updatedAt パッケージの最後の更新の時刻（ミリ秒）。問い合わせなかった・作れなければ null。0 は有効な時刻
 */
public data class PackageObservation(
    val entries: Set<String>,
    val archived: Boolean,
    val apkKey: String?,
    val updatedAt: Long?,
) {
    /**
     * 変化を鍵で拾えない組か。APK の属性も更新の時刻もない組だけで、どちらかがあれば鍵の比較で拾える。
     * 片方だけの組まで再起動のたびに読み直すと、個人用の組（時刻は必ずある）を毎回の起動で読み直してしまう。
     */
    val keyless: Boolean get() = apkKey == null && updatedAt == null
}

/**
 * 同期のときの環境。言語・OS・部品の版は鍵に入り、変われば作り直す。
 *
 * @property locale 端末の言語（`LocaleList.toLanguageTags` の形）
 * @property osBuild OS のビルド
 * @property readerVersion 材料の読み取りの部品の版。読み方を変えたら上げる
 * @property generatorVersion キーの生成の規則の版。辞書・正規化・読みの補いを含む（`SearchIndexVersion` が対象にする範囲）
 * @property brandTableHash ブランド表の中身から作る値
 * @property appVersion このアプリの `versionCode`。欠けの行を書いた版と比べる
 * @property bootCount 再起動の回数（`Settings.Global.BOOT_COUNT`）。取れなければ null
 */
public data class Environment(
    val locale: String,
    val osBuild: String,
    val readerVersion: Int,
    val generatorVersion: Int,
    val brandTableHash: String,
    val appVersion: Int,
    val bootCount: Int?,
)

/**
 * 材料を何から読んだかの鍵。今の環境と観測から作った値と違えば、材料を読み直す。
 *
 * 入口はハッシュにせず集合のまま持つ。ハッシュの衝突で入口の変化を見逃さないため。
 *
 * @property apkKey [PackageObservation.apkKey]
 * @property updatedAt [PackageObservation.updatedAt]
 * @property entries [PackageObservation.entries]
 * @property archived [PackageObservation.archived]
 * @property locale [Environment.locale]
 * @property osBuild [Environment.osBuild]
 * @property readerVersion [Environment.readerVersion]
 */
public data class MaterialKey(
    val apkKey: String?,
    val updatedAt: Long?,
    val entries: Set<String>,
    val archived: Boolean,
    val locale: String,
    val osBuild: String,
    val readerVersion: Int,
) {
    /** 鍵の作り方。 */
    public companion object {
        /** 観測 [observed] と環境 [env] から作る今の鍵。 */
        public fun of(
            observed: PackageObservation,
            env: Environment,
        ): MaterialKey =
            MaterialKey(
                observed.apkKey,
                observed.updatedAt,
                observed.entries,
                observed.archived,
                env.locale,
                env.osBuild,
                env.readerVersion,
            )
    }
}

/**
 * キーを何から作ったかの鍵（材料の鍵のほかの分）。今の値と違えばキーを作り直す。
 *
 * 材料の世代は持たない。材料を読み直して成功した回は、同じ回にキーも作り直すため。
 *
 * @property generatorVersion [Environment.generatorVersion]
 * @property brandTableHash [Environment.brandTableHash]
 */
public data class DerivedKey(
    val generatorVersion: Int,
    val brandTableHash: String,
) {
    /** 鍵の作り方。 */
    public companion object {
        /** 環境 [env] から作る今の鍵。 */
        public fun of(env: Environment): DerivedKey = DerivedKey(env.generatorVersion, env.brandTableHash)
    }
}

/** 組の読み取りと生成の段。欠けの報告に使う。 */
public enum class Stage {
    /** 組全体の詳細（表示名・入口の変換・一覧との突き合わせ・読み取りの前後の鍵の比較）。 */
    ENTRY,

    /** 別の言語の名前。 */
    LABEL,

    /** 署名の DN。 */
    SIGNER,

    /** 検索キーの生成（このアプリの処理）。 */
    KEYS,
}

/**
 * 本当の失敗の区分。区分は読み手が理由から決めて渡す（例外の型の分類は Android の型を知る側にある）。
 * ないことが決まっているもの（その言語の名前の資源がない・署名がない・アーカイブ済みなど）は失敗にせず、成功した空として渡す。
 */
public enum class IssueCategory {
    /** 読み直せば直る見込みがある。通常のきっかけに加えて 1 回だけ読み直す。 */
    TRANSIENT,

    /** 材料（APK・OS・言語）が替わるまで同じ結果になる。 */
    DETERMINISTIC,

    /** このアプリ側の例外か、型で決められない例外。 */
    SUSPECTED_BUG,
}

/**
 * 段 1 つの本当の失敗。例外は持たず、保存する事実だけを持つ。
 *
 * @property cause 理由（R8 で変わらないクラス名か固定の符号）
 * @property category 区分
 * @property frames 不具合の疑いのときだけ、例外の先頭のフレーム
 * @property inputSummary キーの生成の失敗のときだけ、落ちた入力の種類と形
 */
public data class Failure(
    val cause: String,
    val category: IssueCategory,
    val frames: String? = null,
    val inputSummary: String? = null,
) {
    /** 固定の符号。 */
    public companion object {
        /** 読み取りの前後で、一覧の時点の鍵と違う観測が出た。 */
        public const val CHANGED_DURING_READ: String = "CHANGED_DURING_READ"
    }
}

/**
 * 組の最後の試みの欠け（組ごとに 1 つ）。「索引の状態」にはこれを出す。
 *
 * @property stage 失敗した段
 * @property failure 失敗
 * @property version 失敗した試みのこのアプリの `versionCode`。報告でフレームの出所（R8 の対応表）を示し、今の版と違えば組を作り直す。
 *   試みのたびに書き直すので、例外が起きた版と確かめた版は同じ値になる
 * @property firstFailedOn 続けて欠けている間の初めの日（UTC の `YYYY-MM-DD`）
 * @property lastFailedOn 最後に欠けた日（同上）
 */
public data class Issue(
    val stage: Stage,
    val failure: Failure,
    val version: Int,
    val firstFailedOn: String,
    val lastFailedOn: String,
)

/**
 * 検索に使う値。組の材料とキーがすべて成功した回の結果だけが入り、失敗した回は前の値を丸ごと保つ。
 * 由来の鍵（[materialKey]・[derivedKey]）は、この値を作ったときの鍵で、最後に試した鍵（[Attempt]）とは別に持つ。
 *
 * @property materialKey 材料を読んだときの鍵
 * @property derivedKey キーを作ったときの鍵
 * @property labels 入口ごとの表示名
 * @property localized 入口ごとの別の言語の名前
 * @property signerDns 署名の DN
 * @property keysByEntry 入口ごとのキー（ジャンルのキーは含まない）
 */
public data class Current(
    val materialKey: MaterialKey,
    val derivedKey: DerivedKey,
    val labels: Map<String, String>,
    val localized: Map<String, List<String>>,
    val signerDns: List<String>,
    val keysByEntry: Map<String, List<SearchKey>>,
)

/**
 * 組の最後の試み。同じ鍵で読み直さないかを、[Current] の鍵ではなくこちらの鍵で決める（失敗し続ける組を毎回読み直さないため）。
 *
 * @property materialKey 最後に材料を試した鍵（一覧の時点の観測と環境）
 * @property derivedKey 最後にキーを試した鍵
 * @property bootCount 最後に材料を試したときの再起動の回数
 * @property issue 最後の試みの欠け。成功なら null
 * @property retry 次の同期で、組を 1 回だけ作り直すか（一時的な失敗で立つ再試行の枠）
 */
public data class Attempt(
    val materialKey: MaterialKey,
    val derivedKey: DerivedKey,
    val bootCount: Int?,
    val issue: Issue?,
    val retry: Boolean,
)

/**
 * 組 1 つの状態。検索に使うのは [current] と [substitutes] で、[attempt] は次に読み直すかの判断と報告にだけ使う。
 *
 * 一覧の入口は、[current] が持つ入口か [substitutes] の入口のどちらか一方に必ず入る（[IndexSnapshot.restore] が確かめる）。
 *
 * @property key 組
 * @property observed 最後に当てた観測。入口の集合は、成否によらず今の一覧に合わせる
 * @property current 最後に全部が成功した回の値。失敗した回は前の値を保ち、一覧から消えた入口だけを落とす
 *   （消えた入口が後で戻ったとき、前の APK の提供元などのキーを戻さないため）。一度も成功していなければ null
 * @property substitutes [current] にない入口 → 代わりの表示名。失敗した回の試みで取れた表示名、なければ一覧に残り続けた入口の前の値、
 *   それもなければパッケージ名。キーは表示名から作る（[SubstituteKeys]）
 * @property attempt 最後の試み
 * @property genres 入口ごとのジャンル。同期のたびに問い合わせる（APK によらず変わるため）
 */
public data class PackageState(
    val key: PackageKey,
    val observed: PackageObservation,
    val current: Current?,
    val substitutes: Map<String, String>,
    val attempt: Attempt,
    val genres: Map<String, List<GenreId>>,
) {
    /** 入口ごとの照合の材料。代わりの表示名の入口は、表示名の代わりのキーで出す（保存せず、ここで作る）。 */
    public fun apps(): List<IndexedApp> =
        observed.entries.sorted().map { entry ->
            val built = current?.keysByEntry?.get(entry)
            val label = current?.labels?.get(entry) ?: substitutes.getValue(entry)
            IndexedApp(
                AppId(key.packageName, entry, key.profileSerial),
                label,
                (built ?: SubstituteKeys.orEmpty(label)) +
                    genres[entry].orEmpty().map { SearchKey(NormalizedText(it.value), SearchKeyKind.GENRE) },
            )
        }

    // 入れ子まで写し取る（呼ぶ側の可変の集まりを公開した写しに持ち込まない）
    internal fun frozen(): PackageState =
        PackageState(
            key,
            observed.copy(entries = observed.entries.toSet()),
            current?.let { c ->
                Current(
                    c.materialKey.copy(entries = c.materialKey.entries.toSet()),
                    c.derivedKey,
                    c.labels.toMap(),
                    c.localized.mapValues { it.value.toList() },
                    c.signerDns.toList(),
                    c.keysByEntry.mapValues { (_, keys) -> keys.map { it.copy(wordStarts = it.wordStarts.toList()) } },
                )
            },
            substitutes.toMap(),
            attempt.copy(materialKey = attempt.materialKey.copy(entries = attempt.materialKey.entries.toSet())),
            genres.mapValues { it.value.toList() },
        )

    // 検索に使う値・最後の試み・観測の整合。保存した写しの読み戻しで、壊れた組み合わせを検索に出したまま固定しないため
    internal fun isConsistent(): Boolean {
        val c = current
        val served = c?.labels?.keys.orEmpty()
        val tried = attempt.materialKey
        val parts =
            (c == null || c.labels.keys == c.keysByEntry.keys) &&
                served.none { it in substitutes } &&
                served + substitutes.keys == observed.entries
        // 試した鍵の観測の成分が観測と違えば、どちらかが壊れている
        val observedMatches =
            tried.entries == observed.entries && tried.apkKey == observed.apkKey && tried.updatedAt == observed.updatedAt &&
                tried.archived == observed.archived
        // 成功した試みは、同じ鍵の検索に使う値が全入口を持ち、代わりの表示名も再試行の枠もない
        val success =
            attempt.issue != null ||
                (c != null && c.materialKey == tried && c.derivedKey == attempt.derivedKey && substitutes.isEmpty() && !attempt.retry)
        return parts && observedMatches && success
    }
}

/** 一覧の読み取りの状態。空の一覧と、読めなかった一覧を取り違えないため。 */
public enum class ListingStatus {
    /** 最後まで読めた。空なら入口がない。 */
    COMPLETE,

    /** 一時停止・ロックなどで今は読めない。 */
    UNAVAILABLE,

    /** 問い合わせに失敗した。 */
    FAILED,
}

/**
 * 索引の写し（作った後は変えない）。作るのは同期の規則（[IndexRules.apply]）か、保存した写しの読み戻し（[restore]）だけ。
 *
 * @property packages 組ごとの状態
 * @property listings プロファイルごとの、最後の一覧の状態
 */
public class IndexSnapshot internal constructor(
    public val packages: Map<PackageKey, PackageState>,
    public val listings: Map<Long, ListingStatus> = emptyMap(),
) {
    /** 照合の材料。組と入口の名前の順。 */
    public fun apps(): List<IndexedApp> =
        packages.values
            .sortedWith(compareBy({ it.key.profileSerial }, { it.key.packageName }))
            .flatMap { it.apps() }

    /** 照合に使う索引。 */
    public fun toSearchIndex(): SearchIndex = apps().let { if (it.isEmpty()) SearchIndex.EMPTY else SearchIndex(it) }

    override fun equals(other: Any?): Boolean = other is IndexSnapshot && packages == other.packages && listings == other.listings

    override fun hashCode(): Int = 31 * packages.hashCode() + listings.hashCode()

    override fun toString(): String = "IndexSnapshot(packages=$packages, listings=$listings)"

    /** 作り方と定数。 */
    public companion object {
        /** 何もない写し。保存した索引が読めないときもここから作り直す。 */
        public val EMPTY: IndexSnapshot = IndexSnapshot(emptyMap())

        /**
         * 保存した写しの読み戻し。組を入れ子まで写し取り（呼ぶ側が組み立てに使った集まりを後で変えても、公開した写しが変わらないため）、
         * 組ごとに検索に使う値・最後の試み・観測の整合を確かめる。合わない組は写しに入れず、次の同期でまだない組として作り直す
         * （壊れた組を成功のまま固定すると、同じ鍵では読み直さないため）。同じ組が 2 つあれば [IllegalArgumentException] で止める。
         *
         * @param packages 組の状態
         * @param listings プロファイルごとの最後の一覧の状態
         */
        public fun restore(
            packages: Collection<PackageState>,
            listings: Map<Long, ListingStatus>,
        ): IndexSnapshot {
            val frozen = packages.map { it.frozen() }
            require(frozen.map { it.key }.toSet().size == frozen.size) { "同じ組が 2 つある" }
            return IndexSnapshot(frozen.filter { it.isConsistent() }.associateBy { it.key }, listings.toMap())
        }
    }
}
