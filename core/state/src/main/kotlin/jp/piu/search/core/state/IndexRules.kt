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
 * 1. [plan]: 前の写し・観測・環境・まだ当てていない知らせから、材料を読む組・キーを作る組・消す組を決める。→ 材料とジャンルを読む
 * 2. [prepare]: 読んだ結果を材料に当て、生成器に頼む入口（[PreparedSync.jobs]）を決める。→ キーを作る
 * 3. [apply]: [prepare] の結果と生成の結果から次の写しを作る
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
        val noticeTargets =
            notices.associate { notice ->
                notice.seq to (notice.profileSerial ?: userSerials[notice.userHandle])?.let { PackageKey(it, notice.packageName) }
            }
        val noticed = noticeTargets.values.filterNotNullTo(HashSet())
        val reads = LinkedHashMap<PackageKey, MaterialJob>()
        val keys = LinkedHashMap<PackageKey, Set<KeyReason>>()
        val genreTargets = ArrayList<AppId>()
        val removals = previous.packages.keys.filterTo(HashSet()) { it.profileSerial in observation.removedProfiles }
        for ((serial, listing) in observation.profiles.toSortedMap()) {
            if (serial in observation.removedProfiles || listing.status != ListingStatus.COMPLETE) continue
            for ((name, observed) in listing.packages.toSortedMap()) {
                val key = PackageKey(serial, name)
                observed.entries.sorted().mapTo(genreTargets) { AppId(name, it, serial) }
                val prev = previous.packages[key]
                val readReasons = readReasons(prev, observed, env, key in noticed)
                if (readReasons.isNotEmpty()) {
                    reads[key] = MaterialJob(readReasons, auxiliary = !observed.archived)
                } else {
                    keyReasons(checkNotNull(prev), env).takeIf { it.isNotEmpty() }?.let { keys[key] = it }
                }
            }
            previous.packages.keys.filterTo(removals) { it.profileSerial == serial && it.packageName !in listing.packages }
        }
        return SyncPlan(reads, keys, removals, genreTargets, noticeTargets)
    }

    /**
     * 材料の読み取りの結果を当て、キーの生成を待つ形にする。生成器に頼む入口は [PreparedSync.jobs]。
     *
     * 材料を読んだ組は、`ENTRY` が読めたならすべての入口を生成器に頼む。欠けたなら、旧い入口は旧いキーを保ち、
     * 新しい入口は表示名の代わりのキーにする（どの版の APK の材料か分からないため）。代わりのキーはここで作り、生成器には頼まない。
     * キーだけを作り直す組は、保存した材料から作る。有効でないプロファイルの組は扱わない（結果を当てないため）。
     *
     * @param previous [plan] に渡した前の写し
     * @param observation [plan] に渡した観測
     * @param plan [plan] の結果
     * @param reads プロファイルごとの材料の読み取りの結果。材料を頼んだプロファイルの分がいる
     * @param env [plan] に渡した環境
     * @param today 欠けの行の日（UTC の `YYYY-MM-DD`）
     */
    public fun prepare(
        previous: IndexSnapshot,
        observation: DeviceObservation,
        plan: SyncPlan,
        reads: Map<Long, ProfileReads>,
        env: Environment,
        today: String,
    ): PreparedSync {
        // 写しは作った後に変えないので、呼ぶ側の集まりを入れ子まで写し取る。呼ぶ側が後で中身を変えても、公開した写しと次の計画は変わらない
        val frozenObservation = observation.frozen()
        val frozenReads = reads.frozen()
        val valid = validProfiles(frozenObservation, plan, frozenReads)
        val packages = LinkedHashMap<PackageKey, PreparedPackage>()
        for ((key, job) in plan.reads) {
            if (key.profileSerial !in valid) continue
            packages[key] =
                readPackage(key, previous.packages[key], observed(frozenObservation, key), job, readOf(frozenReads, key), env, today)
        }
        for ((key, reasons) in plan.keys) {
            if (key.profileSerial !in valid) continue
            val prev = checkNotNull(previous.packages[key])
            val observed = observed(frozenObservation, key)
            val materials = prev.materials
            val entries =
                observed.entries.sorted().associateWith { entry ->
                    if (entry in materials.substituted) {
                        EntryPlan.Substitute(materials.labels[entry] ?: key.packageName)
                    } else {
                        EntryPlan.Generate(KeyJob(appEntry(key, entry, materials.labels, materials.localized, materials.signerDns)))
                    }
                }
            packages[key] =
                PreparedPackage(key, prev, observed, materials, entries, retryOnly = reasons == setOf(KeyReason.RETRY), read = false)
        }
        return PreparedSync(previous, frozenObservation, plan, frozenReads, env, today, valid, packages)
    }

    /**
     * 次の写しを作る。
     *
     * @param prepared [prepare] の結果
     * @param genres ジャンルの問い合わせの結果
     * @param built 生成器の結果。[PreparedSync.jobs] のすべての頼みの分がちょうどいる。
     *   頼みは入力（表示名・別の言語の名前・署名）ごと鍵にするので、別の回の結果を取り違えて渡すと止まる
     */
    public fun apply(
        prepared: PreparedSync,
        genres: GenreResult,
        built: Map<KeyJob, KeyOutcome>,
    ): SyncOutcome {
        require(
            built.keys == prepared.jobs.toSet(),
        ) { "生成の結果が頼みと合わない: ${built.keys - prepared.jobs.toSet()} ${prepared.jobs - built.keys}" }
        val previous = prepared.previous
        val observation = prepared.observation
        val plan = prepared.plan
        val env = prepared.env
        val valid = prepared.valid
        val genreResult = genres.frozen()
        val outcomes =
            built.mapValues { (_, outcome) ->
                if (outcome is KeyOutcome.Built) KeyOutcome.Built(outcome.keys.toList()) else outcome
            }
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
                val state =
                    if (pkg != null) {
                        if (pkg.read) staleIcons += key
                        PackageState(key, observed, pkg.materials, derive(pkg, outcomes, env, prepared.today), prev?.genres.orEmpty())
                    } else {
                        checkNotNull(prev) { "計画にない新しい組: $key" }.copy(observed = observed)
                    }
                next[key] = state.copy(genres = genresOf(key, observed, prev?.genres.orEmpty(), genreResult))
            }
        }
        // 一覧が読めても材料を読み終えた後に読めなくなったプロファイルは、その読み取りの状態を残す（表示と次の判断に使う）
        val listings =
            (previous.listings + observation.profiles.mapValues { (serial, listing) -> prepared.reads[serial]?.status ?: listing.status })
                .filterKeys { it !in removed }
        val consumable =
            plan.noticeTargets
                .filterValues { it != null && (it.profileSerial in valid || it.profileSerial in removed) }
                .keys
        return SyncOutcome(IndexSnapshot(next, listings), valid, consumable, staleIcons)
    }

    // 一覧が最後まで読めて、消えておらず、材料を読んだならその読み取りも最後まで読めたプロファイル。
    // 材料を頼んだプロファイルの結果がなければ呼ぶ側の誤りとして止める。黙って外すと、正常な部分の同期と見分けられない
    private fun validProfiles(
        observation: DeviceObservation,
        plan: SyncPlan,
        reads: Map<Long, ProfileReads>,
    ): Set<Long> {
        val readSerials = plan.reads.keys.mapTo(HashSet()) { it.profileSerial }
        for (serial in readSerials) require(serial in reads) { "材料を頼んだプロファイルの結果がない: $serial" }
        return observation.profiles
            .filter { (serial, listing) ->
                listing.status == ListingStatus.COMPLETE &&
                    serial !in observation.removedProfiles &&
                    (serial !in readSerials || reads[serial]?.complete == true)
            }.keys
    }

    private fun readReasons(
        prev: PackageState?,
        observed: PackageObservation,
        env: Environment,
        noticed: Boolean,
    ): Set<ReadReason> {
        if (prev == null) return setOf(ReadReason.MISSING)
        val reasons = HashSet<ReadReason>()
        val materials = prev.materials
        if (materials.key != MaterialKey.of(observed, env)) reasons += ReadReason.KEY_CHANGED
        if (noticed) reasons += ReadReason.NOTICE
        if (materials.issues.values.any { it.retryOnce }) reasons += ReadReason.RETRY
        if (materials.issues.values.any { it.checkedVersion != env.appVersion }) reasons += ReadReason.VERSION
        // 回数が取れない端末では読み直さない。プロセスがない間の更新の取りこぼしは知らせに頼る
        if (observed.keyless && env.bootCount != null && materials.bootCount != env.bootCount) reasons += ReadReason.BOOT
        return reasons
    }

    private fun keyReasons(
        prev: PackageState,
        env: Environment,
    ): Set<KeyReason> {
        val reasons = HashSet<KeyReason>()
        if (prev.derived.key != DerivedKey.of(prev.materials.generation, env)) reasons += KeyReason.KEY_CHANGED
        val issue = prev.derived.issue
        if (issue?.retryOnce == true) reasons += KeyReason.RETRY
        if (issue != null && issue.checkedVersion != env.appVersion) reasons += KeyReason.VERSION
        return reasons
    }

    private fun observed(
        observation: DeviceObservation,
        key: PackageKey,
    ): PackageObservation = checkNotNull(observation.profiles[key.profileSerial]?.packages?.get(key.packageName)) { "一覧にない組: $key" }

    private fun readOf(
        reads: Map<Long, ProfileReads>,
        key: PackageKey,
    ): MaterialRead = requireNotNull(reads[key.profileSerial]?.packages?.get(key.packageName)) { "読み取りの結果がない組: $key" }

    // 一覧の時点で鍵がなければ前後を比べない（片方がない比較では替わったかを決められない）
    private fun changedDuringRead(
        observed: PackageObservation,
        read: MaterialRead,
    ): Failure? {
        val apkMoved = observed.apkKey != null && (read.before.apkKey != observed.apkKey || read.after.apkKey != observed.apkKey)
        val timeMoved =
            observed.updatedAt != null && (read.before.updatedAt != observed.updatedAt || read.after.updatedAt != observed.updatedAt)
        return if (apkMoved || timeMoved) Failure(Failure.CHANGED_DURING_READ, IssueCategory.TRANSIENT) else null
    }

    // 材料を読んだ組の、次の材料と入口ごとのキーの作り方。
    // 欠けたときに残す材料: ENTRY の失敗は旧い入口に旧い値、新しい入口に一覧の表示名（なければパッケージ名）。
    // LABEL・SIGNER の失敗は読めた分だけにし、古い提供元を新しい鍵で引き継がない。
    // アーカイブ済みは補助の材料を空にし、補助の段の行も消す。ENTRY の失敗と重なっても、アーカイブの規則を先にする
    // （APK がないので補助の材料は正常な不在。旧い補助の材料と、それから作ったキーを残さない）
    @Suppress("LongParameterList")
    private fun readPackage(
        key: PackageKey,
        prev: PackageState?,
        observed: PackageObservation,
        job: MaterialJob,
        read: MaterialRead,
        env: Environment,
        today: String,
    ): PreparedPackage {
        val entries = observed.entries
        val archived = observed.archived
        val entryFailure = read.entryFailure ?: changedDuringRead(observed, read)
        val old = prev?.materials
        val before = old?.issues.orEmpty()
        val issues = LinkedHashMap<Stage, Issue>()

        fun put(
            stage: Stage,
            failure: Failure?,
        ) {
            if (failure != null) issues[stage] = issueOf(failure, before[stage], job.retryOnly, env, today)
        }
        val plans = LinkedHashMap<String, EntryPlan>()
        val materials: Materials
        if (entryFailure != null) {
            // 旧い入口: 一覧にも前の写しにもある入口
            val kept = entries.filterTo(HashSet()) { prev?.derived?.keysByEntry?.containsKey(it) == true }
            val labels =
                entries.associateWith {
                    if (it in
                        kept
                    ) {
                        old?.labels?.get(it) ?: key.packageName
                    } else {
                        read.labels[it] ?: key.packageName
                    }
                }
            val substituted = entries.filterTo(HashSet()) { it !in kept || it in old?.substituted.orEmpty() }
            put(Stage.ENTRY, entryFailure)
            if (!archived) {
                // どの版の APK の材料か分からないので、その回の補助の材料の結果は使わず、前の行を保つ
                before[Stage.LABEL]?.let { issues[Stage.LABEL] = kept(it, job.retryOnly, env) }
                before[Stage.SIGNER]?.let { issues[Stage.SIGNER] = kept(it, job.retryOnly, env) }
            }
            val localized = if (archived) emptyMap() else old?.localized.orEmpty().filterKeys { it in kept }
            val signerDns = if (archived) emptyList() else old?.signerDns.orEmpty()
            materials = materialsOf(observed, env, old, labels, localized, signerDns, issues, substituted)
            for (entry in entries.sorted()) {
                plans[entry] =
                    when {
                        entry in substituted -> EntryPlan.Substitute(labels.getValue(entry))

                        // 旧いキーは補助の材料を含みうるので、旧い表示名だけから作り直す
                        archived -> EntryPlan.Generate(KeyJob(appEntry(key, entry, labels, emptyMap(), emptyList())))

                        else -> EntryPlan.Keep
                    }
            }
        } else {
            val labels = entries.associateWith { read.labels[it] ?: key.packageName }
            val localized =
                if (archived) emptyMap() else entries.associateWith { read.localized[it].orEmpty() }.filterValues { it.isNotEmpty() }
            val signerDns = if (archived || read.signerFailure != null) emptyList() else read.signerDns
            // アーカイブ済みは補助の材料がないのが正常な不在なので、行を書かない（前の行も消える）
            if (!archived) {
                put(Stage.LABEL, read.labelFailure)
                put(Stage.SIGNER, read.signerFailure)
            }
            materials = materialsOf(observed, env, old, labels, localized, signerDns, issues, emptySet())
            for (entry in entries.sorted()) {
                plans[entry] = EntryPlan.Generate(KeyJob(appEntry(key, entry, labels, localized, signerDns)))
            }
        }
        return PreparedPackage(key, prev, observed, materials, plans, job.retryOnly, read = true)
    }

    @Suppress("LongParameterList")
    private fun materialsOf(
        observed: PackageObservation,
        env: Environment,
        old: Materials?,
        labels: Map<String, String>,
        localized: Map<String, List<String>>,
        signerDns: List<String>,
        issues: Map<Stage, Issue>,
        substituted: Set<String>,
    ): Materials =
        Materials(
            key = MaterialKey.of(observed, env),
            generation = (old?.generation ?: 0) + 1,
            bootCount = env.bootCount,
            labels = labels,
            localized = localized,
            signerDns = signerDns,
            issues = issues,
            substituted = substituted,
        )

    private fun appEntry(
        key: PackageKey,
        entry: String,
        labels: Map<String, String>,
        localized: Map<String, List<String>>,
        signerDns: List<String>,
    ): AppEntry =
        AppEntry(
            AppId(key.packageName, entry, key.profileSerial),
            labels[entry] ?: key.packageName,
            localizedLabels = localized[entry].orEmpty(),
            signerNames = signerDns,
        )

    // 一覧の入口は必ず残す。作れなかった入口は、前のキーが今回と同じ入力から作ったものならそれを、そうでなければ表示名の代わりのキーにする。
    // 入力の違う前のキーを使うと、読めなかった段の古い材料（旧い提供元など）で当たり続けるため。それも無理ならキー 0 個
    private fun derive(
        pkg: PreparedPackage,
        outcomes: Map<KeyJob, KeyOutcome>,
        env: Environment,
        today: String,
    ): Derived {
        val prev = pkg.prev?.derived
        val prevKeys = prev?.keysByEntry.orEmpty()
        val prevSources = prev?.sources.orEmpty()
        var failure: Failure? = null
        val keysByEntry = LinkedHashMap<String, List<SearchKey>>()
        val sources = LinkedHashMap<String, AppEntry>()
        for ((entry, plan) in pkg.entries) {
            when (plan) {
                EntryPlan.Keep -> {
                    keysByEntry[entry] = prevKeys.getValue(entry)
                    prevSources[entry]?.let { sources[entry] = it }
                }

                is EntryPlan.Substitute -> {
                    keysByEntry[entry] =
                        try {
                            SubstituteKeys.of(plan.label)
                        } catch (e: RuntimeException) {
                            failure = failure ?: Failure(e.javaClass.name, IssueCategory.SUSPECTED_BUG)
                            emptyList()
                        }
                }

                is EntryPlan.Generate -> {
                    val input = plan.job.entry
                    when (val outcome = outcomes.getValue(plan.job)) {
                        is KeyOutcome.Built -> {
                            keysByEntry[entry] = outcome.keys
                            sources[entry] = input
                        }

                        is KeyOutcome.Failed -> {
                            failure = failure ?: outcome.failure
                            if (prevSources[entry] == input && entry in prevKeys) {
                                keysByEntry[entry] = prevKeys.getValue(entry)
                                sources[entry] = input
                            } else {
                                keysByEntry[entry] = SubstituteKeys.orEmpty(input.label)
                            }
                        }
                    }
                }
            }
        }
        val generated = pkg.entries.values.any { it is EntryPlan.Generate }
        val issue =
            failure?.let { issueOf(it, prev?.issue, pkg.retryOnly, env, today) }
                // 生成器を通さなかった回は、前の行を保つ
                ?: prev?.issue?.takeIf { !generated }?.let { kept(it, pkg.retryOnly, env) }
        return Derived(DerivedKey.of(pkg.materials.generation, env), keysByEntry, issue, sources)
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

    /**
     * 欠けの行を書く。区分が一時的で、再試行だけがきっかけでなければ `retryOnce` を立てる。
     * 続けて欠けている間は初めの日を保つ。
     */
    private fun issueOf(
        failure: Failure,
        previous: Issue?,
        retryOnly: Boolean,
        env: Environment,
        today: String,
    ): Issue =
        Issue(
            failure = failure,
            failedVersion = env.appVersion,
            checkedVersion = env.appVersion,
            retryOnce = failure.category == IssueCategory.TRANSIENT && !retryOnly,
            firstFailedOn = previous?.firstFailedOn ?: today,
            lastFailedOn = today,
        )

    // その回に確かめられなかった段の前の行。確かめた版だけを今の版にする（古いまま残すと、版の違いが毎回の読み直しのきっかけになり、
    // 同期が収束しない）。例外が起きた版は変えない。印は、再試行だけがきっかけの回にだけ消費する。鍵の変化と重なった回に下ろすと、
    // その段を確かめないまま再試行の機会を失う
    private fun kept(
        issue: Issue,
        retryOnly: Boolean,
        env: Environment,
    ): Issue = issue.copy(retryOnce = issue.retryOnce && !retryOnly, checkedVersion = env.appVersion)

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
                        read.copy(
                            labels = read.labels.toMap(),
                            localized = read.localized.mapValues { it.value.toList() },
                            signerDns = read.signerDns.toList(),
                        )
                    },
            )
        }

    private fun GenreResult.frozen(): GenreResult =
        when (this) {
            is GenreResult.Found -> GenreResult.Found(byEntry.mapValues { it.value.toList() })
            is GenreResult.Failed -> this
        }
}

/** 入口 1 つのキーの作り方。 */
internal sealed interface EntryPlan {
    /** 前のキーを保つ（`ENTRY` が欠けた回の旧い入口）。 */
    data object Keep : EntryPlan

    /** 表示名の代わりのキーにする。 */
    data class Substitute(
        val label: String,
    ) : EntryPlan

    /** 生成器に頼む。 */
    data class Generate(
        val job: KeyJob,
    ) : EntryPlan
}

/** 材料を当て終えた組 1 つ。 */
internal class PreparedPackage(
    val key: PackageKey,
    val prev: PackageState?,
    val observed: PackageObservation,
    val materials: Materials,
    val entries: Map<String, EntryPlan>,
    val retryOnly: Boolean,
    val read: Boolean,
)

/**
 * 材料を当て終え、キーの生成を待つ同期 1 回（[IndexRules.prepare] の結果）。観測・材料・環境と生成の頼みを 1 つに束ね、
 * [IndexRules.apply] がこれと生成の結果だけを受け取るようにする（別々に渡すと、別の回のものを組み合わせても気づけないため）。
 */
public class PreparedSync internal constructor(
    internal val previous: IndexSnapshot,
    internal val observation: DeviceObservation,
    internal val plan: SyncPlan,
    internal val reads: Map<Long, ProfileReads>,
    internal val env: Environment,
    internal val today: String,
    internal val valid: Set<Long>,
    internal val packages: Map<PackageKey, PreparedPackage>,
) {
    /** 生成器に頼む入口。代わりのキーの入口は入らない（ここで作るため）。 */
    public val jobs: List<KeyJob> =
        packages.values.flatMap { pkg ->
            pkg.entries.values
                .filterIsInstance<EntryPlan.Generate>()
                .map { it.job }
        }

    /** 一覧と材料の両方が有効なプロファイル。 */
    public val appliedProfiles: Set<Long> get() = valid
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
