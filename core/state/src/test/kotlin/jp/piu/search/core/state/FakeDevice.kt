// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText

/** 試験の既定の環境。 */
internal val ENV = Environment("ja-JP", "build-1", 1, 7, "brand-1", 1, 5)

internal const val TODAY = "2026-11-18"

/** 端末の組 1 つの中身。 */
internal data class FakePackage(
    val labels: Map<String, String>,
    val archived: Boolean = false,
    val apkKey: String? = "apk-1",
    val updatedAt: Long? = 100,
    val localized: Map<String, List<String>> = emptyMap(),
    val signers: List<String> = emptyList(),
    val genres: Map<String, List<GenreId>> = emptyMap(),
) {
    val observation: PackageObservation get() = PackageObservation(labels.keys, archived, apkKey, updatedAt)
}

/** 端末のプロファイル 1 つ。[readsComplete] が偽なら、材料を読み終えた後に読めなくなる。 */
internal data class FakeProfile(
    val status: ListingStatus = ListingStatus.COMPLETE,
    val packages: Map<String, FakePackage> = emptyMap(),
    val readsComplete: Boolean = true,
)

/** 端末の全体。 */
internal data class FakeDevice(
    val profiles: Map<Long, FakeProfile>,
    val removed: Set<Long> = emptySet(),
) {
    fun observe(): DeviceObservation =
        DeviceObservation(
            profiles.mapValues { (_, p) ->
                if (p.status == ListingStatus.COMPLETE) {
                    ProfileListing(p.status, p.packages.mapValues { it.value.observation })
                } else {
                    ProfileListing(p.status)
                }
            },
            removed,
        )

    fun pkg(key: PackageKey): FakePackage = profiles.getValue(key.profileSerial).packages.getValue(key.packageName)
}

/** 同期 1 回に注入する失敗。 */
internal data class Faults(
    val entry: Map<PackageKey, Failure> = emptyMap(),
    val label: Map<PackageKey, Failure> = emptyMap(),
    val signer: Map<PackageKey, Failure> = emptyMap(),
    val moved: Set<PackageKey> = emptySet(),
    val movedBefore: Set<PackageKey> = emptySet(),
    val movedTime: Set<PackageKey> = emptySet(),
    val keys: Map<Pair<PackageKey, String>, Failure> = emptyMap(),
    val genre: Failure? = null,
) {
    companion object {
        val NONE = Faults()
    }
}

internal val TRANSIENT = Failure("java.io.IOException", IssueCategory.TRANSIENT)
internal val DETERMINISTIC = Failure("java.security.cert.CertificateException", IssueCategory.DETERMINISTIC)
internal val BUG = Failure("java.lang.IllegalStateException", IssueCategory.SUSPECTED_BUG)

/** 偽の生成器。入力と生成器の版・ブランド表から決まるキーを返す（版の違う回の結果を取り違えれば、キーの違いで見える）。 */
internal fun fakeKeys(
    entry: AppEntry,
    env: Environment,
): List<SearchKey> =
    listOf(
        SearchKey(NormalizedText("name:${entry.label}"), SearchKeyKind.NAME),
        SearchKey(NormalizedText("gen:${env.generatorVersion}:${env.brandTableHash}"), SearchKeyKind.READING),
    ) +
        entry.localizedLabels.map { SearchKey(NormalizedText("loc:$it"), SearchKeyKind.LOCALIZED_NAME) } +
        entry.signerNames.map { SearchKey(NormalizedText("pub:$it"), SearchKeyKind.PUBLISHER) }

/** 材料もキーも作らない計画か（試験の読みやすさのため）。 */
internal val SyncPlan.isIdle: Boolean get() = !hasBuildWork

/** 同期 1 回の入力と出力。 */
internal data class Round(
    val previous: IndexSnapshot,
    val observation: DeviceObservation,
    val plan: SyncPlan,
    val reads: List<ProfileReads>,
    val jobs: Map<PackageKey, List<KeyJob>>,
    val built: Map<KeyJob, KeyOutcome>,
    val genres: GenreResult,
    val outcome: SyncOutcome,
) {
    val next: IndexSnapshot get() = outcome.snapshot
}

/** [device] を相手に同期を 1 回行う。端末の読み取りとキーの生成は計画のとおりにだけ行う。 */
@Suppress("LongParameterList")
internal fun sync(
    previous: IndexSnapshot,
    device: FakeDevice,
    env: Environment = ENV,
    faults: Faults = Faults.NONE,
    notices: List<Notice> = emptyList(),
    userSerials: Map<Int, Long> = emptyMap(),
    today: String = TODAY,
): Round {
    val observation = device.observe()
    val plan = IndexRules.plan(previous, observation, env, notices, userSerials)
    val reads = read(device, plan, faults)
    val prepared = IndexRules.prepare(plan, reads, today)
    val built =
        prepared.jobs.associateWith { job ->
            val id = job.entry.id
            faults.keys[PackageKey(id.profileSerial, id.packageName) to id.activityName]?.let { KeyOutcome.Failed(it) }
                ?: KeyOutcome.Built(fakeKeys(job.entry, env))
        }
    val genres =
        faults.genre?.let { GenreResult.Failed(plan.genres, it.cause) } ?: GenreResult.Found(
            plan.genres,
            plan.genres.targets
                .associateWith { id ->
                    device.pkg(PackageKey(id.profileSerial, id.packageName)).genres[id.activityName].orEmpty()
                }.filterValues { it.isNotEmpty() },
        )
    val outcome = IndexRules.apply(prepared, genres, built)
    // 規則が作った写しは、保存して読み戻しても同じで、検索に使う値と最後の試みの整合を満たす
    check(IndexSnapshot.restore(outcome.snapshot.packages.values, outcome.snapshot.listings) == outcome.snapshot)
    val jobs = prepared.jobs.groupBy { PackageKey(it.entry.id.profileSerial, it.entry.id.packageName) }
    return Round(previous, observation, plan, reads, jobs, built, genres, outcome)
}

// 偽の読み手。読む前後の鍵を取り、段の順（ENTRY・LABEL・SIGNER）に、注入した失敗の最初のものを本当の失敗として返す。
// アーカイブ済み（補助の材料を頼まれない）は補助の段を読まないので、その段の失敗も起きず、空で成功する
private fun read(
    device: FakeDevice,
    plan: SyncPlan,
    faults: Faults,
): List<ProfileReads> =
    plan.profileReads.values.map { request ->
        val profile = device.profiles.getValue(request.serial)
        ProfileReads(
            request,
            if (profile.readsComplete) ListingStatus.COMPLETE else ListingStatus.UNAVAILABLE,
            request.jobs.associateWith { job -> readPackage(device.pkg(job.key), job.key, job.auxiliary, faults) },
        )
    }

private fun readPackage(
    pkg: FakePackage,
    key: PackageKey,
    aux: Boolean,
    faults: Faults,
): MaterialRead {
    val keys = KeyObservation(pkg.apkKey, pkg.updatedAt)
    // moved は読んだ後の APK と時刻、movedBefore は読む前の APK だけ、movedTime は読んだ後の時刻だけを変える
    val after =
        when (key) {
            in faults.moved -> KeyObservation("moved", (pkg.updatedAt ?: 0) + 1)
            in faults.movedTime -> KeyObservation(pkg.apkKey, (pkg.updatedAt ?: 0) + 1)
            else -> keys
        }
    val before = if (key in faults.movedBefore) KeyObservation("moved-before", pkg.updatedAt) else keys
    faults.entry[key]?.let { return MaterialRead.Failed(Stage.ENTRY, it, before, after) }
    if (aux) {
        faults.label[key]?.let { return MaterialRead.Failed(Stage.LABEL, it, before, after, pkg.labels) }
        faults.signer[key]?.let { return MaterialRead.Failed(Stage.SIGNER, it, before, after, pkg.labels) }
    }
    return MaterialRead.Read(
        labels = pkg.labels,
        before = before,
        after = after,
        localized = if (aux) pkg.localized else emptyMap(),
        signerDns = if (aux) pkg.signers else emptyList(),
    )
}

internal fun appId(
    key: PackageKey,
    entry: String,
): AppId = AppId(key.packageName, entry, key.profileSerial)
