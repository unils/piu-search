// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId

/**
 * 同期を始めたときの端末の状態。保存した [SyncState] と比べて差分の取り方を決め、保存後は次の [SyncState] になる。
 *
 * @property bootCount 再起動の回数。取れなければ null
 * @property locales 端末の言語
 * @property buildFingerprint OS のビルド
 * @property appVersionCode 今の Piu Search の `versionCode`
 */
internal data class DeviceState(
    val bootCount: Int?,
    val locales: String,
    val buildFingerprint: String,
    val appVersionCode: Int = 0,
)

/**
 * 差分の取り方。[SyncPlanner.scope] が決める。
 *
 * @property mode 取る方法
 * @property since `getChangedPackages` に渡す番号。番号を使わないなら 0（番号を進めるために、使わないときも問い合わせる）
 * @property bootChanged 再起動の回数が前回も今回も分かっていて、違う
 * @property versionChanged このアプリの版が保存した版と違う（保存した版がない場合を含む）
 */
internal data class SyncScope(
    val mode: SyncMode,
    val since: Int,
    val bootChanged: Boolean = false,
    val versionChanged: Boolean = false,
) {
    /** 変更の番号で差分を取るか。取らないなら、入口一覧に更新の時刻を付けて全件を見直す。 */
    val sequenceUsable: Boolean get() = mode == SyncMode.CHANGES
}

/**
 * 入口一覧と保存した索引を比べた結果。詳細を読む組（[reads]）とジャンルを問い合わせる入口（[stamps]）を決める。
 *
 * @property readable 入口一覧を最後まで読めた、消えていないプロファイル
 * @property profiles 一覧のプロファイルごとの状態
 * @property stamps [readable] の入口
 * @property listed [stamps] を組でまとめたもの
 * @property reads 詳細を読む組。値は、読む理由が `retry_once` だけか
 * @property removed 一覧を読めたプロファイルで、一覧から消えた組。読まずに索引から消す
 */
internal class SyncTargets(
    val readable: Set<Long>,
    val profiles: Map<Long, ProfileRead<ActivityStamp>>,
    val stamps: List<ActivityStamp>,
    val listed: Map<PackageKey, List<ActivityStamp>>,
    val reads: Map<PackageKey, Boolean>,
    val removed: Set<PackageKey>,
) {
    /** [IndexSync] が端末から詳細を読む組と、その一覧の入口。 */
    val readTargets: Map<PackageKey, List<ActivityStamp>> get() = reads.keys.associateWith { listed.getValue(it) }
}

/** 欠けの行 1 つの扱い。 */
internal sealed interface IssueDecision {
    /** 行を消す（読めた・正常な不在）。 */
    data object Delete : IssueDecision

    /** 行を書く。 */
    data class Put(
        val issue: MaterialIssue,
    ) : IssueDecision

    /** 前の行を保つ（`retry_once` は下ろす）。前の行がなければ何もしない。 */
    data object Keep : IssueDecision
}

/**
 * 読んだ組 1 つの計画。キーの生成は [IndexSync] が行い、`KEYS` の行もそのときに [SyncPlanner.issue] で決める。
 *
 * @property read 読み取りの結果
 * @property entryFailure `ENTRY` の段の失敗（前後の鍵の比較による `CHANGED_DURING_READ` を含む）
 * @property build キーを作る入口の材料。`ENTRY` が読めたなら一覧のすべての入口、欠けたなら新しい入口だけ（[substitute] が真）
 * @property kept `ENTRY` が欠けた組で、一覧にも旧索引にもある入口の旧索引の行
 * @property substitute [build] を正規化だけのキーにするか（`ENTRY` が欠けた組の新しい入口）
 * @property issues `ENTRY`・`LABEL`・`SIGNER` の行の扱い
 * @property retryOnly 読んだ理由が `retry_once` だけか
 */
internal class PackagePlan(
    val read: PackageRead,
    val entryFailure: ReadFailure?,
    val build: List<AppEntry>,
    val kept: List<StoredApp>,
    val substitute: Boolean,
    val issues: Map<MaterialStage, IssueDecision>,
    val retryOnly: Boolean,
) {
    /** 組。 */
    val key: PackageKey get() = read.key

    /** 保存する鍵。成否によらず一覧の観測の値（architecture.md の「鍵の比べ方」）。 */
    val keys: ApkKeys get() = read.listedKeys
}

/**
 * 同期 1 回の計画。保存と反映は [IndexSync] が書き手で行う。
 *
 * @property mode 取った方法
 * @property state 保存する同期の状態
 * @property packages 読んだ組の計画。成否によらず、組の行と一覧の観測と欠けの行を書き直す
 * @property removed 索引から消す組（一覧から消えた組）
 * @property completed 一覧も詳細も読めたプロファイル。ここにないプロファイルの索引と欠けの行は保つ
 * @property pending 作り直しの印を立てる組（読めないプロファイルの組）
 * @property changedPackages 読み直したか消したパッケージ。アイコンを失効させる
 * @property genres 入口ごとのジャンル。問い合わせに失敗したら null で、以前のジャンルを保つ
 * @property profileResults プロファイルごとの結果
 * @property profileFailures `FAILED` のプロファイルの理由
 */
internal class SyncPlan(
    val mode: SyncMode,
    val state: SyncState,
    val packages: List<PackagePlan>,
    val removed: Set<PackageKey>,
    val completed: Set<Long>,
    val pending: Set<PackageKey>,
    val changedPackages: Set<String>,
    val genres: Map<AppId, List<GenreId>>?,
    val profileResults: Map<Long, ProfileReadStatus>,
    val profileFailures: Map<Long, String>,
)

/**
 * 差分の同期の規則（architecture.md の「何を読み直すか」「材料の欠け」「読み直しの規則」）。Room と端末に触れず、渡された結果だけで決める。
 *
 * 端末の読み取りは前の段の結果で範囲が決まるので、3 段に分ける。[IndexSync] が段の間で端末を読む。
 * 1. [scope]: 保存した状態と今の端末の状態から、差分の取り方を決める。→ 変更の番号と入口一覧を読む
 * 2. [targets]: 入口一覧・保存した索引・欠けの行を比べ、読む組と理由を決める。→ ジャンルと詳細を読む
 * 3. [plan]: 段ごとの読み取りの結果から、組ごとの行・鍵・欠けの行・印と次の状態を決める
 *
 * プロファイルが消えたかは isPresent で渡す（[ProfileReconciler] が確定した削除）。消えたプロファイルの入口は読まず、索引から落とす。
 */
internal object SyncPlanner {
    /**
     * 差分の取り方を決める。索引がない（[previous] が null）・言語・OS のビルドが変われば全件を作り直す。
     * 再起動の回数が違う・不明、または前回の番号がなければ、変更の番号が使えないので全件の更新時刻を見直す。
     */
    fun scope(
        previous: SyncState?,
        device: DeviceState,
    ): SyncScope {
        val full = previous == null || previous.locales != device.locales || previous.buildFingerprint != device.buildFingerprint
        val sequence = previous?.changeSequence
        val bootChanged = previous?.bootCount != null && device.bootCount != null && previous.bootCount != device.bootCount
        val versionChanged = previous?.appVersionCode != device.appVersionCode
        return when {
            full -> SyncScope(SyncMode.FULL_REBUILD, 0, bootChanged, versionChanged)
            device.bootCount != null && previous.bootCount == device.bootCount && sequence != null ->
                SyncScope(SyncMode.CHANGES, sequence, false, versionChanged)
            else -> SyncScope(SyncMode.FULL_REVIEW, 0, bootChanged, versionChanged)
        }
    }

    /**
     * 入口一覧 [profiles] と保存した索引 [stored]・欠けの行 [issues] を比べ、読む組を決める。
     *
     * @param changed 変更の番号に出たパッケージ。[SyncScope.sequenceUsable] のときだけ見る
     * @param forced 鍵が同じでも読み直す組（Callback の知らせ）
     */
    fun targets(
        scope: SyncScope,
        stored: List<StoredApp>,
        issues: List<MaterialIssue>,
        isPresent: (Long) -> Boolean,
        profiles: Map<Long, ProfileRead<ActivityStamp>>,
        changed: Set<String>,
        forced: Set<PackageKey>,
    ): SyncTargets {
        val readable = profiles.filter { (serial, read) -> read.status == ProfileReadStatus.COMPLETE && isPresent(serial) }.keys
        val stamps = readable.flatMap { profiles.getValue(it).items }
        val listed = stamps.groupBy { PackageKey(it.id.profileSerial, it.id.packageName) }
        val old = stored.groupBy { it.key }
        val issued = issues.groupBy { it.key }
        val reads = LinkedHashMap<PackageKey, Boolean>()
        for ((key, now) in listed) {
            val normal = isDirty(scope, key, old[key], now, issued[key], changed, forced)
            val retry = issued[key].orEmpty().any { it.retryOnce }
            if (normal || retry) reads[key] = !normal
        }
        val removed = old.keys.filterTo(HashSet()) { it.profileSerial in readable && it !in listed }
        return SyncTargets(readable, profiles, stamps, listed, reads, removed)
    }

    // 組の通常の条件（retry_once を除く）。欠けの行があるだけでは読み直さない
    private fun isDirty(
        scope: SyncScope,
        key: PackageKey,
        old: List<StoredApp>?,
        now: List<ActivityStamp>,
        issues: List<MaterialIssue>?,
        changed: Set<String>,
        forced: Set<PackageKey>,
    ): Boolean {
        if (scope.mode == SyncMode.FULL_REBUILD || old == null) return true
        val stamp = now.first()
        val stored = old.first()
        val keyChanged =
            if (stamp.isCurrentProfile) {
                // 個人用は変更の番号で拾う。番号が使えないときだけ時刻を比べる
                if (scope.sequenceUsable) key.packageName in changed else changed(stored.packageUpdatedAt, stamp.packageUpdatedAt)
            } else {
                // 仕事用は変更の番号に出ないので、毎回指紋を比べる。鍵も時刻もない組は、再起動 1 回につき 1 回だけ読む
                // （プロセスがない間の更新の取りこぼしを抑えるため。architecture.md の「鍵の比べ方」）
                val keyless =
                    stored.apkFingerprint == null && stamp.apkFingerprint == null &&
                        stored.packageUpdatedAt == null && stamp.packageUpdatedAt == null
                changed(stored.apkFingerprint, stamp.apkFingerprint) || (keyless && scope.bootChanged)
            }
        return keyChanged || key in forced ||
            old.any { it.rebuildPending } ||
            (scope.versionChanged && !issues.isNullOrEmpty()) ||
            old.any { it.archived } != now.any { it.isArchived } ||
            old.mapTo(HashSet()) { it.app.id } != now.mapTo(HashSet()) { it.id }
    }

    /**
     * 鍵の比べ方の 4 通り（architecture.md の「鍵の比べ方」）。両方ともなければ変わっていないとみなす。
     * 両方ともない組を毎回読み直すと、同期が収束しないため。0 は APEX の中の APK の本当の時刻なので、ほかの値と同じく比べる。
     */
    fun <T> changed(
        previous: T?,
        current: T?,
    ): Boolean = previous != current

    /**
     * 段ごとの読み取りの結果 [candidates] から計画を作る。
     *
     * @param sequence 今の変更の番号。次の状態に保存する
     * @param genres ジャンルの問い合わせの結果。失敗なら null
     * @param candidates [SyncTargets.readTargets] の詳細。プロファイルごと
     * @param today 欠けの行の日（UTC の `YYYY-MM-DD`）
     */
    @Suppress("LongParameterList")
    fun plan(
        scope: SyncScope,
        device: DeviceState,
        targets: SyncTargets,
        stored: List<StoredApp>,
        issues: List<MaterialIssue>,
        isPresent: (Long) -> Boolean,
        sequence: Int,
        genres: Map<AppId, List<GenreId>>?,
        candidates: Map<Long, ProfileRead<PackageRead>>,
        today: String,
    ): SyncPlan {
        // 一覧と詳細の両方を読めたプロファイルだけで、読んだ結果と削除を当てる
        val completed =
            targets.readable.filterTo(HashSet()) { serial ->
                candidates[serial]?.status?.let { it == ProfileReadStatus.COMPLETE } ?: true
            }
        val old = stored.groupBy { it.key }
        val issued = issues.groupBy { it.key }
        val packages =
            candidates
                .filterKeys { it in completed }
                .values
                .flatMap { it.items }
                .filter { it.key in targets.reads }
                .map { packagePlan(it, old[it.key].orEmpty(), issued[it.key].orEmpty(), targets.reads.getValue(it.key), device, today) }
        val removed = targets.removed.filterTo(HashSet()) { it.profileSerial in completed }
        // 全プロファイルに当てるべき読み直しを、読めないプロファイルに当てられなかったことを印で覚える
        val pending =
            old.keys.filterTo(HashSet()) { key ->
                isPresent(key.profileSerial) && key.profileSerial !in completed &&
                    (scope.mode == SyncMode.FULL_REBUILD || (scope.versionChanged && key in issued)) &&
                    old.getValue(key).any { !it.rebuildPending }
            }
        val results =
            targets.profiles.mapValues { (serial, listing) ->
                if (listing.status == ProfileReadStatus.COMPLETE) candidates[serial]?.status ?: listing.status else listing.status
            }
        val failures =
            targets.profiles
                .filterValues { it.status == ProfileReadStatus.FAILED }
                .mapValues { it.value.failure ?: Throwable::class.java.name } +
                candidates.filterValues { it.status == ProfileReadStatus.FAILED }.mapValues { it.value.failure ?: Throwable::class.java.name }
        return SyncPlan(
            mode = scope.mode,
            // 読めなかった組には印が立ち、再開後に作り直される。前の言語・ビルドを残すと、再開までの同期がすべて全件の作り直しになり、
            // 読めたプロファイルを毎回作り直してしまう
            state = SyncState(sequence, device.bootCount, device.locales, device.buildFingerprint, device.appVersionCode),
            packages = packages,
            removed = removed,
            completed = completed,
            pending = pending,
            changedPackages = (packages.map { it.key.packageName } + removed.map { it.packageName }).toSet(),
            genres = genres,
            profileResults = results,
            profileFailures = failures,
        )
    }

    private fun packagePlan(
        read: PackageRead,
        old: List<StoredApp>,
        issues: List<MaterialIssue>,
        retryOnly: Boolean,
        device: DeviceState,
        today: String,
    ): PackagePlan {
        val entryFailure = read.entryFailure ?: changedDuringRead(read)
        val previous = issues.associateBy { it.stage }

        fun decide(
            stage: MaterialStage,
            failure: ReadFailure?,
        ) = issue(read.key, stage, failure, null, previous[stage], retryOnly, device.appVersionCode, today)
        val decisions = LinkedHashMap<MaterialStage, IssueDecision>()
        decisions[MaterialStage.ENTRY] = decide(MaterialStage.ENTRY, entryFailure)
        when {
            // どの版の APK の材料か分からないので、その回の補助の材料の結果は使わない
            entryFailure != null -> {
                decisions[MaterialStage.LABEL] = IssueDecision.Keep
                decisions[MaterialStage.SIGNER] = IssueDecision.Keep
            }
            // アーカイブ済みは APK がなく、補助の材料がないのは正常な不在
            !read.auxiliaryRead -> {
                decisions[MaterialStage.LABEL] = IssueDecision.Delete
                decisions[MaterialStage.SIGNER] = IssueDecision.Delete
            }
            else -> {
                decisions[MaterialStage.LABEL] = decide(MaterialStage.LABEL, read.labelFailure)
                decisions[MaterialStage.SIGNER] = decide(MaterialStage.SIGNER, read.signerFailure)
            }
        }
        val oldById = old.associateBy { it.app.id }
        return if (entryFailure == null) {
            val signers = if (read.signerFailure == null) read.signers else emptyList()
            val entries =
                read.listed.map {
                    AppEntry(it.id, checkNotNull(read.labels[it.id]), localizedLabels = read.localized[it.id].orEmpty(), signerNames = signers)
                }
            PackagePlan(read, null, entries, emptyList(), false, decisions, retryOnly)
        } else {
            val kept = read.listed.mapNotNull { oldById[it.id] }
            val fresh =
                read.listed
                    .filter { it.id !in oldById }
                    .map { AppEntry(it.id, read.labels[it.id] ?: it.id.packageName) }
            PackagePlan(read, entryFailure, fresh, kept, true, decisions, retryOnly)
        }
    }

    // 一覧の時点で鍵が取れなければ前後を比べない（片方がない比較では替わったかを決められない）
    private fun changedDuringRead(read: PackageRead): ReadFailure? {
        val listed = read.listedKeys
        val fingerprintMoved =
            listed.fingerprint != null && (read.before.fingerprint != listed.fingerprint || read.after.fingerprint != listed.fingerprint)
        val timeMoved = listed.updatedAt != null && (read.before.updatedAt != listed.updatedAt || read.after.updatedAt != listed.updatedAt)
        return if (fingerprintMoved || timeMoved) ReadFailure.code(MaterialCauses.CHANGED_DURING_READ) else null
    }

    /**
     * 段 [stage] の欠けの行を決める（architecture.md の「読み直しの規則」の表）。
     *
     * 読めたら消す。欠けたら書き、区分が一時的で、`retry_once` 以外のきっかけで読んだときだけ `retry_once` を立てる。
     * 続けて欠けている間は初めの日を保つ。フレームは不具合の疑いの行だけに残す。
     */
    @Suppress("LongParameterList")
    fun issue(
        key: PackageKey,
        stage: MaterialStage,
        failure: ReadFailure?,
        inputSummary: String?,
        previous: MaterialIssue?,
        retryOnly: Boolean,
        versionCode: Int,
        today: String,
    ): IssueDecision {
        if (failure == null) return IssueDecision.Delete
        val cause = failure.cause
        val category = MaterialCauses.categoryOf(stage, cause)
        return IssueDecision.Put(
            MaterialIssue(
                key = key,
                stage = stage,
                cause = cause,
                frames = failure.frames(stage),
                inputSummary = inputSummary.takeIf { stage == MaterialStage.KEYS },
                versionCode = versionCode,
                retryOnce = category == IssueCategory.TRANSIENT && !retryOnly,
                firstFailedOn = previous?.firstFailedOn ?: today,
                lastFailedOn = today,
            ),
        )
    }
}

internal val StoredApp.key: PackageKey get() = PackageKey(app.id.profileSerial, app.id.packageName)
