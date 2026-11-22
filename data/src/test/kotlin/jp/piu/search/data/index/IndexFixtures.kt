// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import android.database.sqlite.SQLiteFullException
import android.os.Process
import android.os.UserHandle
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.state.DeviceObservation
import jp.piu.search.core.state.Environment
import jp.piu.search.core.state.Failure
import jp.piu.search.core.state.GenreRequest
import jp.piu.search.core.state.GenreResult
import jp.piu.search.core.state.IndexRules
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.KeyJob
import jp.piu.search.core.state.KeyObservation
import jp.piu.search.core.state.KeyOutcome
import jp.piu.search.core.state.ListingStatus
import jp.piu.search.core.state.MaterialRead
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.PackageObservation
import jp.piu.search.core.state.ProfileListing
import jp.piu.search.core.state.ProfileReadRequest
import jp.piu.search.core.state.ProfileReads
import jp.piu.search.core.state.Stage
import jp.piu.search.core.text.NormalizedText
import java.io.IOException

// 索引の側のテストに共通の材料。自分の番号は 0、仕事用は 11

internal const val OWN = 0L
internal const val WORK = 11L

/** 仕事用のユーザーの識別子（uid の幅は AOSP の `UserHandle.PER_USER_RANGE`）。 */
internal val WORK_USER: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)
internal val OWN_USER: UserHandle = Process.myUserHandle()

internal val TRANSIENT = Failure("java.io.IOException", IssueCategory.TRANSIENT)
internal val BUG = Failure("java.lang.IllegalStateException", IssueCategory.SUSPECTED_BUG)

/** 偽の端末の組 1 つ。 */
internal data class FakeApp(
    val labels: Map<String, String>,
    val apkKey: String? = "apk-1",
    val updatedAt: Long? = 100,
    val archived: Boolean = false,
    val signers: List<String> = emptyList(),
    val genres: Map<String, List<GenreId>> = emptyMap(),
)

internal fun app(
    label: String,
    signers: List<String> = emptyList(),
) = FakeApp(mapOf("Main" to label), signers = signers)

/**
 * 偽の [DeviceReader]。読んだ組を数え、材料の読み取りに本当の失敗を注入できる。
 * [duringRead] は材料の読み取りの中（書き手の列の中）で呼ぶ。同期の途中に届く頼みを再現する。
 */
internal class FakeReader : DeviceReader {
    override var readerVersion = 1
    var environment = DeviceEnvironment("ja-JP", "build-1", 5)
    val profiles = linkedMapOf<Long, MutableMap<String, FakeApp>>(OWN to linkedMapOf(), WORK to linkedMapOf())
    val statuses = HashMap<Long, ListingStatus>()
    val serials = HashMap<UserHandle, Long>()
    val failures = HashMap<PackageKey, Pair<Stage, Failure>>()
    val reads = ArrayList<PackageKey>()
    var observeCalls = 0
    var duringRead: () -> Unit = {}

    fun install(
        key: PackageKey,
        app: FakeApp,
    ) {
        profiles.getOrPut(key.profileSerial) { linkedMapOf() }[key.packageName] = app
    }

    fun app(key: PackageKey): FakeApp = profiles.getValue(key.profileSerial).getValue(key.packageName)

    override fun environment(): DeviceEnvironment = environment

    override fun observe(known: Set<Long>): DeviceObservation {
        observeCalls++
        return DeviceObservation(
            profiles.mapValues { (serial, apps) ->
                val status = statuses[serial] ?: ListingStatus.COMPLETE
                if (status != ListingStatus.COMPLETE) {
                    ProfileListing(status)
                } else {
                    ProfileListing(
                        status,
                        apps.mapValues { PackageObservation(it.value.labels.keys, it.value.archived, it.value.apkKey, it.value.updatedAt) },
                    )
                }
            },
            known - profiles.keys,
        )
    }

    override fun serialOf(user: UserHandle): Long? = serials[user]

    override fun readMaterials(request: ProfileReadRequest): ProfileReads {
        duringRead()
        val status = statuses[request.serial] ?: ListingStatus.COMPLETE
        if (status != ListingStatus.COMPLETE) return ProfileReads(request, status)
        return ProfileReads(
            request,
            status,
            request.jobs.associateWith { job ->
                reads += job.key
                val app = app(job.key)
                val keys = KeyObservation(app.apkKey, app.updatedAt)
                failures[job.key]?.let { (stage, failure) -> MaterialRead.Failed(stage, failure, keys, keys, app.labels) }
                    ?: MaterialRead.Read(app.labels, keys, keys, signerDns = if (job.auxiliary) app.signers else emptyList())
            },
        )
    }

    override fun genres(request: GenreRequest): GenreResult =
        GenreResult.Found(
            request,
            request.targets
                .associateWith {
                    profiles[it.profileSerial]
                        ?.get(it.packageName)
                        ?.genres
                        ?.get(it.activityName)
                        .orEmpty()
                }.filterValues { it.isNotEmpty() },
        )
}

/** 偽のキーの生成。入力と版から決まるキーを返し、呼ばれた回数と頼みを数える。 */
internal class FakeKeys : KeyFactory {
    override var generatorVersion = 7
    override var brandTableHash = "brand-1"
    val failing = HashSet<PackageKey>()
    var calls = 0
    val built = ArrayList<KeyJob>()

    override fun build(jobs: List<KeyJob>): Map<KeyJob, KeyOutcome> {
        calls++
        built += jobs
        return jobs.associateWith { job ->
            val id = job.entry.id
            if (PackageKey(id.profileSerial, id.packageName) in failing) KeyOutcome.Failed(BUG) else KeyOutcome.Built(keysOf(job.entry))
        }
    }

    fun keysOf(entry: AppEntry): List<SearchKey> =
        listOf(
            SearchKey(NormalizedText("name:${entry.label}"), SearchKeyKind.NAME),
            SearchKey(NormalizedText("gen:$generatorVersion"), SearchKeyKind.READING),
        ) + entry.signerNames.map { SearchKey(NormalizedText("pub:$it"), SearchKeyKind.PUBLISHER) }
}

/** 読み込みと保存を、指定した回数だけ失敗させる保存先。失敗は委ねる前に投げ、DB に触れない。 */
internal class FaultyIndexStorage(
    private val delegate: IndexStorage,
) : IndexStorage {
    var failLoads = 0
    var failSaves = 0
    var loads = 0
    val saves = ArrayList<IndexSnapshot>()

    /** 保存に入ったときに呼ぶ（保存の途中の公開値を見る・取り消しを注入する）。 */
    var onSave: () -> Unit = {}

    override fun load(): StoredSnapshot {
        loads++
        if (failLoads > 0) {
            failLoads--
            throw IOException("読み込みの失敗")
        }
        return delegate.load()
    }

    override fun save(
        snapshot: IndexSnapshot,
        savedAt: Long,
    ) {
        onSave()
        if (failSaves > 0) {
            failSaves--
            throw SQLiteFullException("保存の失敗")
        }
        delegate.save(snapshot, savedAt)
        saves += snapshot
    }
}

/** 組の、検索に使うキーの語（照合の材料から）。 */
internal fun IndexSnapshot.keyTexts(key: PackageKey): Set<String> =
    checkNotNull(packages[key])
        .apps()
        .flatMap { app -> app.keys.map { it.text.value } }
        .toSet()

/** 偽の端末から、規則だけで写しを作る（書き手を通さない。保存の部品のテストの材料）。 */
internal fun snapshotOf(
    reader: FakeReader,
    keys: FakeKeys = FakeKeys(),
    previous: IndexSnapshot = IndexSnapshot.EMPTY,
): IndexSnapshot {
    val env = Environment("ja-JP", "build-1", reader.readerVersion, keys.generatorVersion, keys.brandTableHash, 1, 5)
    val plan = IndexRules.plan(previous, reader.observe(emptySet()), env)
    val prepared = IndexRules.prepare(plan, plan.profileReads.values.map(reader::readMaterials), "2026-11-22")
    val built = if (prepared.jobs.isEmpty()) emptyMap() else keys.build(prepared.jobs)
    return IndexRules.apply(prepared, reader.genres(plan.genres), built).snapshot
}
