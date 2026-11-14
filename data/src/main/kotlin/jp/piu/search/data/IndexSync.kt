// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 索引の受け取り手。索引を差し替えるたびに、保存後の共通の書き手から呼ばれる。
 */
public fun interface IndexListener {
    /** 新しい索引 [index] に差し替えた。 */
    public fun onIndexChanged(index: SearchIndex)
}

/** 同期で取った方法（[SyncReport.mode]）。 */
public enum class SyncMode {
    /** 保存した索引がない・索引の版か端末の言語・OS のビルドが変わった。すべての入口を作り直した。 */
    FULL_REBUILD,

    /** 再起動した（変更の番号が使えない）。すべての入口の更新の時刻を、保存した索引と比べた。 */
    FULL_REVIEW,

    /** 個人用の変更番号と仕事用の APK 指紋で差分を取った。 */
    CHANGES,
}

/**
 * 同期 1 回の結果。記録と計器テストに使う。
 *
 * @property genreFailed ジャンルの問い合わせに失敗したか
 * @property genreMillis 全体のジャンル判定にかかった時間
 * @property failedPackages 材料かキー生成に失敗したパッケージ数
 * @property failedProfiles 一覧を読み取れなかったプロファイル数。一時停止は含めない
 * @property mode 取った方法
 * @property rebuiltApps 作り直した入口の数
 * @property removedPackages 索引から消したパッケージの数（プロファイルとパッケージの組で数える）
 * @property removedProfiles 索引から消したプロファイルの数
 * @property analyzerLoadMillis 形態素解析器の辞書の読み込みにかかった時間。読み込まなかったら 0
 * @property totalMillis 同期全体の時間（保存した索引の読み込みを含む）
 * @property indexSize 同期の後の索引の入口の数
 */
public data class SyncReport(
    val mode: SyncMode,
    val rebuiltApps: Int,
    val removedPackages: Int,
    val removedProfiles: Int,
    val analyzerLoadMillis: Long,
    val totalMillis: Long,
    val indexSize: Int,
    val genreMillis: Long = 0,
    val genreFailed: Boolean = false,
    val failedPackages: Int = 0,
    val failedProfiles: Int = 0,
)

/**
 * 端末と保存した索引を合わせ、失敗しても最後の正常な写しを保つ。
 * 個人用は変更番号、仕事用は毎回の APK 指紋と入口一覧で変更を見つける。
 * ジャンルは APK によらず全体を判定する。計算は書き手の外、保存と反映は書き手で行う。
 * 何を読み直し何を消すかの規則は [SyncPlanner] にあり、ここは端末の読み取り・キーの生成・保存・知らせの消費を受け持つ。
 * Callback の登録は Application が所有し、背景では通知だけを溜める。
 * [sync] は完了を待つため、画面のスレッドや書き手から呼ばない。
 */
public class IndexSync internal constructor(
    private val store: IndexStore,
    private val device: DeviceApps,
    generatorFactory: () -> SearchKeyGenerator,
    private val analyzer: ReadingAnalyzer,
    private val launcherApps: LauncherApps?,
    private val writer: DatabaseWriter = DatabaseWriter(),
    private val profiles: ProfileReconciler? = null,
    private val profileSerialOf: (UserHandle) -> Long? = device::profileSerial,
) {
    /**
     * 端末の部品で作る。[analyzer] は、漢字の検索語に読みを付ける側（画面）と共有してよい。
     * ブランド表は最初の同期のときに裏のスレッドで読む。
     */
    public constructor(
        context: Context,
        database: LocalDatabase,
        analyzer: ReadingAnalyzer,
        writer: DatabaseWriter = DatabaseWriter(),
        profiles: ProfileReconciler = ProfileReconciler({ database.profiles }, ExistingProfiles(context)::serials),
    ) : this(
        store = database.index,
        device = AndroidDeviceApps(context.applicationContext),
        generatorFactory = { SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.loadDefault())) },
        analyzer = analyzer,
        launcherApps = context.getSystemService(LauncherApps::class.java),
        writer = writer,
        profiles = profiles,
    )

    private val generator by lazy(generatorFactory)

    // 計算同士だけを直列にする。書き手はこのロックを取らず、計算中の記録や削除を待たせない
    private val syncLock = Any()
    private val executor by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "piu-index-sync")
        }
    }
    private val listeners = CopyOnWriteArrayList<IndexListener>()

    private val statusListeners = CopyOnWriteArrayList<(IndexSyncStatus) -> Unit>()
    private val packageListeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()
    private val requestLock = Any()
    private val pendingPackages = LinkedHashMap<PackageRequest, Long>()
    private var notificationRevision = 0L
    private var scheduled = false
    private var requested = false

    @Volatile private var searchVisible = false

    /** 実行中・最後の結果・失敗。索引の写しとは別に保つ。 */
    @Volatile public var status: IndexSyncStatus = IndexSyncStatus()
        private set

    /** 状態の受け取り手。通知は同期のスレッドから届く。 */
    public fun addStatusListener(listener: (IndexSyncStatus) -> Unit) {
        statusListeners += listener
    }

    /** 状態の受け取りを終える。 */
    public fun removeStatusListener(listener: (IndexSyncStatus) -> Unit) {
        statusListeners -= listener
    }

    /** アイコンの失効など、パッケージの変化を受ける。 */
    public fun addPackageListener(listener: (Set<String>) -> Unit) {
        packageListeners += listener
    }

    /** パッケージの変化の受け取りを終える。 */
    public fun removePackageListener(listener: (Set<String>) -> Unit) {
        packageListeners -= listener
    }

    /** 画面が背景にある間は Callback だけで作り直さない。 */
    public fun setSearchVisible(visible: Boolean) {
        searchVisible = visible
    }

    /**
     * 通知元のプロファイルを成功まで保持し、同期中に届いた新しい通知を古い完了で消さない。
     *
     * @param packages 変更を知らせられたパッケージ
     * @param user Callback の通知元。省略した場合は全プロファイルを指定する
     */
    public fun packagesChanged(
        packages: Set<String>,
        user: UserHandle? = null,
    ) {
        enqueuePackages(packages, user)
        packageListeners.forEach { it(packages) }
        if (searchVisible) requestSync()
    }

    private fun enqueuePackages(
        packages: Set<String>,
        user: UserHandle? = null,
    ) {
        // 取得できたシリアル番号を保ち、ユーザー id が再利用されても通知先を取り違えない
        val serial = user?.let(::readProfileSerial)
        synchronized(requestLock) {
            packages.forEach { pendingPackages[PackageRequest(it, user, serial)] = ++notificationRevision }
        }
    }

    private fun readProfileSerial(user: UserHandle): Long? =
        try {
            profileSerialOf(user)?.takeIf { it >= 0 }
        } catch (_: RuntimeException) {
            // 番号の取得失敗で通知を捨てず、次の同期でも通知元から解決する
            null
        }

    init {
        profiles?.addRemovalListener(::profilesRemoved)
    }

    // 書き手の中でだけ読み書きする。計算には変更されない Snapshot を渡す
    private var loaded = false
    private var apps: List<StoredApp> = emptyList()
    private var state: SyncState? = null
    private var revision = 0L

    /** 今のメモリ上の索引。保存した索引を読む前は空。 */
    @Volatile
    public var index: SearchIndex = SearchIndex.EMPTY
        private set

    /** [listener] を足す。足したときに今の索引をすぐには渡さないので、要るなら [index] を読む。 */
    public fun addListener(listener: IndexListener) {
        listeners += listener
    }

    /** [listener] を外す。 */
    public fun removeListener(listener: IndexListener) {
        listeners -= listener
    }

    /** 保存した索引を読み、端末と合わせる。裏のスレッドで行い、すぐ戻る。 */
    public fun requestSync() {
        synchronized(requestLock) {
            requested = true
            if (scheduled) return
            scheduled = true
            executor.execute(::drainRequests)
        }
    }

    /**
     * 保存した索引をまだ読んでいなければ読み、端末と合わせる。終わるまで戻らないので、画面のスレッドや書き手から呼ばない。
     *
     * @param packages 変更の番号に出なくても作り直すパッケージ（`LauncherApps.Callback` で知ったもの）
     */
    public fun sync(packages: Set<String> = emptySet()): SyncReport {
        check(!writer.isCurrentThread) { "索引の計算は書き手の外から呼ぶ" }
        enqueuePackages(packages)
        return synchronized(syncLock) {
            updateStatus(status.copy(running = true))
            try {
                val notifications = synchronized(requestLock) { pendingPackages.toMap() }
                val result = syncSerial(notifications)
                updateStatus(
                    IndexSyncStatus(
                        false,
                        result,
                        if (result.failedPackages > 0 || result.failedProfiles > 0 ||
                            result.genreFailed
                        ) {
                            SyncFailure.READ
                        } else {
                            null
                        },
                    ),
                )
                result
            } catch (e: RuntimeException) {
                updateStatus(status.copy(running = false, failure = SyncFailure.SYNC))
                throw e
            }
        }
    }

    private fun updateStatus(next: IndexSyncStatus) {
        status = next
        statusListeners.forEach { it(next) }
    }

    private fun drainRequests() {
        while (true) {
            synchronized(requestLock) {
                if (!requested) {
                    scheduled = false
                    return
                }
                requested = false
            }
            try {
                sync()
            } catch (e: RuntimeException) {
                Log.w(TAG, "索引の同期に失敗した: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun syncSerial(notifications: Map<PackageRequest, Long>): SyncReport {
        val started = SystemClock.elapsedRealtime()
        val removedProfiles = HashSet<Long>()
        var analyzerMillis = 0L
        repeat(MAX_SYNC_ATTEMPTS) {
            val before =
                writer.run {
                    removedProfiles += profiles?.reconcile().orEmpty()
                    val justLoaded = loadOnWriter()
                    Snapshot(apps, state, revision, profiles?.snapshot(), justLoaded)
                }
            if (before.justLoaded) {
                val restoredIndex = searchIndex(before.apps)
                writer.run {
                    // 読み戻した索引の組み立て中に削除された場合も、古い写しを戻さない
                    if (revision == before.revision) publish(restoredIndex)
                }
            }
            val deviceState = readDeviceState()
            val round = plan(before, deviceState, notifications.keys)
            val plan = round.sync
            analyzerMillis += warmUpIfNeeded(plan.rebuild)
            val failed = plan.failedPackages.toMutableSet()
            val built = build(plan.rebuild, failed)
            val replaced = built.mapTo(HashSet()) { it.key } + plan.removedPackages
            var next = before.apps.filter { it.key !in replaced && before.isPresent(it.app.id.profileSerial) } + built
            // 旧索引を残した場合も指紋を消し、番号が進んだ後に必ず読み直す
            next = next.map { if (it.key in failed || it.app.id.profileSerial !in plan.readable) it.copy(apkFingerprint = null) else it }
            val previousById = before.apps.associateBy { it.app.id }
            next =
                next.map { stored ->
                    val genres = plan.genres?.get(stored.app.id)
                    if (stored.app.id.profileSerial !in plan.readable) {
                        stored
                    } else if (plan.genres == null) {
                        val oldGenres =
                            previousById[stored.app.id]
                                ?.app
                                ?.keys
                                .orEmpty()
                                .filter { it.kind == SearchKeyKind.GENRE }
                        stored.copy(app = stored.app.copy(keys = stored.app.keys.filter { it.kind != SearchKeyKind.GENRE } + oldGenres))
                    } else {
                        val keys =
                            stored.app.keys.filter { it.kind != SearchKeyKind.GENRE } +
                                genres.orEmpty().map { SearchKey(NormalizedText(it.value), SearchKeyKind.GENRE) }
                        if (keys == stored.app.keys) stored else stored.copy(app = stored.app.copy(keys = keys))
                    }
                }
            val changed = next != before.apps
            val nextIndex = if (changed) searchIndex(next) else null
            // 計算中に端末の前提が変わった計画は保存せず、計算し直す
            if (readDeviceState() != deviceState) return@repeat
            val report =
                writer.run {
                    removedProfiles += profiles?.reconcile().orEmpty()
                    if (revision != before.revision || profiles?.snapshot() != before.profiles) return@run null
                    if (!store.writeIfCurrent(before.state) { save(before.apps, plan, next) }) {
                        // 保存済みの版や状態が変わったので、古い計画を当てずに保存先から読み直す
                        loaded = false
                        return@run null
                    }
                    apps = next
                    state = plan.state
                    revision++
                    val invalidated = plan.changedPackages
                    if (invalidated.isNotEmpty()) packageListeners.forEach { it(invalidated) }
                    if (nextIndex != null) publish(nextIndex)
                    synchronized(requestLock) {
                        notifications.forEach { (request, generation) ->
                            val targets = round.requestTargets[request]
                            val completed =
                                !targets.isNullOrEmpty() &&
                                    targets.all { key ->
                                        key !in failed && (key.profileSerial in plan.readable || !before.isPresent(key.profileSerial))
                                    }
                            if (completed && pendingPackages[request] == generation) pendingPackages.remove(request)
                        }
                    }
                    SyncReport(
                        mode = plan.mode,
                        rebuiltApps = built.size,
                        removedPackages = plan.removedPackages.size,
                        removedProfiles = removedProfiles.size,
                        analyzerLoadMillis = analyzerMillis,
                        totalMillis = SystemClock.elapsedRealtime() - started,
                        indexSize = apps.size,
                        genreMillis = round.genreMillis,
                        genreFailed = plan.genres == null,
                        failedPackages = failed.size,
                        failedProfiles = plan.failedProfiles,
                    ).also { Log.i(TAG, "索引を同期した: $it") }
                }
            if (report != null) return report
        }
        // 前提が変わり続けて保存できない。知らせは残るので、次の要求で最初からやり直す
        throw SyncNotSettledException()
    }

    private fun readDeviceState(): DeviceState = DeviceState(device.bootCount(), device.locales(), device.buildFingerprint())

    /**
     * Application の部品から Callback を登録する。背景では通知を保持し、前面でのみ同期を頼む。
     * 2 度呼んでも 1 つしか登録しない。
     */
    public fun startWatching() {
        val apps = launcherApps ?: return
        synchronized(callback) {
            if (watching) return
            watching = true
        }
        apps.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    /** [startWatching] で始めた受け取りをやめる。 */
    public fun stopWatching() {
        val apps = launcherApps ?: return
        synchronized(callback) {
            if (!watching) return
            watching = false
        }
        apps.unregisterCallback(callback)
    }

    private var watching = false

    private val callback =
        object : LauncherApps.Callback() {
            override fun onPackageRemoved(
                packageName: String,
                user: UserHandle,
            ) = packagesChanged(setOf(packageName), user)

            override fun onPackageAdded(
                packageName: String,
                user: UserHandle,
            ) = packagesChanged(setOf(packageName), user)

            override fun onPackageChanged(
                packageName: String,
                user: UserHandle,
            ) = packagesChanged(setOf(packageName), user)

            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = packagesChanged(packageNames.toSet(), user)

            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = packagesChanged(packageNames.toSet(), user)
        }

    /** 頼んだ同期がすべて終わるまで待つ。テストで、データベースを閉じる前に使う。 */
    internal fun awaitIdle() {
        executor.submit { synchronized(syncLock) {} }.get()
        writer.run {}
    }

    private fun loadOnWriter(): Boolean {
        if (loaded) return false
        val stored = store.load()
        apps = stored.apps
        state = stored.state
        loaded = true
        revision++
        return true
    }

    // 端末の読み取りと SyncPlanner の段を交互に行う。読む範囲は前の段の結果で決まる
    private fun plan(
        snapshot: Snapshot,
        deviceState: DeviceState,
        requested: Set<PackageRequest>,
    ): Plan {
        val scope = SyncPlanner.scope(snapshot.state, deviceState)
        val changes = device.changedPackages(scope.since)
        val profiles = device.stampProfiles(withUpdateTime = !scope.sequenceUsable)
        val requestTargets = requestTargets(requested, profiles.keys + snapshot.apps.map { it.app.id.profileSerial })
        val targets =
            SyncPlanner.targets(
                scope,
                snapshot.apps,
                snapshot::isPresent,
                profiles,
                changes.packages,
                requestTargets.values.flatten().toHashSet(),
            )
        val genreStarted = SystemClock.elapsedRealtime()
        val genres =
            try {
                device.genres(targets.stamps)
            } catch (_: RuntimeException) {
                null
            }
        val genreMillis = SystemClock.elapsedRealtime() - genreStarted
        val candidates = if (targets.dirty.isEmpty()) emptyMap() else device.readProfiles(targets.dirtyPackages)
        val plan = SyncPlanner.plan(scope, deviceState, targets, snapshot::isPresent, changes.sequence, genres, candidates)
        return Plan(plan, requestTargets, genreMillis)
    }

    // 知らせごとの対象の組。通知元のないものは、一覧か索引にあるすべてのプロファイルを指す。
    // 番号を解決できない知らせは対象を持たず、消費されずに次の同期へ残る
    private fun requestTargets(
        requested: Set<PackageRequest>,
        knownProfiles: Set<Long>,
    ): Map<PackageRequest, Set<PackageKey>> =
        requested
            .mapNotNull { request ->
                val serials =
                    if (request.user == null) {
                        knownProfiles
                    } else {
                        (request.profileSerial ?: readProfileSerial(request.user))?.let(::setOf) ?: return@mapNotNull null
                    }
                request to serials.mapTo(HashSet()) { PackageKey(it, request.packageName) }
            }.toMap()

    private fun warmUpIfNeeded(rebuild: List<InstalledApp>?): Long {
        if (rebuild.isNullOrEmpty() || analyzer !is KuromojiReadingAnalyzer || analyzer.isLoaded) return 0
        val started = SystemClock.elapsedRealtime()
        analyzer.warmUp()
        return SystemClock.elapsedRealtime() - started
    }

    private fun build(
        read: List<InstalledApp>,
        failed: MutableSet<PackageKey>,
    ): List<StoredApp> =
        read.groupBy { it.key }.flatMap { (key, entries) ->
            try {
                val complete = entries.all { it.complete }
                entries.map {
                    StoredApp(
                        IndexedApp(it.entry.id, it.entry.label, generator.generate(it.entry)),
                        it.packageUpdatedAt,
                        if (complete) it.apkFingerprint else null,
                    )
                }
            } catch (_: RuntimeException) {
                failed += key
                emptyList()
            }
        }

    private fun save(
        before: List<StoredApp>,
        plan: SyncPlan,
        next: List<StoredApp>,
    ) {
        if (state == null) {
            store.replaceAll(next, plan.state)
        } else {
            val old = before.groupBy { it.key }
            val new = next.groupBy { it.key }
            val changed = (old.keys + new.keys).filterTo(HashSet()) { old[it] != new[it] }
            val genreOnly =
                changed.filterTo(HashSet()) { key ->
                    fun withoutGenres(apps: List<StoredApp>?) =
                        apps?.map { stored ->
                            stored.copy(app = stored.app.copy(keys = stored.app.keys.filter { it.kind != SearchKeyKind.GENRE }))
                        }
                    old[key] != null && withoutGenres(old[key]) == withoutGenres(new[key])
                }
            store.update(next.filter { it.key in changed && it.key !in genreOnly }, changed - new.keys, state = plan.state)
            store.updateGenres(next.filter { it.key in genreOnly })
        }
    }

    private fun profilesRemoved(serials: Set<Long>) {
        val next = apps.filter { it.app.id.profileSerial !in serials }
        if (next.size == apps.size) return
        apps = next
        revision++
        publish()
    }

    private fun searchIndex(apps: List<StoredApp>): SearchIndex =
        if (apps.isEmpty()) SearchIndex.EMPTY else SearchIndex(apps.map { it.app })

    private fun publish(next: SearchIndex = searchIndex(apps)) {
        index = next
        listeners.forEach { it.onIndexChanged(next) }
    }

    private data class Snapshot(
        val apps: List<StoredApp>,
        val state: SyncState?,
        val revision: Long,
        val profiles: ProfileSnapshot?,
        val justLoaded: Boolean,
    ) {
        fun isPresent(serial: Long): Boolean = profiles?.isPresent(serial) != false
    }

    /** 番号をまだ取得できない通知も、通知元を持って次回に解決する。user が null なら全プロファイル指定。 */
    private data class PackageRequest(
        val packageName: String,
        val user: UserHandle?,
        val profileSerial: Long?,
    )

    /** 計画と、知らせの消費・記録に使う付随の値。 */
    private class Plan(
        val sync: SyncPlan,
        val requestTargets: Map<PackageRequest, Set<PackageKey>>,
        val genreMillis: Long,
    )

    private companion object {
        const val TAG = "PiuIndexSync"
    }
}

/**
 * 同期 1 回で計算し直す上限。計算し直すのは、計算中に端末の言語・ビルド・再起動の回数が変わったとき、
 * 書き手で索引かプロファイルが変わったとき（削除・プロファイルの削除）、保存先の版や状態が変わって読み直すとき。
 * どれも 1 回の出来事なので普段は 1〜2 回で終わる。1 回が全件の作り直しで数秒かかり得るので、重なりへの余裕を見て 5 回で打ち切る。
 */
internal const val MAX_SYNC_ATTEMPTS = 5

/** [MAX_SYNC_ATTEMPTS] 回計算しても前提が落ち着かず、保存できなかった。状態は [SyncFailure.SYNC] になる。 */
internal class SyncNotSettledException : IllegalStateException("同期の前提が変わり続け、保存できなかった")

/** 利用者に詳細な識別子を出さずに知らせる同期の失敗。 */
public enum class SyncFailure {
    /** 一部の材料を読み取れず、次回に再試行する。 */
    READ,

    /** 同期全体が失敗した。最後の索引を保つ。 */
    SYNC,
}

/**
 * 索引と独立した同期の状態。
 * @property running 同期の計算か保存を実行中か
 * @property lastResult 最後に完了した同期。部分的な失敗は件数で分かる
 * @property failure 最後の失敗。成功すると消える
 */
public data class IndexSyncStatus(
    val running: Boolean = false,
    val lastResult: SyncReport? = null,
    val failure: SyncFailure? = null,
)
