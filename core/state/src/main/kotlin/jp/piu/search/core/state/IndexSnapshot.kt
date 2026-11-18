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
 * キーを何から作ったかの鍵。材料の鍵ではなく世代を持つ。
 *
 * 材料の鍵だけでは、署名の読み取りが失敗した後に同じ APK のまま読めるようになったとき、鍵が変わらず提供元のキーが古いまま残る。
 * 世代なら、材料を読み直した回は必ずキーも作り直す。
 *
 * @property materialsGeneration キーを作った材料の世代
 * @property generatorVersion [Environment.generatorVersion]
 * @property brandTableHash [Environment.brandTableHash]
 */
public data class DerivedKey(
    val materialsGeneration: Long,
    val generatorVersion: Int,
    val brandTableHash: String,
) {
    /** 鍵の作り方。 */
    public companion object {
        /** 世代 [generation] の材料と環境 [env] から作る今の鍵。 */
        public fun of(
            generation: Long,
            env: Environment,
        ): DerivedKey = DerivedKey(generation, env.generatorVersion, env.brandTableHash)
    }
}

/** 組の読み取りと生成の段。 */
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

/** 欠けの理由の区分。区分は読み手が理由から決めて渡す（例外の型の分類は Android の型を知る側にある）。 */
public enum class IssueCategory {
    /** 読み直せば直る見込みがある。通常のきっかけに加えて 1 回だけ読み直す。 */
    TRANSIENT,

    /** 材料（APK・OS・言語）が替わるまで同じ結果になる。 */
    DETERMINISTIC,

    /** このアプリ側の例外か、型で決められない例外。 */
    SUSPECTED_BUG,
}

/**
 * 段 1 つの失敗。例外は持たず、保存する事実だけを持つ。
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
 * 欠けの事実 1 つ（組と段 1 つ）。
 *
 * @property failure 最後の失敗
 * @property failedVersion [failure] が起きたときのこのアプリの `versionCode`。報告でフレームの出所（R8 の対応表）を示すのに使う
 * @property checkedVersion この段を最後に確かめたこのアプリの `versionCode`。今の版と違えば読み直す。
 *   確かめられなかった回に前の行を保つときは、こちらだけを進める（古い版がきっかけになり続けて収束しないため）
 * @property retryOnce 次の同期で 1 回だけ読み直すか
 * @property firstFailedOn 続けて欠けている間の初めの日（UTC の `YYYY-MM-DD`）
 * @property lastFailedOn 最後に欠けた日（同上）
 */
public data class Issue(
    val failure: Failure,
    val failedVersion: Int,
    val checkedVersion: Int,
    val retryOnce: Boolean,
    val firstFailedOn: String,
    val lastFailedOn: String,
)

/**
 * 組の材料。鍵が違うときだけ読む（資源と APK を開くので高い）。
 *
 * @property key 読んだときの鍵。成否によらず今の値
 * @property generation 世代。読むたびに成否によらず 1 進む
 * @property bootCount 読んだときの再起動の回数
 * @property labels 入口ごとの表示名
 * @property localized 入口ごとの別の言語の名前
 * @property signerDns 署名の DN
 * @property issues `ENTRY`・`LABEL`・`SIGNER` の欠け
 * @property substituted `ENTRY` が欠けた回に入った新しい入口。表示名は一覧の値かパッケージ名で、ほかの材料は旧い APK のものかもしれないので、
 *   キーだけを作り直すときも代わりのキーに留める。材料を読めた回に空になる
 */
public data class Materials(
    val key: MaterialKey,
    val generation: Long,
    val bootCount: Int?,
    val labels: Map<String, String>,
    val localized: Map<String, List<String>>,
    val signerDns: List<String>,
    val issues: Map<Stage, Issue>,
    val substituted: Set<String> = emptySet(),
)

/**
 * 材料から作ったキー。鍵が違うときだけ作る（形態素解析をするので高い）。
 *
 * @property key 作ったときの鍵。成否によらず今の値
 * @property keysByEntry 入口ごとのキー（ジャンルのキーは含まない）
 * @property issue `KEYS` の欠け
 */
public data class Derived(
    val key: DerivedKey,
    val keysByEntry: Map<String, List<SearchKey>>,
    val issue: Issue?,
)

/**
 * 組 1 つの状態。
 *
 * @property key 組
 * @property observed 最後に当てた観測
 * @property materials 材料
 * @property derived キー
 * @property genres 入口ごとのジャンル。同期のたびに問い合わせる（APK によらず変わるため）
 */
public data class PackageState(
    val key: PackageKey,
    val observed: PackageObservation,
    val materials: Materials,
    val derived: Derived,
    val genres: Map<String, List<GenreId>>,
) {
    /** 入口ごとの照合の材料。表示名のない入口はパッケージ名で出す。 */
    public fun apps(): List<IndexedApp> =
        observed.entries.sorted().map { entry ->
            IndexedApp(
                AppId(key.packageName, entry, key.profileSerial),
                materials.labels[entry] ?: key.packageName,
                derived.keysByEntry[entry].orEmpty() +
                    genres[entry].orEmpty().map { SearchKey(NormalizedText(it.value), SearchKeyKind.GENRE) },
            )
        }

    /** 未消費の再試行の印（材料か `KEYS`）があるか。 */
    public val retryPending: Boolean
        get() = materials.issues.values.any { it.retryOnce } || derived.issue?.retryOnce == true
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
 * 索引の写し（作った後は変えない）。
 *
 * @property packages 組ごとの状態
 * @property listings プロファイルごとの、最後の一覧の状態
 */
public data class IndexSnapshot(
    val packages: Map<PackageKey, PackageState>,
    val listings: Map<Long, ListingStatus> = emptyMap(),
) {
    /** 照合の材料。組と入口の名前の順。 */
    public fun apps(): List<IndexedApp> =
        packages.values
            .sortedWith(compareBy({ it.key.profileSerial }, { it.key.packageName }))
            .flatMap { it.apps() }

    /** 照合に使う索引。 */
    public fun toSearchIndex(): SearchIndex = apps().let { if (it.isEmpty()) SearchIndex.EMPTY else SearchIndex(it) }

    /** 定数。 */
    public companion object {
        /** 何もない写し。保存した索引が読めないときもここから作り直す。 */
        public val EMPTY: IndexSnapshot = IndexSnapshot(emptyMap())
    }
}
