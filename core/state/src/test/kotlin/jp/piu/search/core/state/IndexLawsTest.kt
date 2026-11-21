// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.SearchKeyKind
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 同期の法則 1〜5 を、乱数で作った端末の変化と失敗の列で確かめる。
 *
 * 種ごとに、プロファイル 3 つ（個人用 0・仕事用 10・11）、組 6 つ・入口 3 つの範囲で端末を作り、[STEPS] 回、端末・環境・失敗・知らせを
 * 変えながら同期する。端末と環境の変化には、鍵の成分（APK・時刻（0 を含む）・入口・アーカイブ・言語・OS・読み取りの版・生成器の版・
 * ブランド表）を 1 つだけ変えるものを含める。
 *
 * 期待値は本体の出力から作らない。偽の端末と注入した失敗だけから次の状態を決める模型（[Model]）を並べて動かし、
 * 作り直す組・キーだけを作る組・当てるプロファイル・組ごとの状態（検索に使う値・最後の試み・ジャンル）を突き合わせる。
 * 模型は規則を素直に書き直したもので、本体の関数を呼ばない（鍵も構成子から直に作る）。
 */
class IndexLawsTest {
    @Test
    fun `法則 1〜5 - 乱数の端末の変化と失敗の列`() {
        forAllSeeds("索引の法則", SEEDS) { random, log ->
            var device = randomDevice(random)
            var env = ENV
            var snapshot = IndexSnapshot.EMPTY
            val model = Model()
            var seq = 0L
            // 消せなかった知らせは次の同期に持ち越す（書き手の規則）
            var pending = emptyList<Notice>()
            repeat(STEPS) { step ->
                device = mutate(random, device)
                env = mutateEnv(random, env)
                val faults = randomFaults(random, device)
                val notices = pending + List(random.nextInt(3)) { randomNotice(random, ++seq) }
                val today = day(step)
                log += "step $step: device=$device env=$env faults=$faults notices=$notices"
                val round = sync(snapshot, device, env, faults, notices, USER_SERIALS, today)
                val expected = model.step(device, env, faults, notices, today)
                checkRound(round, expected, device, env)
                pending = notices.filter { it.seq !in expected.consumable }
                snapshot = round.next
                if (random.nextInt(3) == 0) {
                    log += "step $step: 失敗のない同期で回復を確かめる"
                    snapshot = checkRecovery(snapshot, model, device, env, today)
                }
            }
            checkConvergence(random, snapshot, model, device, env, log)
        }
    }

    private fun checkRound(
        round: Round,
        expected: Model.Step,
        device: FakeDevice,
        env: Environment,
    ) {
        val plan = round.plan
        law(plan.reads.keys == expected.reads) { "作り直す組 ${plan.reads.keys} が模型 ${expected.reads} と違う" }
        law(plan.keys == expected.keys) { "キーだけを作る組 ${plan.keys} が模型 ${expected.keys} と違う" }
        for ((key, retryOnly) in expected.retryOnly) {
            law(plan.reads.getValue(key).retryOnly == retryOnly) { "$key の再試行だけのきっかけ ${plan.reads.getValue(key).reasons} が模型と違う" }
        }
        val applied = expected.applied
        law(round.outcome.appliedProfiles == applied) { "当てたプロファイル ${round.outcome.appliedProfiles} が $applied と違う" }
        law(
            round.outcome.consumableNotices == expected.consumable,
        ) { "消してよい知らせ ${round.outcome.consumableNotices} が模型 ${expected.consumable} と違う" }
        // 組ごとの状態の全体（検索に使う値・最後の試み・ジャンル・観測）を模型と比べる
        val indexed = round.next.packages.filterKeys { it.profileSerial in applied }
        law(indexed == expected.packages) {
            val diff = (indexed.keys + expected.packages.keys).filter { indexed[it] != expected.packages[it] }
            "当てたプロファイルの組が模型と違う: " + diff.joinToString("\n") { "$it\n  本体 ${indexed[it]}\n  模型 ${expected.packages[it]}" }
        }
        // 法則 2: 当てたプロファイルでは、検索に出る入口の集合が一覧と等しい
        for (serial in applied) {
            val listed =
                device.profiles
                    .getValue(serial)
                    .packages
                    .mapValues { it.value.labels.keys }
            val shown =
                indexed.values
                    .filter { it.key.profileSerial == serial }
                    .associate { it.key.packageName to it.apps().mapTo(HashSet()) { app -> app.id.activityName } }
            law(listed == shown) { "法則 2: プロファイル $serial の一覧 $listed と索引 $shown が違う" }
        }
        // 法則 2 の続き: 入口ごとの表示名とキーが、模型の検索に使う値（なければ代わりの表示名）から作ったものと等しい。
        // 代わりの表示名の入口は表示名のキーだけで、提供元などのキーを持たない
        for ((key, state) in expected.packages) {
            for (app in indexed.getValue(key).apps()) {
                val entry = app.id.activityName
                val label = state.current?.labels?.get(entry) ?: state.substitutes.getValue(entry)
                val keys = state.current?.keysByEntry?.get(entry) ?: SubstituteKeys.of(label)
                law(app.label == label) { "法則 2: $key/$entry の表示名 ${app.label} が $label でない" }
                law(app.keys.filter { it.kind != SearchKeyKind.GENRE } == keys) { "法則 2: $key/$entry のキーが違う" }
                if (state.current?.keysByEntry?.containsKey(entry) != true) {
                    law(
                        app.keys.all { it.kind == SearchKeyKind.NAME || it.kind == SearchKeyKind.GENRE },
                    ) { "法則 2: 代わりの入口 $key/$entry に名前のほかのキー" }
                }
            }
        }
        // 法則 3: 有効でない（消えてもいない）プロファイルの組は、前後で同じ。新しく入った組がないことも見るため、プロファイルごとの組の全体を比べる
        val removed = round.observation.removedProfiles
        val serials = (round.previous.packages.keys + round.next.packages.keys).mapTo(HashSet()) { it.profileSerial }
        for (serial in serials.filter { it !in applied && it !in removed }) {
            law(
                round.previous.packages.filterKeys { it.profileSerial == serial } ==
                    round.next.packages.filterKeys { it.profileSerial == serial },
            ) { "法則 3: 読めないプロファイル $serial の組が変わった" }
        }
        law(
            round.next.packages.keys
                .none { it.profileSerial in removed },
        ) { "消えたプロファイルの組が残った" }
        // 法則 4: 当てたプロファイルのすべての組で、最後に試した鍵が端末と環境から直に作った今の値と等しい（失敗した組も）
        for ((key, state) in indexed) {
            law(state.attempt.materialKey == materialKey(device.pkg(key), env)) { "法則 4: $key の材料の鍵 ${state.attempt.materialKey} が今の値でない" }
            law(state.attempt.derivedKey == derivedKey(env)) { "法則 4: $key のキーの鍵が今の値でない" }
        }
        // 法則 5: 作り直して失敗を注入しなかった組は、検索に使う値が端末の材料から作ったものと等しい
        for (key in expected.clean) checkFromDevice(round.next.packages.getValue(key), device.pkg(key), env, "法則 5")
    }

    // 回復: 前の同期で一時的な失敗を注入した組（模型が注入した失敗から決める）は、失敗のない次の同期で作り直され、端末の材料と一致する
    private fun checkRecovery(
        snapshot: IndexSnapshot,
        model: Model,
        device: FakeDevice,
        env: Environment,
        today: String,
    ): IndexSnapshot {
        val transient = model.transientlyFailed()
        val round = sync(snapshot, device, env, today = today)
        val expected = model.step(device, env, Faults.NONE, emptyList(), today)
        checkRound(round, expected, device, env)
        for (key in transient.filter { it.profileSerial in round.outcome.appliedProfiles && it in round.next.packages }) {
            law(key in round.plan.reads) { "回復: 一時的に失敗した $key を作り直さない" }
            checkFromDevice(round.next.packages.getValue(key), device.pkg(key), env, "回復")
        }
        return round.next
    }

    // 法則 1: 端末・環境・失敗を固定すると、再試行の枠を使い切った後は読まない。枠も知らせもなければ、次の同期は読まない
    private fun checkConvergence(
        random: Random,
        start: IndexSnapshot,
        model: Model,
        frozen: FakeDevice,
        env: Environment,
        log: MutableList<String>,
    ) {
        val device = frozen.copy(profiles = frozen.profiles.mapValues { it.value.copy(readsComplete = true) })
        val faults = randomFaults(random, device)
        log += "固定: device=$device faults=$faults"
        var snapshot = start
        repeat(CONVERGENCE_ROUNDS) { round ->
            val today = day(STEPS + round)
            val result = sync(snapshot, device, env, faults, today = today)
            checkRound(result, model.step(device, env, faults, emptyList(), today), device, env)
            val noMarks =
                result.next.packages.values
                    .none { it.attempt.retry }
            val again = IndexRules.plan(result.next, device.observe(), env)
            law(!noMarks || again.isIdle) { "法則 1: 枠がないのに読む: ${again.reads.keys} ${again.keys}" }
            if (round >= 2) law(result.plan.isIdle) { "法則 1: ${round + 1} 回目の同期でも読む: ${result.plan}" }
            snapshot = result.next
        }
    }

    // 端末の材料から直に作った値と比べる（本体の計算を通さない）
    private fun checkFromDevice(
        state: PackageState,
        pkg: FakePackage,
        env: Environment,
        name: String,
    ) {
        val key = state.key
        law(state.current == currentFromDevice(key, pkg, env)) { "$name: $key の検索に使う値 ${state.current} が端末の材料から作ったものと違う" }
        law(state.attempt.issue == null && !state.attempt.retry) { "$name: $key に欠けが残った" }
    }

    /**
     * 規則を素直に書き直した模型。偽の端末と注入した失敗だけから、作り直す組・当てるプロファイル・次の組の状態を決める。
     * 鍵は構成子から直に作り、本体の関数（`MaterialKey.of` など）を呼ばない。
     */
    private class Model {
        private var packages: Map<PackageKey, PackageState> = emptyMap()
        private var lastTransient: Set<PackageKey> = emptySet()

        /**
         * 同期 1 回で期待するもの。
         *
         * @property reads 作り直す組
         * @property keys キーだけを作る組
         * @property retryOnly 作り直す組 → きっかけが再試行だけか
         * @property applied 当てるプロファイル
         * @property packages 当てるプロファイルの組の、次の状態
         * @property clean 作り直し、失敗を注入しなかった組
         * @property consumable 消してよい知らせ（解決でき、当てたか消えたプロファイルの組の知らせ）
         */
        class Step(
            val reads: Set<PackageKey>,
            val keys: Set<PackageKey>,
            val retryOnly: Map<PackageKey, Boolean>,
            val applied: Set<Long>,
            val packages: Map<PackageKey, PackageState>,
            val clean: Set<PackageKey>,
            val consumable: Set<Long>,
        )

        fun transientlyFailed(): Set<PackageKey> = lastTransient

        fun step(
            device: FakeDevice,
            env: Environment,
            faults: Faults,
            notices: List<Notice>,
            today: String,
        ): Step {
            val noticed =
                notices.mapNotNullTo(HashSet()) { n ->
                    (n.profileSerial ?: USER_SERIALS[n.userHandle])?.let {
                        PackageKey(it, n.packageName)
                    }
                }
            val derived = derivedKey(env)
            val reads = HashMap<PackageKey, Boolean>()
            val keys = HashSet<PackageKey>()
            val listed = device.profiles.filter { (serial, p) -> p.status == ListingStatus.COMPLETE && serial !in device.removed }
            for ((serial, profile) in listed) {
                for ((name, pkg) in profile.packages) {
                    val key = PackageKey(serial, name)
                    val prev = packages[key]?.attempt
                    val boot = pkg.apkKey == null && pkg.updatedAt == null && env.bootCount != null && prev?.bootCount != env.bootCount
                    val other =
                        prev == null || prev.materialKey != materialKey(pkg, env) || key in noticed || boot ||
                            (prev.issue != null && (prev.issue.version != env.appVersion || prev.derivedKey != derived))
                    when {
                        other || prev.retry -> reads[key] = !other
                        prev.derivedKey != derived -> keys += key
                    }
                }
            }
            val applied = listed.filter { (serial, p) -> p.readsComplete || reads.keys.none { it.profileSerial == serial } }.keys
            val next = packages.filterKeys { it.profileSerial !in device.removed && it.profileSerial !in applied }.toMutableMap()
            val clean = HashSet<PackageKey>()
            val transient = HashSet<PackageKey>()
            for (serial in applied) {
                for ((name, pkg) in device.profiles.getValue(serial).packages) {
                    val key = PackageKey(serial, name)
                    val prev = packages[key]
                    val failure =
                        when (key) {
                            in reads -> rebuildFailure(key, pkg, faults)
                            in keys -> keyFailure(key, checkNotNull(prev?.current).labels.keys, faults)
                            else -> null
                        }
                    if (key in reads && failure == null) clean += key
                    // 再試行の枠が立つ組（一時的な失敗で、再試行だけがきっかけの回でない）
                    if (failure?.second?.category == IssueCategory.TRANSIENT && reads[key] != true) transient += key
                    val state =
                        when (key) {
                            in reads -> rebuilt(key, pkg, prev, failure, reads.getValue(key), key in faults.entry, env, today)
                            in keys -> rekeyed(key, pkg, checkNotNull(prev), failure, env, today)
                            else -> checkNotNull(prev).copy(observed = pkg.observation)
                        }
                    next[key] = state.copy(genres = genres(pkg, prev, faults))
                }
            }
            packages = next
            lastTransient = transient
            val consumable =
                notices
                    .filter { n -> (n.profileSerial ?: USER_SERIALS[n.userHandle])?.let { it in applied || it in device.removed } == true }
                    .mapTo(HashSet()) { it.seq }
            return Step(reads.keys, keys, reads, applied, next.filterKeys { it.profileSerial in applied }, clean, consumable)
        }

        // 段の順に最初の失敗。補助の段はアーカイブ済みでは読まない。読む前後の鍵の動きは、一覧の時点の値がある成分だけが効く
        private fun rebuildFailure(
            key: PackageKey,
            pkg: FakePackage,
            faults: Faults,
        ): Pair<Stage, Failure>? {
            val aux = !pkg.archived
            val moved =
                (key in faults.moved && (pkg.apkKey != null || pkg.updatedAt != null)) ||
                    (key in faults.movedBefore && pkg.apkKey != null) ||
                    (key in faults.movedTime && pkg.updatedAt != null)
            // 読んでいる間に替わったことを、段の失敗より先に扱う
            return (Stage.ENTRY to Failure(Failure.CHANGED_DURING_READ, IssueCategory.TRANSIENT)).takeIf { moved }
                ?: faults.entry[key]?.let { Stage.ENTRY to it }
                ?: faults.label[key]?.takeIf { aux }?.let { Stage.LABEL to it }
                ?: faults.signer[key]?.takeIf { aux }?.let { Stage.SIGNER to it }
                ?: keyFailure(key, pkg.labels.keys, faults)
        }

        private fun keyFailure(
            key: PackageKey,
            entries: Set<String>,
            faults: Faults,
        ): Pair<Stage, Failure>? = entries.sorted().firstNotNullOfOrNull { faults.keys[key to it] }?.let { Stage.KEYS to it }

        @Suppress("LongParameterList")
        private fun rebuilt(
            key: PackageKey,
            pkg: FakePackage,
            prev: PackageState?,
            failure: Pair<Stage, Failure>?,
            retryOnly: Boolean,
            entryFailed: Boolean,
            env: Environment,
            today: String,
        ): PackageState {
            val attempt =
                Attempt(
                    materialKey(pkg, env),
                    derivedKey(env),
                    env.bootCount,
                    issue(failure, prev, env, today),
                    retry = failure?.second?.category == IssueCategory.TRANSIENT && !retryOnly,
                )
            if (failure ==
                null
            ) {
                return PackageState(key, pkg.observation, currentFromDevice(key, pkg, env), emptyMap(), attempt, emptyMap())
            }
            // 失敗: 前の値から一覧にない入口を落とし、残りの入口は代わりの表示名（この回の表示名 → 前の代わりの表示名 → パッケージ名）
            val entries = pkg.labels.keys
            val current =
                prev?.current?.let { c ->
                    c.copy(
                        labels = c.labels.filterKeys { it in entries },
                        keysByEntry =
                            c.keysByEntry.filterKeys {
                                it in
                                    entries
                            },
                    )
                }
            // ENTRY の段で失敗した読み取りは表示名を返さない（前後の鍵が動いた回も同じ）
            val readLabels = if (entryFailed) emptyMap() else pkg.labels
            val substitutes =
                (entries - current?.labels?.keys.orEmpty()).associateWith {
                    readLabels[it] ?: prev?.substitutes?.get(
                        it,
                    ) ?: key.packageName
                }
            return PackageState(key, pkg.observation, current, substitutes, attempt, emptyMap())
        }

        @Suppress("LongParameterList")
        private fun rekeyed(
            key: PackageKey,
            pkg: FakePackage,
            prev: PackageState,
            failure: Pair<Stage, Failure>?,
            env: Environment,
            today: String,
        ): PackageState {
            val old = checkNotNull(prev.current)
            val current =
                if (failure == null) {
                    old.copy(
                        derivedKey = derivedKey(env),
                        keysByEntry =
                            old.labels.mapValues { (entry, label) ->
                                fakeKeys(
                                    AppEntry(appId(key, entry), label, old.localized[entry].orEmpty(), signerNames = old.signerDns),
                                    env,
                                )
                            },
                    )
                } else {
                    old
                }
            val attempt =
                prev.attempt.copy(
                    derivedKey = derivedKey(env),
                    issue = issue(failure, prev, env, today),
                    retry = failure?.second?.category == IssueCategory.TRANSIENT,
                )
            return PackageState(key, pkg.observation, current, prev.substitutes, attempt, emptyMap())
        }

        private fun issue(
            failure: Pair<Stage, Failure>?,
            prev: PackageState?,
            env: Environment,
            today: String,
        ): Issue? =
            failure?.let { (stage, f) ->
                Issue(stage, f, env.appVersion, prev?.attempt?.issue?.firstFailedOn ?: today, today)
            }

        // ジャンルの問い合わせに失敗した回は前のジャンルを保つ（新しい入口はなし）
        private fun genres(
            pkg: FakePackage,
            prev: PackageState?,
            faults: Faults,
        ): Map<String, List<GenreId>> =
            if (faults.genre != null) {
                prev?.genres.orEmpty().filterKeys { it in pkg.labels }
            } else {
                pkg.genres.filter { (entry, genres) -> entry in pkg.labels && genres.isNotEmpty() }
            }
    }

    private fun randomDevice(random: Random): FakeDevice =
        FakeDevice(SERIALS.filter { it == 0L || random.nextBoolean() }.associateWith { randomProfile(random) })

    private fun randomProfile(random: Random): FakeProfile =
        FakeProfile(
            status = randomStatus(random),
            packages = NAMES.filter { random.nextInt(3) > 0 }.associateWith { randomPackage(random) },
        )

    private fun randomStatus(random: Random): ListingStatus =
        when (random.nextInt(10)) {
            0 -> ListingStatus.UNAVAILABLE
            1 -> ListingStatus.FAILED
            else -> ListingStatus.COMPLETE
        }

    private fun randomPackage(random: Random): FakePackage {
        val entries = ACTIVITIES.shuffled(random).take(1 + random.nextInt(ACTIVITIES.size)).associateWith { LABELS.random(random) }
        return FakePackage(
            labels = entries,
            archived = random.nextInt(8) == 0,
            apkKey = listOf(null, "k1", "k2", "k3").random(random),
            updatedAt = listOf(null, 0L, 100L, 200L).random(random),
            localized = entries.keys.filter { random.nextBoolean() }.associateWith { listOf("L${random.nextInt(3)}") },
            signers = if (random.nextBoolean()) listOf("O=S${random.nextInt(3)}") else emptyList(),
            genres = entries.keys.filter { random.nextInt(3) == 0 }.associateWith { listOf(GenreId(GENRES.random(random))) },
        )
    }

    // 端末の変化 1 つ（何もしないこともある）。消えたプロファイルは戻らない（シリアル番号は使い回されない）
    private fun mutate(
        random: Random,
        device: FakeDevice,
    ): FakeDevice {
        val serial = device.profiles.keys.random(random)
        val profile = device.profiles.getValue(serial)
        val name = NAMES.random(random)
        val pkg = profile.packages[name]

        fun replaced(change: (FakePackage) -> FakePackage) =
            pkg?.let { device.with(serial, profile.copy(packages = profile.packages + (name to change(it)))) } ?: device
        return when (random.nextInt(13)) {
            0 -> device.with(serial, profile.copy(packages = profile.packages + (name to randomPackage(random))))

            1 -> device.with(serial, profile.copy(packages = profile.packages - name))

            2 -> replaced { it.copy(apkKey = listOf(null, "k1", "k2", "k4").random(random)) }

            3 -> replaced { it.copy(archived = !it.archived) }

            4 -> device.with(serial, profile.copy(status = randomStatus(random)))

            5 -> device.with(serial, profile.copy(readsComplete = random.nextInt(4) > 0))

            6 -> if (serial != 0L && random.nextInt(3) == 0) FakeDevice(device.profiles - serial, device.removed + serial) else device

            7 -> replaced { it.copy(labels = it.labels + (ACTIVITIES.random(random) to LABELS.random(random))) }

            8 -> replaced { it.copy(updatedAt = listOf(null, 0L, 100L, 300L).random(random)) }

            // 鍵の成分は変えずに材料だけを変える（鍵で拾えない変化。知らせか再試行でしか読み直さない）
            9 -> replaced { it.copy(signers = listOf("O=S${random.nextInt(3)}")) }

            // 入口を 1 つ減らす（失敗の間に消えて戻る入口を作る）
            10 -> replaced { p -> if (p.labels.size > 1) p.copy(labels = p.labels - p.labels.keys.random(random)) else p }

            else -> device
        }
    }

    private fun FakeDevice.with(
        serial: Long,
        profile: FakeProfile,
    ): FakeDevice = copy(profiles = profiles + (serial to profile))

    private fun mutateEnv(
        random: Random,
        env: Environment,
    ): Environment =
        when (random.nextInt(14)) {
            0 -> env.copy(locale = listOf("ja-JP", "en-US").random(random))
            1 -> env.copy(osBuild = listOf("build-1", "build-2").random(random))
            2 -> env.copy(appVersion = env.appVersion + 1)
            3 -> env.copy(generatorVersion = env.generatorVersion + 1)
            4 -> env.copy(bootCount = listOf(null, 5, 6, 7).random(random))
            5 -> env.copy(brandTableHash = listOf("brand-1", "brand-2").random(random))
            6 -> env.copy(readerVersion = env.readerVersion + 1)
            else -> env
        }

    private fun randomFaults(
        random: Random,
        device: FakeDevice,
    ): Faults {
        val keys = device.profiles.flatMap { (serial, p) -> p.packages.keys.map { PackageKey(serial, it) } }

        fun pick(rate: Int) = keys.filter { random.nextInt(100) < rate }.associateWith { randomFailure(random) }
        return Faults(
            entry = pick(FAULT_RATE),
            label = pick(FAULT_RATE),
            signer = pick(FAULT_RATE),
            moved = keys.filterTo(HashSet()) { random.nextInt(100) < MOVED_RATE },
            movedBefore = keys.filterTo(HashSet()) { random.nextInt(100) < MOVED_RATE },
            movedTime = keys.filterTo(HashSet()) { random.nextInt(100) < MOVED_RATE },
            keys =
                keys
                    .flatMap { key ->
                        device
                            .pkg(key)
                            .labels.keys
                            .map { key to it }
                    }.filter { random.nextInt(100) < FAULT_RATE }
                    .associateWith { randomFailure(random) },
            genre = if (random.nextInt(10) == 0) TRANSIENT else null,
        )
    }

    private fun randomFailure(random: Random): Failure = listOf(TRANSIENT, DETERMINISTIC, BUG).random(random)

    private fun randomNotice(
        random: Random,
        seq: Long,
    ): Notice =
        if (random.nextBoolean()) {
            Notice(seq, NAMES.random(random), SERIALS.random(random), 0)
        } else {
            // 受けた時点で番号が分からない知らせ。10 は解決でき、99 は解決できない
            Notice(seq, NAMES.random(random), null, listOf(10, 99).random(random))
        }

    private companion object {
        const val SEEDS = 300
        const val STEPS = 8
        const val CONVERGENCE_ROUNDS = 5
        const val FAULT_RATE = 15
        const val MOVED_RATE = 5
        val SERIALS = listOf(0L, 10L, 11L)
        val USER_SERIALS = mapOf(10 to 10L)
        val NAMES = listOf("a.maps", "b.mail", "c.camera", "d.game", "e.bank", "f.note")
        val ACTIVITIES = listOf("Main", "Alt", "Widget")
        val LABELS = listOf("マップ", "Mail", "カメラ", "銀行", "ノート", "ゲーム")
        val GENRES = listOf("maps", "mail", "game")

        // 欠けの初めの日が続けて欠けている間だけ保たれることを見るため、同期ごとに日を進める
        fun day(step: Int): String = "2026-12-%02d".format(step + 1)
    }
}

/** 端末の値から直に作る材料の鍵（本体の `MaterialKey.of` を通さない）。 */
internal fun materialKey(
    pkg: FakePackage,
    env: Environment,
): MaterialKey = MaterialKey(pkg.apkKey, pkg.updatedAt, pkg.labels.keys, pkg.archived, env.locale, env.osBuild, env.readerVersion)

/** 環境から直に作るキーの鍵。 */
internal fun derivedKey(env: Environment): DerivedKey = DerivedKey(env.generatorVersion, env.brandTableHash)

/** 端末の材料から直に作る、全部が成功した回の検索に使う値。アーカイブ済みは補助の材料が空。 */
internal fun currentFromDevice(
    key: PackageKey,
    pkg: FakePackage,
    env: Environment,
): Current {
    val localized = if (pkg.archived) emptyMap() else pkg.localized.filter { (entry, names) -> entry in pkg.labels && names.isNotEmpty() }
    val signers = if (pkg.archived) emptyList() else pkg.signers
    return Current(
        materialKey(pkg, env),
        derivedKey(env),
        pkg.labels,
        localized,
        signers,
        pkg.labels.mapValues { (entry, label) ->
            fakeKeys(AppEntry(appId(key, entry), label, localized[entry].orEmpty(), signerNames = signers), env)
        },
    )
}
