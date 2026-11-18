// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.GenreId
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 同期の法則 1〜5 を、乱数で作った端末の変化と失敗の列で確かめる。
 *
 * 種ごとに、プロファイル 3 つ（個人用 0・仕事用 10・11）、組 6 つ・入口 3 つの範囲で端末を作り、[STEPS] 回、端末・環境・失敗・知らせを
 * 変えながら同期する。毎回の同期で法則 2〜5 を確かめ、最後に端末と失敗を固定して法則 1（収束）を確かめる。
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
        // 法則 3: 有効でない（消えてもいない）プロファイルの組は、前後で同じ
        val untouched =
            round.previous.packages.keys
                .filter { it.profileSerial !in applied && it.profileSerial !in observation.removedProfiles }
        for (key in untouched) {
            law(round.previous.packages[key] == round.next.packages[key]) { "法則 3: 読めないプロファイルの組 $key が変わった" }
        }
        for (key in round.previous.packages.keys
            .filter { it.profileSerial in observation.removedProfiles }) {
            law(key !in round.next.packages) { "消えたプロファイルの組 $key が残った" }
        }
        // 法則 4: 読んだ組の鍵は、成否によらず今の値
        for (key in round.plan.reads.keys
            .filter { it.profileSerial in applied }) {
            val state = round.next.packages.getValue(key)
            val observed =
                observation.profiles
                    .getValue(key.profileSerial)
                    .packages
                    .getValue(key.packageName)
            law(state.materials.key == MaterialKey.of(observed, env)) { "法則 4: $key の材料の鍵が今の値でない" }
            law(state.derived.key == DerivedKey.of(state.materials.generation, env)) { "法則 4: $key のキーの鍵が今の値でない" }
            val before =
                round.previous.packages[key]
                    ?.materials
                    ?.generation ?: 0
            law(state.materials.generation == before + 1) { "$key の世代が 1 進んでいない" }
        }
        for (key in round.plan.keys.keys
            .filter { it.profileSerial in applied }) {
            val state = round.next.packages.getValue(key)
            law(state.derived.key == DerivedKey.of(state.materials.generation, env)) { "法則 4: $key のキーの鍵が今の値でない" }
        }
        // 法則 5: 読み直すきっかけがあり、その回にすべての段が成功した組は、全件を作り直した結果と一致する
        val fresh by lazy { sync(IndexSnapshot.EMPTY, device, env).next }
        for (key in round.plan.reads.keys
            .filter { it.profileSerial in applied && clean(it, faults) }) {
            val state = round.next.packages.getValue(key)
            val expected = fresh.packages.getValue(key)
            law(state.derived.keysByEntry == expected.derived.keysByEntry) { "法則 5: $key のキーが作り直した結果と違う" }
            law(state.materials.labels == expected.materials.labels && state.materials.localized == expected.materials.localized) {
                "法則 5: $key の材料が作り直した結果と違う"
            }
            law(state.materials.signerDns == expected.materials.signerDns) { "法則 5: $key の署名が作り直した結果と違う" }
            law(state.materials.issues.isEmpty() && state.derived.issue == null) { "法則 5: $key に欠けが残った" }
        }
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
        return when (random.nextInt(10)) {
            0 -> device.with(serial, profile.copy(packages = profile.packages + (name to randomPackage(random))))
            1 -> device.with(serial, profile.copy(packages = profile.packages - name))
            2 -> replaced { it.copy(apkKey = listOf(null, "k1", "k2", "k4").random(random)) }
            3 -> replaced { it.copy(archived = !it.archived) }
            4 -> device.with(serial, profile.copy(status = randomStatus(random)))
            5 -> device.with(serial, profile.copy(readsComplete = random.nextInt(4) > 0))
            6 -> if (serial != 0L && random.nextInt(3) == 0) FakeDevice(device.profiles - serial, device.removed + serial) else device
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
