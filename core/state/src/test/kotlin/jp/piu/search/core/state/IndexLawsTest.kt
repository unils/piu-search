// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.GenreId
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 同期の法則 1〜5 を、乱数で作った端末の変化と失敗の列で確かめる。
 *
 * 種ごとに、プロファイル 3 つ（個人用 0・仕事用 10・11）、組 6 つ・入口 3 つの範囲で端末を作り、[STEPS] 回、端末・環境・失敗・知らせを
 * 変えながら同期する。端末の変化には、鍵の成分（APK・時刻（0 を含む）・入口・アーカイブ）を 1 つだけ変えるものを含める。
 * 毎回の同期で法則 2〜5 を確かめ、ときどき失敗のない同期を挟んで回復を確かめ、最後に端末と失敗を固定して法則 1（収束）を確かめる。
 * 期待する鍵と材料は、本体の関数を通さず、端末の偽物の値から直に作る。
 */
class IndexLawsTest {
    @Test
    fun `法則 1〜5 - 乱数の端末の変化と失敗の列`() {
        forAllSeeds("索引の法則", SEEDS) { random, log ->
            var device = randomDevice(random)
            var env = ENV
            var snapshot = IndexSnapshot.EMPTY
            var seq = 0L
            repeat(STEPS) { step ->
                device = mutate(random, device)
                env = mutateEnv(random, env)
                val faults = randomFaults(random, device)
                val notices = List(random.nextInt(3)) { randomNotice(random, ++seq) }
                log += "step $step: device=$device env=$env faults=$faults notices=$notices"
                val round = sync(snapshot, device, env, faults, notices, userSerials = mapOf(10 to 10L))
                checkRound(round, device, env, faults)
                snapshot = round.next
                if (random.nextInt(3) == 0) {
                    log += "step $step: 失敗のない同期で回復を確かめる"
                    snapshot = checkRecovery(snapshot, device, env)
                }
            }
            checkConvergence(random, snapshot, device, env, log)
        }
    }

    private fun checkRound(
        round: Round,
        device: FakeDevice,
        env: Environment,
        faults: Faults,
    ) {
        val observation = round.observation
        // 当ててよいプロファイルを、端末の側から決め直す: 一覧が最後まで読めて、消えておらず、材料を読んだなら読み終えた後も読めた
        val readSerials =
            round.plan.reads.keys
                .mapTo(HashSet()) { it.profileSerial }
        val applied =
            device.profiles
                .filter { (serial, p) ->
                    p.status == ListingStatus.COMPLETE && serial !in device.removed && (serial !in readSerials || p.readsComplete)
                }.keys
        law(round.outcome.appliedProfiles == applied) { "当てたプロファイル ${round.outcome.appliedProfiles} が $applied と違う" }
        // 法則 2: 一覧と材料の両方が有効なプロファイルでは、入口の集合が一覧と等しい
        for (serial in applied) {
            val listed =
                observation.profiles
                    .getValue(serial)
                    .packages
                    .mapValues { it.value.entries }
            val indexed =
                round.next.packages.values
                    .filter { it.key.profileSerial == serial }
                    .associate { it.key.packageName to it.apps().mapTo(HashSet()) { app -> app.id.activityName } }
            law(listed == indexed) { "法則 2: プロファイル $serial の一覧 $listed と索引 $indexed が違う" }
        }
        // 法則 3: 有効でない（消えてもいない）プロファイルの組は、前後で同じ。新しく入った組がないことも見るため、プロファイルごとの組の全体を比べる
        val serials = (round.previous.packages.keys + round.next.packages.keys).mapTo(HashSet()) { it.profileSerial }
        for (serial in serials.filter { it !in applied && it !in observation.removedProfiles }) {
            law(
                round.previous.packages.filterKeys { it.profileSerial == serial } ==
                    round.next.packages.filterKeys { it.profileSerial == serial },
            ) {
                "法則 3: 読めないプロファイル $serial の組が変わった"
            }
        }
        law(
            round.next.packages.keys
                .none { it.profileSerial in observation.removedProfiles },
        ) { "消えたプロファイルの組が残った" }
        // 法則 4: 当てたプロファイルのすべての組で、保存した鍵が入力から直に作った今の値と等しい（読み直しを省けば、ここで古い鍵が見つかる）
        for ((key, state) in round.next.packages.filterKeys { it.profileSerial in applied }) {
            val pkg = device.pkg(key)
            val expected =
                MaterialKey(pkg.apkKey, pkg.updatedAt, pkg.labels.keys, pkg.archived, env.locale, env.osBuild, env.readerVersion)
            law(state.materials.key == expected) { "法則 4: $key の材料の鍵 ${state.materials.key} が今の値 $expected でない" }
            val derived = DerivedKey(state.materials.generation, env.generatorVersion, env.brandTableHash)
            law(state.derived.key == derived) { "法則 4: $key のキーの鍵が今の値でない" }
            val before = round.previous.packages[key]
            val generation = (before?.materials?.generation ?: 0) + if (key in round.plan.reads) 1 else 0
            law(state.materials.generation == generation) { "$key の世代が、読んだ回だけ 1 進む、になっていない" }
            if (before == null || before.materials.key != expected) law(key in round.plan.reads) { "法則 4: 鍵の違う $key を読まない" }
        }
        // 法則 5: 読み直すきっかけがあり、その回にすべての段が成功した組は、端末の材料から作った結果と一致する
        for (key in round.plan.reads.keys
            .filter { it.profileSerial in applied && clean(it, faults) }) {
            checkFromDevice(round.next.packages.getValue(key), device.pkg(key), "法則 5")
        }
    }

    // 端末の材料から直に作った値と比べる（本体の計算を通さない）
    private fun checkFromDevice(
        state: PackageState,
        pkg: FakePackage,
        name: String,
    ) {
        val key = state.key
        val localized =
            if (pkg.archived) {
                emptyMap()
            } else {
                pkg.localized.filter { (entry, names) ->
                    entry in pkg.labels && names.isNotEmpty()
                }
            }
        val signers = if (pkg.archived) emptyList() else pkg.signers
        law(state.materials.labels == pkg.labels) { "$name: $key の表示名が端末と違う" }
        law(state.materials.localized == localized) { "$name: $key の別の言語の名前が端末と違う" }
        law(state.materials.signerDns == signers) { "$name: $key の署名が端末と違う" }
        val keys =
            pkg.labels.mapValues { (entry, label) ->
                fakeKeys(AppEntry(appId(key, entry), label, localized[entry].orEmpty(), signerNames = signers))
            }
        law(state.derived.keysByEntry == keys) { "$name: $key のキーが端末の材料から作ったものと違う" }
        law(
            state.materials.issues.isEmpty() && state.derived.issue == null && state.materials.substituted.isEmpty(),
        ) { "$name: $key に欠けが残った" }
    }

    // 回復: 一時的な失敗で印の立った組は、同じ鍵のまま失敗がなくなった次の同期で読み直され、端末の材料と一致する
    private fun checkRecovery(
        snapshot: IndexSnapshot,
        device: FakeDevice,
        env: Environment,
    ): IndexSnapshot {
        val round = sync(snapshot, device, env)
        val marked =
            snapshot.packages.values
                .filter { state ->
                    state.materials.issues.values
                        .any { it.retryOnce }
                }.map { it.key }
        for (key in marked.filter { it.profileSerial in round.outcome.appliedProfiles && it in round.next.packages }) {
            law(key in round.plan.reads) { "回復: 印の立った $key を読まない" }
            checkFromDevice(round.next.packages.getValue(key), device.pkg(key), "回復")
        }
        return round.next
    }

    // 法則 1: 端末・環境・失敗を固定すると、再試行の印を使い切った後は読まない。印も知らせもなければ、次の同期は読まない
    private fun checkConvergence(
        random: Random,
        start: IndexSnapshot,
        frozen: FakeDevice,
        env: Environment,
        log: MutableList<String>,
    ) {
        val device = frozen.copy(profiles = frozen.profiles.mapValues { it.value.copy(readsComplete = true) })
        val faults = randomFaults(random, device)
        log += "固定: device=$device faults=$faults"
        var snapshot = start
        repeat(CONVERGENCE_ROUNDS) { round ->
            val result = sync(snapshot, device, env, faults)
            val noMarks =
                result.next.packages.values
                    .none { it.retryPending }
            val again = IndexRules.plan(result.next, device.observe(), env)
            law(!noMarks || again.isIdle) { "法則 1: 印がないのに読む: ${again.reads.keys} ${again.keys.keys}" }
            if (round >= 2) law(result.plan.isIdle) { "法則 1: ${round + 1} 回目の同期でも読む: ${result.plan}" }
            snapshot = result.next
        }
    }

    private fun clean(
        key: PackageKey,
        faults: Faults,
    ): Boolean =
        key !in faults.entry && key !in faults.label && key !in faults.signer && key !in faults.moved &&
            faults.keys.keys.none { it.first == key }

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
        return when (random.nextInt(11)) {
            0 -> device.with(serial, profile.copy(packages = profile.packages + (name to randomPackage(random))))
            1 -> device.with(serial, profile.copy(packages = profile.packages - name))
            2 -> replaced { it.copy(apkKey = listOf(null, "k1", "k2", "k4").random(random)) }
            3 -> replaced { it.copy(archived = !it.archived) }
            4 -> device.with(serial, profile.copy(status = randomStatus(random)))
            5 -> device.with(serial, profile.copy(readsComplete = random.nextInt(4) > 0))
            6 -> if (serial != 0L && random.nextInt(3) == 0) FakeDevice(device.profiles - serial, device.removed + serial) else device
            8 -> replaced { it.copy(updatedAt = listOf(null, 0L, 100L, 300L).random(random)) }
            7 -> replaced { it.copy(labels = it.labels + (ACTIVITIES.random(random) to LABELS.random(random))) }
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
        when (random.nextInt(12)) {
            0 -> env.copy(locale = listOf("ja-JP", "en-US").random(random))
            1 -> env.copy(osBuild = listOf("build-1", "build-2").random(random))
            2 -> env.copy(appVersion = env.appVersion + 1)
            3 -> env.copy(generatorVersion = env.generatorVersion + 1)
            4 -> env.copy(bootCount = listOf(null, 5, 6, 7).random(random))
            5 -> env.copy(brandTableHash = listOf("brand-1", "brand-2").random(random))
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
        val NAMES = listOf("a.maps", "b.mail", "c.camera", "d.game", "e.bank", "f.note")
        val ACTIVITIES = listOf("Main", "Alt", "Widget")
        val LABELS = listOf("マップ", "Mail", "カメラ", "銀行", "ノート", "ゲーム")
        val GENRES = listOf("maps", "mail", "game")
    }
}
