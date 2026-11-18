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

/** 偽の生成器。入力から決まるキーを返す。 */
internal fun fakeKeys(entry: AppEntry): List<SearchKey> =
    listOf(SearchKey(NormalizedText("name:${entry.label}"), SearchKeyKind.NAME)) +
        entry.localizedLabels.map { SearchKey(NormalizedText("loc:$it"), SearchKeyKind.LOCALIZED_NAME) } +
        entry.signerNames.map { SearchKey(NormalizedText("pub:$it"), SearchKeyKind.PUBLISHER) }

/** 同期 1 回の入力と出力。 */
internal data class Round(
    val previous: IndexSnapshot,
    val observation: DeviceObservation,
    val plan: SyncPlan,
    val reads: Map<Long, ProfileReads>,
    val jobs: Map<PackageKey, List<KeyJob>>,
    val built: Map<PackageKey, Map<String, KeyOutcome>>,
    val genres: GenreResult,
    val outcome: SyncOutcome,
) {
    val next: IndexSnapshot get() = outcome.snapshot
}

/** [device] を相手に同期を 1 回行う。端末の読み取りとキーの生成は計画のとおりにだけ行う。 */
internal fun sync(
    previous: IndexSnapshot,
    device: FakeDevice,
    env: Environment = ENV,
    faults: Faults = Faults.NONE,
    notices: List<Notice> = emptyList(),
    userSerials: Map<Int, Long> = emptyMap(),
): Round {
    val observation = device.observe()
    val plan = IndexRules.plan(previous, observation, env, notices, userSerials)
    val reads = read(device, plan, faults)
    val jobs = IndexRules.keyJobs(previous, observation, plan, reads, env)
    val built =
        jobs.mapValues { (key, list) ->
            list.associate { job ->
                val failure = faults.keys[key to job.entry.id.activityName]
                job.entry.id.activityName to
                    when {
                        failure != null -> KeyOutcome.Failed(failure)
                        job.substitute -> KeyOutcome.Built(SubstituteKeys.of(job.entry.label))
                        else -> KeyOutcome.Built(fakeKeys(job.entry))
                    }
            }
        }
    val genres =
        faults.genre?.let { GenreResult.Failed(it.cause) } ?: GenreResult.Found(
            plan.genreTargets
                .associateWith { id ->
                    device.pkg(PackageKey(id.profileSerial, id.packageName)).genres[id.activityName].orEmpty()
                }.filterValues { it.isNotEmpty() },
        )
    val outcome = IndexRules.apply(previous, observation, plan, reads, genres, built, env, TODAY)
    return Round(previous, observation, plan, reads, jobs, built, genres, outcome)
}

private fun read(
    device: FakeDevice,
    plan: SyncPlan,
    faults: Faults,
): Map<Long, ProfileReads> =
    plan.reads.entries.groupBy { it.key.profileSerial }.mapValues { (serial, jobs) ->
        val profile = device.profiles.getValue(serial)
        ProfileReads(
            if (profile.readsComplete) ListingStatus.COMPLETE else ListingStatus.UNAVAILABLE,
            jobs.associate { (key, job) ->
                val pkg = device.pkg(key)
                val keys = KeyObservation(pkg.apkKey, pkg.updatedAt)
                val moved = if (key in faults.moved) KeyObservation("moved", (pkg.updatedAt ?: 0) + 1) else keys
                val entryFailure = faults.entry[key]
                val aux = job.auxiliary && entryFailure == null
                val labelFailure = faults.label[key]?.takeIf { aux }
                key.packageName to
                    MaterialRead(
                        labels = pkg.labels.mapValues { (_, label) -> label.takeIf { entryFailure == null } },
                        entryFailure = entryFailure,
                        before = keys,
                        after = moved,
                        // 別の言語の名前は、失敗したら先頭の入口の分だけが読めなかったことにする
                        localized =
                            if (aux) {
                                pkg.localized.filterKeys { labelFailure == null || it != pkg.labels.keys.minOrNull() }
                            } else {
                                emptyMap()
                            },
                        labelFailure = labelFailure,
                        signerDns = if (aux && faults.signer[key] == null) pkg.signers else emptyList(),
                        signerFailure = faults.signer[key]?.takeIf { aux },
                    )
            },
        )
    }

internal fun appId(
    key: PackageKey,
    entry: String,
): AppId = AppId(key.packageName, entry, key.profileSerial)
