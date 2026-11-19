// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.KeyForm
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.TextNormalizer
import java.text.Normalizer

/**
 * 索引の同期の規則（鍵つきのメモ化）。端末に触れず、渡された観測と結果だけで決める純粋な関数。
 *
 * 同期 1 回は次の順に進む。端末の読み取りとキーの生成は呼ぶ側が段の間で行う。
 * 1. [plan]: 前の写し・観測・環境・まだ当てていない知らせから、作り直す組・キーだけを作る組・消す組を決める。→ 材料とジャンルを読む
 * 2. [prepare]: 読んだ結果を受け、生成器に頼む入口（[PreparedSync.jobs]）を決める。→ キーを作る
 * 3. [apply]: [prepare] の結果と生成の結果から次の写しを作る
 *
 * 組ごとに全部か無かで当てる。材料の読み取りとキーの生成がすべて成功した組だけ、検索に使う値（[Current]）を置き換える。
 * 本当の失敗が 1 つでもあれば、検索に使う値は前のまま丸ごと保ち、最後の試み（[Attempt]）にだけ今の鍵と欠けを書く。
 * 段ごとに読めた分だけを採ると、欠けた段の古い材料と新しい材料が混ざる組み合わせが増え、穴が続いたため。
 *
 * 更新と不在による削除は、一覧と材料の両方が有効なプロファイルにだけ当てる。ほかのプロファイルの組は前の写しのまま保ち、
 * 鍵が古いまま残るので、読めるようになった同期で [plan] が拾う。
 */
public object IndexRules {
    /**
     * 同期 1 回の計画を作る。
     *
     * @param previous 前の写し
     * @param observation 一覧の時点の観測
     * @param env 今の環境
     * @param notices まだ当てていない知らせ（同期を始めた時点の分）
     * @param userSerials 通知元のユーザーの識別子 → シリアル番号。受けた時点で番号が分からなかった知らせの解決に使う
     */
    public fun plan(
        previous: IndexSnapshot,
        observation: DeviceObservation,
        env: Environment,
        notices: Collection<Notice> = emptyList(),
        userSerials: Map<Int, Long> = emptyMap(),
    ): SyncPlan {
        // 写しは作った後に変えないので、呼ぶ側の集まりを入れ子まで写し取る。呼ぶ側が後で中身を変えても、公開した写しと次の計画は変わらない
        val frozen = observation.frozen()
        val noticeTargets =
            notices.associate { notice ->
                notice.seq to (notice.profileSerial ?: userSerials[notice.userHandle])?.let { PackageKey(it, notice.packageName) }
            }
        val noticed = noticeTargets.values.filterNotNullTo(HashSet())
        // 頼みをこの計画に結び付ける印。別の計画の読み取りの結果を受けないため
        val round = Any()
        val reads = LinkedHashMap<PackageKey, MaterialJob>()
        val keys = LinkedHashSet<PackageKey>()
        val genreTargets = ArrayList<AppId>()
        val removals = previous.packages.keys.filterTo(HashSet()) { it.profileSerial in frozen.removedProfiles }
        for ((serial, listing) in frozen.profiles.toSortedMap()) {
            if (serial in frozen.removedProfiles || listing.status != ListingStatus.COMPLETE) continue
            for ((name, observed) in listing.packages.toSortedMap()) {
                val key = PackageKey(serial, name)
                observed.entries.sorted().mapTo(genreTargets) { AppId(name, it, serial) }
                val attempt = previous.packages[key]?.attempt
                val reasons = readReasons(attempt, observed, env, key in noticed)
                if (reasons.isNotEmpty()) {
                    reads[key] = MaterialJob(key, reasons, auxiliary = !observed.archived, round)
                } else if (checkNotNull(attempt).derivedKey != DerivedKey.of(env)) {
                    keys += key
                }
            }
            previous.packages.keys.filterTo(removals) { it.profileSerial == serial && it.packageName !in listing.packages }
        }
        return SyncPlan(reads, keys, removals, GenreRequest(genreTargets), noticeTargets, previous, frozen, env)
    }

    /**
     * 材料の読み取りの結果を受け、キーの生成を待つ形にする。生成器に頼む入口は [PreparedSync.jobs]。
     *
     * 作り直す組は、読み取りが成功したならすべての入口を生成器に頼み、本当の失敗なら何も頼まない（前の状態を保つため）。
     * 読む前後で鍵が動いた組は `ENTRY` の [Failure.CHANGED_DURING_READ] とする。キーだけを作る組は、検索に使う値の材料から作る。
     * 当てられないプロファイルの組は扱わない。
     *
     * 結果は計画の頼みとちょうど合うものだけを受ける。材料を頼まなかったプロファイルの結果・別の計画の頼みの結果・頼みの欠け・
     * 補助の材料を頼まなかった組の補助の材料・一覧の入口の分そろわない表示名は、呼ぶ側の誤りとして [IllegalArgumentException] で止める。
     *
     * @param plan [plan] の結果
     * @param reads プロファイルごとの材料の読み取りの結果。材料を頼んだプロファイルの分だけがちょうどいる
     * @param today 欠けの日（UTC の `YYYY-MM-DD`）
     */
    public fun prepare(
        plan: SyncPlan,
        reads: Map<Long, ProfileReads>,
        today: String,
    ): PreparedSync {
        val frozenReads = reads.frozen()
        val jobsBySerial = plan.reads.values.groupBy { it.key.profileSerial }
        require(frozenReads.keys == jobsBySerial.keys) { "材料の読み取りのプロファイルが頼みと合わない: ${frozenReads.keys} ${jobsBySerial.keys}" }
        for ((serial, profile) in frozenReads) {
            if (profile.complete) {
                require(profile.packages.keys == jobsBySerial.getValue(serial).toSet()) { "材料の読み取りが頼みと合わない: プロファイル $serial" }
            }
        }
        val statuses = statuses(plan, frozenReads)
        val applicable = { key: PackageKey -> statuses[key.profileSerial] == ListingStatus.COMPLETE }
        // 頼みをこの呼び出しに結び付ける印。別の回の生成の結果を受けないため
        val round = Any()
        val packages = LinkedHashMap<PackageKey, PreparedPackage>()
        for ((key, job) in plan.reads) {
            if (!applicable(key)) continue
            val read = frozenReads.getValue(key.profileSerial).packages.getValue(job)
            packages[key] = rebuild(key, observed(plan, key), job, read, plan.env, round)
        }
        for (key in plan.keys) {
            if (!applicable(key)) continue
            val current = checkNotNull(plan.previous.packages[key]?.current) { "検索に使う値のない組のキーだけを作る: $key" }
            val materials = Materials(current.materialKey, current.labels, current.localized, current.signerDns)
            packages[key] = PreparedPackage(key, observed(plan, key), read = false, retryOnly = false, materials, null, emptyMap(), round)
        }
        return PreparedSync(plan, statuses, today, packages)
    }

    /**
     * 次の写しを作る。
     *
     * @param prepared [prepare] の結果
     * @param genres ジャンルの問い合わせの結果。同じ計画の頼み（[SyncPlan.genres]）に答えたものだけを受ける
     * @param built 生成器の結果。[PreparedSync.jobs] のすべての頼みの分がちょうどいる。別の回の頼みの結果を渡すと止まる
     */
    public fun apply(
        prepared: PreparedSync,
        genres: GenreResult,
        built: Map<KeyJob, KeyOutcome>,
    ): SyncOutcome {
        require(
            built.keys == prepared.jobs.toSet(),
        ) { "生成の結果が頼みと合わない: ${built.keys - prepared.jobs.toSet()} ${prepared.jobs - built.keys}" }
        val plan = prepared.plan
        require(genres.request === plan.genres) { "ジャンルの結果が別の計画の頼みに答えている" }
        val genreResult = genres.frozen()
        if (genreResult is GenreResult.Found) {
            require(plan.genres.targets.containsAll(genreResult.byEntry.keys)) { "頼んでいない入口のジャンル" }
        }
        val previous = plan.previous
        val observation = plan.observation
        val statuses = prepared.statuses
        val valid = prepared.appliedProfiles
        val outcomes = built.frozen()
        val removed = observation.removedProfiles
        val next = LinkedHashMap(previous.packages)
        val staleIcons = HashSet<PackageKey>()
        for (key in plan.removals) {
            if (key.profileSerial in removed || key.profileSerial in valid) {
                next.remove(key)
                staleIcons += key
            }
        }
        for (serial in valid.sorted()) {
            for ((name, observed) in checkNotNull(observation.profiles[serial]).packages) {
                val key = PackageKey(serial, name)
                val prev = previous.packages[key]
                val pkg = prepared.packages[key]
                if (pkg?.read == true) staleIcons += key
                val state =
                    pkg?.let { settle(it, prev, outcomes, plan.env, prepared.today) } ?: checkNotNull(prev).copy(observed = observed)
                next[key] = state.copy(genres = genresOf(key, observed, prev?.genres.orEmpty(), genreResult))
            }
        }
        val listings = (previous.listings + statuses).filterKeys { it !in removed }
        val consumable =
            plan.noticeTargets
                .filterValues { it != null && (it.profileSerial in valid || it.profileSerial in removed) }
                .keys
        return SyncOutcome(IndexSnapshot(next, listings), valid, consumable, staleIcons)
    }

    // プロファイルごとの判定（消えたプロファイルは除く）。一覧の状態・更新・削除・知らせの消費は、すべてこれだけから決める。
    // 一覧が最後まで読めて、材料を読んだなら読み終えた後も読めたプロファイルだけが COMPLETE
    private fun statuses(
        plan: SyncPlan,
        reads: Map<Long, ProfileReads>,
    ): Map<Long, ListingStatus> =
        plan.observation.profiles
            .filterKeys { it !in plan.observation.removedProfiles }
            .mapValues { (serial, listing) ->
                if (listing.status == ListingStatus.COMPLETE) reads[serial]?.status ?: ListingStatus.COMPLETE else listing.status
            }

    // 比べるのは最後に試した鍵（検索に使う値の鍵ではない）。失敗しても試した鍵は今の値になるので、同じ鍵では読み直さない
    private fun readReasons(
        attempt: Attempt?,
        observed: PackageObservation,
        env: Environment,
        noticed: Boolean,
    ): Set<ReadReason> {
        if (attempt == null) return setOf(ReadReason.MISSING)
        val reasons = HashSet<ReadReason>()
        val issue = attempt.issue
        if (attempt.materialKey != MaterialKey.of(observed, env)) reasons += ReadReason.KEY_CHANGED
        if (noticed) reasons += ReadReason.NOTICE
        if (attempt.retry) reasons += ReadReason.RETRY
        if (issue != null && issue.version != env.appVersion) reasons += ReadReason.VERSION
        if (issue != null && attempt.derivedKey != DerivedKey.of(env)) reasons += ReadReason.DERIVED_CHANGED
        // 回数が取れない端末では読み直さない。プロセスがない間の更新の取りこぼしは知らせに頼る
        if (observed.keyless && env.bootCount != null && attempt.bootCount != env.bootCount) reasons += ReadReason.BOOT
        return reasons
    }

    private fun observed(
        plan: SyncPlan,
        key: PackageKey,
    ): PackageObservation =
        checkNotNull(
            plan.observation.profiles[key.profileSerial]
                ?.packages
                ?.get(key.packageName),
        ) { "一覧にない組: $key" }

    // 一覧の時点で鍵がなければ前後を比べない（片方がない比較では替わったかを決められない）
    private fun changedDuringRead(
        observed: PackageObservation,
        read: MaterialRead.Read,
    ): Boolean {
        val apkMoved = observed.apkKey != null && (read.before.apkKey != observed.apkKey || read.after.apkKey != observed.apkKey)
        val timeMoved =
            observed.updatedAt != null && (read.before.updatedAt != observed.updatedAt || read.after.updatedAt != observed.updatedAt)
        return apkMoved || timeMoved
    }

    @Suppress("LongParameterList")
    private fun rebuild(
        key: PackageKey,
        observed: PackageObservation,
        job: MaterialJob,
        read: MaterialRead,
        env: Environment,
        round: Any,
    ): PreparedPackage {
        fun failed(
            stage: Stage,
            failure: Failure,
            labels: Map<String, String>,
        ) = PreparedPackage(key, observed, read = true, job.retryOnly, null, stage to failure, labels, round)
        return when (read) {
            is MaterialRead.Failed -> {
                require(read.stage != Stage.KEYS) { "読み取りの失敗に KEYS の段: $key" }
                failed(read.stage, read.failure, read.labels)
            }

            is MaterialRead.Read -> {
                // アーカイブ済みに補助の材料が来たら、別の頼みの結果か読み手の誤り。採ると APK のない組に古い提供元が付く
                require(job.auxiliary || (read.localized.isEmpty() && read.signerDns.isEmpty())) { "補助の材料を頼んでいない組の補助の材料: $key" }
                // 表示名の欠けた成功を受けると、パッケージ名のキーを今の鍵で確定し、次の同期で読み直さない
                require(read.labels.keys == observed.entries) { "表示名が一覧の入口と合わない: $key ${read.labels.keys} ${observed.entries}" }
                if (changedDuringRead(observed, read)) {
                    failed(Stage.ENTRY, Failure(Failure.CHANGED_DURING_READ, IssueCategory.TRANSIENT), read.labels)
                } else {
                    val localized = read.localized.filter { (entry, names) -> entry in observed.entries && names.isNotEmpty() }
                    val materials = Materials(MaterialKey.of(observed, env), read.labels, localized, read.signerDns)
                    PreparedPackage(key, observed, read = true, job.retryOnly, materials, null, read.labels, round)
                }
            }
        }
    }

    // 全部が成功した組だけ検索に使う値を置き換える。失敗した組は前の値を保ち、一覧から消えた入口だけを落とす（消えた入口が後で戻ったとき、
    // 前の APK の提供元などのキーを戻さないため）。値のない入口は代わりの表示名で出す: この回に取れた表示名、なければ一覧に残り続けた入口の
    // 前の代わりの表示名、それもなければパッケージ名。最後の試みには今の鍵と欠けを書く。再試行の枠は、一時的な失敗で、
    // 再試行だけがきっかけの回でなければ立てる
    private fun settle(
        pkg: PreparedPackage,
        prev: PackageState?,
        outcomes: Map<KeyJob, KeyOutcome>,
        env: Environment,
        today: String,
    ): PackageState {
        val built = pkg.jobs.mapValues { outcomes.getValue(it.value) }
        val keyFailure = built.values.firstNotNullOfOrNull { (it as? KeyOutcome.Failed)?.failure }
        val failed = pkg.failed ?: keyFailure?.let { Stage.KEYS to it }
        val materials = pkg.materials
        val entries = pkg.observed.entries
        val current =
            if (failed == null && materials != null) {
                Current(
                    materials.key,
                    DerivedKey.of(env),
                    materials.labels,
                    materials.localized,
                    materials.signerDns,
                    built.mapValues { (it.value as KeyOutcome.Built).keys },
                )
            } else {
                prev?.current?.let { c ->
                    c.copy(labels = c.labels.filterKeys { it in entries }, keysByEntry = c.keysByEntry.filterKeys { it in entries })
                }
            }
        val substitutes =
            (entries - current?.labels?.keys.orEmpty()).associateWith {
                pkg.fallbackLabels[it] ?: prev?.substitutes?.get(it) ?: pkg.key.packageName
            }
        val attempt =
            Attempt(
                materialKey = MaterialKey.of(pkg.observed, env),
                derivedKey = DerivedKey.of(env),
                bootCount = if (pkg.read) env.bootCount else checkNotNull(prev).attempt.bootCount,
                issue =
                    failed?.let { (stage, failure) ->
                        Issue(stage, failure, env.appVersion, prev?.attempt?.issue?.firstFailedOn ?: today, today)
                    },
                retry = failed?.second?.category == IssueCategory.TRANSIENT && !pkg.retryOnly,
            )
        return PackageState(pkg.key, pkg.observed, current, substitutes, attempt, prev?.genres.orEmpty())
    }

    // ジャンルは「成功した空」と「失敗」を分ける。失敗した回は既存のジャンルを保つ（新しい入口はジャンルなし）
    private fun genresOf(
        key: PackageKey,
        observed: PackageObservation,
        previous: Map<String, List<GenreId>>,
        genres: GenreResult,
    ): Map<String, List<GenreId>> =
        observed.entries
            .associateWith { entry ->
                when (genres) {
                    is GenreResult.Found -> genres.byEntry[AppId(key.packageName, entry, key.profileSerial)].orEmpty()
                    is GenreResult.Failed -> previous[entry].orEmpty()
                }
            }.filterValues { it.isNotEmpty() }

    private fun DeviceObservation.frozen(): DeviceObservation =
        DeviceObservation(
            profiles.mapValues { (_, listing) ->
                listing.copy(packages = listing.packages.mapValues { (_, o) -> o.copy(entries = o.entries.toSet()) })
            },
            removedProfiles.toSet(),
        )

    private fun Map<Long, ProfileReads>.frozen(): Map<Long, ProfileReads> =
        mapValues { (_, profile) ->
            profile.copy(
                packages =
                    profile.packages.mapValues { (_, read) ->
                        when (read) {
                            is MaterialRead.Read -> {
                                read.copy(
                                    labels = read.labels.toMap(),
                                    localized = read.localized.mapValues { it.value.toList() },
                                    signerDns = read.signerDns.toList(),
                                )
                            }

                            is MaterialRead.Failed -> {
                                read.copy(labels = read.labels.toMap())
                            }
                        }
                    },
            )
        }

    // キーの語の区切りまで写し取る（外側のリストだけを写すと、呼ぶ側の区切りのリストの変更が公開した写しに及ぶ）
    @JvmName("frozenOutcomes")
    private fun Map<KeyJob, KeyOutcome>.frozen(): Map<KeyJob, KeyOutcome> =
        mapValues { (_, outcome) ->
            when (outcome) {
                is KeyOutcome.Built -> KeyOutcome.Built(outcome.keys.map { it.copy(wordStarts = it.wordStarts.toList()) })
                is KeyOutcome.Failed -> outcome
            }
        }

    private fun GenreResult.frozen(): GenreResult =
        when (this) {
            is GenreResult.Found -> GenreResult.Found(request, byEntry.mapValues { it.value.toList() })
            is GenreResult.Failed -> this
        }
}

/** 作り直しの材料（読めた回の値か、キーだけを作る回の検索に使う値）。 */
internal class Materials(
    val key: MaterialKey,
    val labels: Map<String, String>,
    val localized: Map<String, List<String>>,
    val signerDns: List<String>,
)

/** 結果を待つ組 1 つ。[materials] は読み取りが成功したかキーだけを作る組で、[failed] は読み取りの本当の失敗。 */
@Suppress("LongParameterList")
internal class PreparedPackage(
    val key: PackageKey,
    val observed: PackageObservation,
    val read: Boolean,
    val retryOnly: Boolean,
    val materials: Materials?,
    val failed: Pair<Stage, Failure>?,
    val fallbackLabels: Map<String, String>,
    round: Any,
) {
    /** 入口 → 生成器への頼み。読み取りが失敗した組は空。 */
    val jobs: Map<String, KeyJob> =
        materials
            ?.labels
            ?.keys
            ?.sorted()
            ?.associateWith { entry ->
                KeyJob(
                    AppEntry(
                        AppId(key.packageName, entry, key.profileSerial),
                        materials.labels.getValue(entry),
                        localizedLabels = materials.localized[entry].orEmpty(),
                        signerNames = materials.signerDns,
                    ),
                    round,
                )
            }.orEmpty()
}

/**
 * 材料の結果を受け、キーの生成を待つ同期 1 回（[IndexRules.prepare] の結果）。計画・材料と生成の頼みを 1 つに束ね、
 * [IndexRules.apply] がこれと生成の結果だけを受け取るようにする（別々に渡すと、別の回のものを組み合わせても気づけないため）。
 */
public class PreparedSync internal constructor(
    internal val plan: SyncPlan,
    internal val statuses: Map<Long, ListingStatus>,
    internal val today: String,
    internal val packages: Map<PackageKey, PreparedPackage>,
) {
    /** 生成器に頼む入口。 */
    public val jobs: List<KeyJob> = packages.values.flatMap { it.jobs.values }

    /** 一覧と材料の両方が有効で、結果を当てるプロファイル。 */
    public val appliedProfiles: Set<Long> = statuses.filterValues { it == ListingStatus.COMPLETE }.keys
}

/** 生成器を通さない代わりのキー。読みを作らず、表示名の厳密形と緩い形だけにする。 */
public object SubstituteKeys {
    /** 表示名 [label] の代わりのキー。NFKC をかけてから正規化する。空なら空。 */
    public fun of(label: String): List<SearchKey> {
        val strict = TextNormalizer.strict(Normalizer.normalize(label, Normalizer.Form.NFKC))
        if (strict.isEmpty) return emptyList()
        val loose = TextNormalizer.loose(strict)
        return listOf(SearchKey(strict, SearchKeyKind.NAME)) +
            if (loose != strict) listOf(SearchKey(loose, SearchKeyKind.NAME, form = KeyForm.LOOSE)) else emptyList()
    }

    // 代わりのキーまで例外ならキー 0 個にする（入口があれば読み直しは止まる）
    internal fun orEmpty(label: String): List<SearchKey> =
        try {
            of(label)
        } catch (_: RuntimeException) {
            emptyList()
        }
}
