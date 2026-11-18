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
 * 2. [keyJobs]: 読んだ結果から、キーを作る入口とその入力を決める。→ キーを作る
 * 3. [apply]: すべての結果から次の写しを作る
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
     * キーを作る入口と、その生成器の入力を決める。[apply] に渡す結果は、ここで返したすべての入口の分がいる。
     *
     * 材料を読んだ組は、`ENTRY` が読めたならすべての入口、欠けたなら新しい入口だけを代わりのキーで作る（旧い入口は旧いキーを保つ）。
     * キーだけを作り直す組は、保存した材料からすべての入口を作る。有効でないプロファイルの組は作らない（結果を当てないため）。
     */
    public fun keyJobs(
        previous: IndexSnapshot,
        observation: DeviceObservation,
        plan: SyncPlan,
        reads: Map<Long, ProfileReads>,
        env: Environment,
    ): Map<PackageKey, List<KeyJob>> {
        val valid = validProfiles(observation, plan, reads)
        val jobs = LinkedHashMap<PackageKey, List<KeyJob>>()
        for (key in plan.reads.keys) {
            if (key.profileSerial !in valid) continue
            val observed = observed(observation, key)
            val prev = previous.packages[key]
            val values = materialValues(key, prev, observed, readOf(reads, key))
            jobs[key] =
                jobEntries(prev, observed, values.entryFailure != null).map {
                    KeyJob(appEntry(key, it, values.labels, values.localized, values.signerDns), values.entryFailure != null)
                }
        }
        for (key in plan.keys.keys) {
            if (key.profileSerial !in valid) continue
            val prev = checkNotNull(previous.packages[key])
            val materials = prev.materials
            jobs[key] =
                observed(observation, key).entries.sorted().map {
                    KeyJob(appEntry(key, it, materials.labels, materials.localized, materials.signerDns), false)
                }
        }
        return jobs
    }

    /**
     * 次の写しを作る。
     *
     * @param previous [plan] に渡した前の写し
     * @param observation [plan] に渡した観測
     * @param plan [plan] の結果
     * @param reads プロファイルごとの材料の読み取りの結果。[SyncPlan.reads] の組の分がいる
     * @param genres ジャンルの問い合わせの結果
     * @param built 組 → 入口 → キーの生成の結果。[keyJobs] の入口の分がいる
     * @param env [plan] に渡した環境
     * @param today 欠けの行の日（UTC の `YYYY-MM-DD`）
     */
    @Suppress("LongParameterList")
    public fun apply(
        previous: IndexSnapshot,
        observation: DeviceObservation,
        plan: SyncPlan,
        reads: Map<Long, ProfileReads>,
        genres: GenreResult,
        built: Map<PackageKey, Map<String, KeyOutcome>>,
        env: Environment,
        today: String,
    ): SyncOutcome {
        val valid = validProfiles(observation, plan, reads)
        val removed = observation.removedProfiles
        val next = LinkedHashMap(previous.packages)
        val changed = HashSet<PackageKey>()
        for (key in plan.removals) {
            if (key.profileSerial in removed || key.profileSerial in valid) {
                next.remove(key)
                changed += key
            }
        }
        for (serial in valid.sorted()) {
            for ((name, observed) in checkNotNull(observation.profiles[serial]).packages) {
                val key = PackageKey(serial, name)
                val prev = previous.packages[key]
                val job = plan.reads[key]
                val keyReasons = plan.keys[key]
                val state =
                    when {
                        job != null -> {
                            changed += key
                            readPackage(key, prev, observed, job, readOf(reads, key), built[key].orEmpty(), env, today)
                        }

                        keyReasons != null -> {
                            rebuildKeys(checkNotNull(prev), observed, keyReasons, built[key].orEmpty(), env, today)
                        }

                        else -> {
                            checkNotNull(prev) { "計画にない新しい組: $key" }.copy(observed = observed)
                        }
                    }
                next[key] = state.copy(genres = genresOf(key, observed, prev?.genres.orEmpty(), genres))
            }
        }
        val listings =
            (previous.listings + observation.profiles.mapValues { it.value.status })
                .filterKeys { it !in removed }
        val consumable =
            plan.noticeTargets
                .filterValues { it != null && (it.profileSerial in valid || it.profileSerial in removed) }
                .keys
        return SyncOutcome(IndexSnapshot(next, listings), valid, consumable, changed)
    }

    // 一覧が最後まで読めて、消えておらず、材料を読んだならその読み取りも最後まで読めたプロファイル
    private fun validProfiles(
        observation: DeviceObservation,
        plan: SyncPlan,
        reads: Map<Long, ProfileReads>,
    ): Set<Long> {
        val readSerials = plan.reads.keys.mapTo(HashSet()) { it.profileSerial }
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
        if (materials.issues.values.any { it.appVersion != env.appVersion }) reasons += ReadReason.VERSION
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
        if (issue != null && issue.appVersion != env.appVersion) reasons += KeyReason.VERSION
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

    /** 読み取りの結果から決めた材料の値。欠けの行は [readPackage] が決める。 */
    private class MaterialValues(
        val labels: Map<String, String>,
        val localized: Map<String, List<String>>,
        val signerDns: List<String>,
        val entryFailure: Failure?,
    )

    // 欠けたときに残す材料: ENTRY の失敗は旧い入口に旧い値、新しい入口に一覧の表示名（なければパッケージ名）。
    // LABEL・SIGNER の失敗は読めた分だけにし、古い提供元を新しい鍵で引き継がない。アーカイブ済みは補助の材料を空にする
    private fun materialValues(
        key: PackageKey,
        prev: PackageState?,
        observed: PackageObservation,
        read: MaterialRead,
    ): MaterialValues {
        val entries = observed.entries
        val entryFailure = read.entryFailure ?: changedDuringRead(observed, read)
        if (entryFailure != null) {
            val kept = keptEntries(prev, observed)
            val old = prev?.materials
            return MaterialValues(
                labels =
                    entries.associateWith {
                        if (it in
                            kept
                        ) {
                            old?.labels?.get(it) ?: key.packageName
                        } else {
                            read.labels[it] ?: key.packageName
                        }
                    },
                localized = old?.localized.orEmpty().filterKeys { it in kept },
                signerDns = old?.signerDns.orEmpty(),
                entryFailure = entryFailure,
            )
        }
        val labels = entries.associateWith { read.labels[it] ?: key.packageName }
        if (observed.archived) return MaterialValues(labels, emptyMap(), emptyList(), null)
        return MaterialValues(
            labels = labels,
            localized = entries.associateWith { read.localized[it].orEmpty() }.filterValues { it.isNotEmpty() },
            signerDns = if (read.signerFailure == null) read.signerDns else emptyList(),
            entryFailure = null,
        )
    }

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

    // ENTRY が欠けた組で、旧いキーを保つ入口（一覧にも前の写しにもある入口）
    private fun keptEntries(
        prev: PackageState?,
        observed: PackageObservation,
    ): Set<String> = observed.entries.filterTo(HashSet()) { prev?.derived?.keysByEntry?.containsKey(it) == true }

    private fun jobEntries(
        prev: PackageState?,
        observed: PackageObservation,
        entryFailed: Boolean,
    ): List<String> {
        val kept = if (entryFailed) keptEntries(prev, observed) else emptySet()
        return observed.entries.filter { it !in kept }.sorted()
    }

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

    @Suppress("LongParameterList")
    private fun readPackage(
        key: PackageKey,
        prev: PackageState?,
        observed: PackageObservation,
        job: MaterialJob,
        read: MaterialRead,
        outcomes: Map<String, KeyOutcome>,
        env: Environment,
        today: String,
    ): PackageState {
        val values = materialValues(key, prev, observed, read)
        val before = prev?.materials?.issues.orEmpty()
        val issues = LinkedHashMap<Stage, Issue>()

        fun put(
            stage: Stage,
            failure: Failure?,
        ) {
            if (failure != null) issues[stage] = issueOf(failure, before[stage], job.retryOnly, env, today)
        }
        val entryFailure = values.entryFailure
        if (entryFailure != null) {
            put(Stage.ENTRY, entryFailure)
            // どの版の APK の材料か分からないので、その回の補助の材料の結果は使わず、前の行を保つ
            before[Stage.LABEL]?.let { issues[Stage.LABEL] = kept(it, env) }
            before[Stage.SIGNER]?.let { issues[Stage.SIGNER] = kept(it, env) }
        } else if (!observed.archived) {
            // アーカイブ済みは補助の材料がないのが正常な不在なので、行を書かない（前の行も消える）
            put(Stage.LABEL, read.labelFailure)
            put(Stage.SIGNER, read.signerFailure)
        }
        val materials =
            Materials(
                key = MaterialKey.of(observed, env),
                generation = (prev?.materials?.generation ?: 0) + 1,
                bootCount = env.bootCount,
                labels = values.labels,
                localized = values.localized,
                signerDns = values.signerDns,
                issues = issues,
            )
        val derived =
            derive(
                prev?.derived,
                materials,
                jobEntries(prev, observed, entryFailure != null),
                substitute = entryFailure != null,
                retryOnly = job.retryOnly,
                outcomes = outcomes,
                env = env,
                today = today,
            )
        return PackageState(key, observed, materials, derived, prev?.genres.orEmpty())
    }

    @Suppress("LongParameterList")
    private fun rebuildKeys(
        prev: PackageState,
        observed: PackageObservation,
        reasons: Set<KeyReason>,
        outcomes: Map<String, KeyOutcome>,
        env: Environment,
        today: String,
    ): PackageState =
        prev.copy(
            observed = observed,
            derived =
                derive(
                    prev.derived,
                    prev.materials,
                    observed.entries.sorted(),
                    substitute = false,
                    retryOnly = reasons == setOf(KeyReason.RETRY),
                    outcomes = outcomes,
                    env = env,
                    today = today,
                ),
        )

    // 作れなかった入口は前のキー、なければ表示名を正規化しただけのキー、それも無理ならキー 0 個。一覧の入口は必ず残す
    @Suppress("LongParameterList")
    private fun derive(
        prev: Derived?,
        materials: Materials,
        jobs: List<String>,
        substitute: Boolean,
        retryOnly: Boolean,
        outcomes: Map<String, KeyOutcome>,
        env: Environment,
        today: String,
    ): Derived {
        val prevKeys = prev?.keysByEntry.orEmpty()
        var failure: Failure? = null
        val keysByEntry = LinkedHashMap<String, List<SearchKey>>()
        for (entry in materials.key.entries.sorted()) {
            keysByEntry[entry] =
                if (entry !in jobs) {
                    prevKeys.getValue(entry)
                } else {
                    when (val outcome = requireNotNull(outcomes[entry]) { "キーの生成の結果がない入口: $entry" }) {
                        is KeyOutcome.Built -> {
                            outcome.keys
                        }

                        is KeyOutcome.Failed -> {
                            failure = failure ?: outcome.failure
                            prevKeys[entry] ?: SubstituteKeys.orEmpty(materials.labels[entry].orEmpty())
                        }
                    }
                }
        }
        val issue =
            failure?.let { issueOf(it, prev?.issue, retryOnly, env, today) }
                // 代わりのキーだけを作った回は、生成器を通していないので前の行を保つ
                ?: prev?.issue?.takeIf { substitute }?.let { kept(it, env) }
        return Derived(DerivedKey.of(materials.generation, env), keysByEntry, issue)
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
            appVersion = env.appVersion,
            retryOnce = failure.category == IssueCategory.TRANSIENT && !retryOnly,
            firstFailedOn = previous?.firstFailedOn ?: today,
            lastFailedOn = today,
        )

    // その回に確かめられなかった段の前の行。印は下ろし、版は今の版にする。版を古いまま残すと、版の違いが毎回の読み直しの
    // きっかけになり、同期が収束しない
    private fun kept(
        issue: Issue,
        env: Environment,
    ): Issue = issue.copy(retryOnce = false, appVersion = env.appVersion)
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
