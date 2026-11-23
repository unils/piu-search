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
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.KeyForm
import jp.piu.search.core.search.KeyGenerationException
import jp.piu.search.core.search.KeyInput
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.TextNormalizer
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 索引の受け取り手。索引を差し替えるたびに、保存後の共通の書き手から呼ばれる。
 */
internal fun interface IndexListener {
    /** 新しい索引 [index] に差し替えた。 */
    public fun onIndexChanged(index: SearchIndex)
}

/** 同期で取った方法（[SyncReport.mode]）。 */
internal enum class SyncMode {
    /** 保存した索引がない・索引の版か端末の言語・OS のビルドが変わった。すべての入口を作り直した。 */
    FULL_REBUILD,

    /** 再起動した（変更の番号が使えない）。すべての入口の更新の時刻を、保存した索引と比べた。 */
    FULL_REVIEW,

    /** 個人用の変更番号と仕事用の APK 指紋で差分を取った。 */
    CHANGES,
}

/** 最後の同期の結果（architecture.md の「知らせ」）。上から最初に当てはまるもの。 */
internal enum class SyncOutcome {
    /** 同期全体の失敗。何も更新できなかった。 */
    FAILED,

    /** あるプロファイルが `FAILED`、またはジャンルの判定が失敗した。一部を古いまま残した。 */
    PARTIAL,

    /** 同期の後に欠けの行がある（読めないプロファイルの保った行を含む）。 */
    ISSUES,

    /** 上のどれでもない。 */
    OK,
}

/**
 * 欠けの行の区分ごとの数（同期の後の表の全体）。
 *
 * @property transient 一時的
 * @property retryPending 一時的のうち、次の同期で 1 回読み直す（`retry_once`）もの
 * @property deterministic 決定的
 * @property suspectedBug 不具合の疑い
 */
internal data class IssueCounts(
    val transient: Int = 0,
    val retryPending: Int = 0,
    val deterministic: Int = 0,
    val suspectedBug: Int = 0,
) {
    /** 行の数。 */
    val total: Int get() = transient + deterministic + suspectedBug

    internal companion object {
        fun of(issues: Collection<MaterialIssue>): IssueCounts {
            val byCategory = issues.groupBy { it.category }
            return IssueCounts(
                transient = byCategory[IssueCategory.TRANSIENT].orEmpty().size,
                retryPending = byCategory[IssueCategory.TRANSIENT].orEmpty().count { it.retryOnce },
                deterministic = byCategory[IssueCategory.DETERMINISTIC].orEmpty().size,
                suspectedBug = byCategory[IssueCategory.SUSPECTED_BUG].orEmpty().size,
            )
        }
    }
}

/**
 * 同期 1 回の結果。記録・計器テスト・「索引の状態」に使う。識別子は持たず、件数・理由・プロファイルの番号だけを持つ。
 *
 * @property mode 取った方法
 * @property rebuiltApps キーを作り直した入口の数（正規化だけのキーの代わりの行を含む）
 * @property removedPackages 索引から消した組の数
 * @property removedProfiles 索引から消したプロファイルの数
 * @property analyzerLoadMillis 形態素解析器の辞書の読み込みにかかった時間。読み込まなかったら 0
 * @property totalMillis 同期全体の時間（保存した索引の読み込みを含む）
 * @property indexSize 同期の後の索引の入口の数
 * @property genreMillis 全体のジャンル判定にかかった時間
 * @property genreFailure ジャンルの問い合わせの例外の理由。成功なら null
 * @property readPackages 詳細を読みに行った組の数（結果を捨てた組を含む）
 * @property profileResults プロファイルのシリアル番号ごとの結果
 * @property issueCounts 同期の後の欠けの行の区分ごとの数
 * @property archivedPackages 同期の後の索引の、アーカイブ済みの組の数（保存した `archived` から数える）
 * @property failureCauses この同期で起きた失敗の理由ごとの件数（欠けの行を書いた段・プロファイル・ジャンル）
 */
internal data class SyncReport(
    val mode: SyncMode,
    val rebuiltApps: Int,
    val removedPackages: Int,
    val removedProfiles: Int,
    val analyzerLoadMillis: Long,
    val totalMillis: Long,
    val indexSize: Int,
    val genreMillis: Long = 0,
    val genreFailure: String? = null,
    val readPackages: Int = 0,
    val profileResults: Map<Long, ProfileReadStatus> = emptyMap(),
    val issueCounts: IssueCounts = IssueCounts(),
    val archivedPackages: Int = 0,
    val failureCauses: Map<String, Int> = emptyMap(),
) {
    /** ジャンルの判定が失敗したか。 */
    val genreFailed: Boolean get() = genreFailure != null

    /** 一覧か詳細の問い合わせが例外になったプロファイル。一時停止は含めない。 */
    val failedProfiles: Set<Long> get() = profileResults.filterValues { it == ProfileReadStatus.FAILED }.keys

    /** 同期が最後まで終わったときの結果。一時停止は結果を下げない。 */
    val outcome: SyncOutcome
        get() =
            when {
                failedProfiles.isNotEmpty() || genreFailed -> SyncOutcome.PARTIAL
                issueCounts.total > 0 -> SyncOutcome.ISSUES
                else -> SyncOutcome.OK
            }
}

/**
 * 端末と保存した索引を合わせ、失敗しても最後の正常な写しを保つ。
 * 個人用は変更番号、仕事用は毎回の APK 指紋と入口一覧で変更を見つける。
 * ジャンルは APK によらず全体を判定する。計算は書き手の外、保存と反映は書き手で行う。
 * 何を読み直し何を消すかの規則は [SyncPlanner] にあり、ここは端末の読み取り・キーの生成・保存・知らせの消費を受け持つ。
 * Callback の登録は Application が所有し、背景では通知だけを溜める。
 * [sync] は完了を待つため、画面のスレッドや書き手から呼ばない。
 *
 * 読んだ組は成否によらず一覧の観測（入口の集合・アーカイブ済みか・鍵）と欠けの行を書き、鍵が変わるまで読み直さない
 * （architecture.md の「読み直しの規則」）。一覧にある入口は、詳細やキーの生成が失敗しても必ず索引に入れる。
 */
internal class IndexSync internal constructor(
    private val store: IndexStore,
    private val device: DeviceApps,
    generatorFactory: () -> SearchKeyGenerator,
    private val analyzer: ReadingAnalyzer,
    private val launcherApps: LauncherApps?,
    private val writer: DatabaseWriter = DatabaseWriter(),
    private val profiles: ProfileReconciler? = null,
    private val profileSerialOf: (UserHandle) -> Long? = device::profileSerial,
    private val appVersionCode: Int = 0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val warmUpAnalyzer: () -> Boolean = { warmUpKuromoji(analyzer) },
) {
    /**
     * 端末の部品で作る。[analyzer] は、漢字の検索語に読みを付ける側（画面）と共有してよい。
     * ブランド表は最初の同期のときに裏のスレッドで読む。
     *
     * @param appVersionCode 今の Piu Search の `versionCode`。保存した版と違えば、欠けの行のある組を読み直す
     */
    public constructor(
        context: Context,
        database: LocalDatabase,
        analyzer: ReadingAnalyzer,
        writer: DatabaseWriter = DatabaseWriter(),
        profiles: ProfileReconciler = ProfileReconciler({ database.profiles }, ExistingProfiles(context)::serials),
        appVersionCode: Int,
    ) : this(
        store = database.index,
        device = AndroidDeviceApps(context.applicationContext),
        generatorFactory = { SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.loadDefault())) },
        analyzer = analyzer,
        launcherApps = context.getSystemService(LauncherApps::class.java),
        writer = writer,
        profiles = profiles,
        appVersionCode = appVersionCode,
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
     * 通知元のプロファイルを、結果を保存するまで保持し、同期中に届いた新しい通知を古い完了で消さない。
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
    private var issues: List<MaterialIssue> = emptyList()
    private var state: SyncState? = null
    private var revision = 0L

    /** 今のメモリ上の索引。保存した索引を読む前は空。 */
    @Volatile
    public var index: SearchIndex = SearchIndex.EMPTY
        private set

    /** 保存した材料の欠けの行（「索引の状態」の画面に出す）。保存した索引を読む前は空。保存と同じ書き手の仕事で差し替える。 */
    @Volatile
    public var materialIssues: List<MaterialIssue> = emptyList()
        private set

    /** 最後に保存できた同期の時刻（ミリ秒）。保存した索引を読む前か、保存したことがなければ null。表示と報告のためだけ。 */
    @Volatile
    public var lastSavedAt: Long? = null
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
                updateStatus(IndexSyncStatus(false, result, if (result.failedProfiles.isNotEmpty()) SyncFailure.PROFILE else null))
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
                Log.w(TAG, "索引の同期に失敗した: ${MaterialCauses.causeOf(e)}")
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
                    Snapshot(apps, issues, state, revision, profiles?.snapshot(), justLoaded)
                }
            if (before.justLoaded) {
                val restoredIndex = searchIndex(before.apps)
                writer.run {
                    // 読み戻した索引の組み立て中に削除された場合も、古い写しを戻さない
                    if (revision == before.revision) publish(restoredIndex)
                }
            }
            val deviceState = readDeviceState()
            val now = clock()
            val round = plan(before, deviceState, notifications.keys, utcDay(now))
            val plan = round.sync
            // 辞書の初期化の失敗の単位は、キーを作る入口。同期全体を止めず、その入口を KEYS の欠けにして旧索引の行か代わりの行で残す
            // （architecture.md の「材料の欠け」）。Error は捕まえない
            val warmUpFailure =
                try {
                    analyzerMillis += warmUpIfNeeded(plan.packages)
                    null
                } catch (e: RuntimeException) {
                    e
                }
            val built = plan.packages.map { build(it, before, warmUpFailure) }
            val next = nextApps(before, plan, built)
            val nextIssues = nextIssues(before, plan, built, next)
            val changed = next != before.apps
            val nextIndex = if (changed) searchIndex(next) else null
            val change = change(before, plan, next, nextIssues, now)
            // 計算中に端末の前提が変わった計画は保存せず、計算し直す
            if (readDeviceState() != deviceState) return@repeat
            val report =
                writer.run {
                    removedProfiles += profiles?.reconcile().orEmpty()
                    if (revision != before.revision || profiles?.snapshot() != before.profiles) return@run null
                    if (!store.saveIfCurrent(before.state, change)) {
                        // 保存済みの版や状態が変わったので、古い計画を当てずに保存先から読み直す
                        loaded = false
                        return@run null
                    }
                    apps = next
                    issues = nextIssues
                    state = plan.state
                    revision++
                    materialIssues = nextIssues
                    lastSavedAt = now
                    if (plan.changedPackages.isNotEmpty()) packageListeners.forEach { it(plan.changedPackages) }
                    if (nextIndex != null) publish(nextIndex)
                    consume(notifications, round.requestTargets, plan, before)
                    report(plan, round, built, nextIssues, removedProfiles.size, analyzerMillis, started)
                        .also { Log.i(TAG, "索引を同期した: $it") }
                }
            if (report != null) return report
        }
        // 前提が変わり続けて保存できない。知らせは残るので、次の要求で最初からやり直す
        throw SyncNotSettledException()
    }

    // 結果を保存した対象（読んで書いた組と、COMPLETE の一覧で不在を確かめた組）だけの知らせを消費する。
    // 失敗した組の知らせを残すと、決定的な欠けの組を毎回読み直すため。読めないプロファイルの知らせは残す
    private fun consume(
        notifications: Map<PackageRequest, Long>,
        requestTargets: Map<PackageRequest, Set<PackageKey>>,
        plan: SyncPlan,
        before: Snapshot,
    ) {
        synchronized(requestLock) {
            notifications.forEach { (request, generation) ->
                val targets = requestTargets[request]
                val saved =
                    !targets.isNullOrEmpty() &&
                        targets.all { it.profileSerial in plan.completed || !before.isPresent(it.profileSerial) }
                if (saved && pendingPackages[request] == generation) pendingPackages.remove(request)
            }
        }
    }

    private fun report(
        plan: SyncPlan,
        round: Plan,
        built: List<BuiltPackage>,
        nextIssues: List<MaterialIssue>,
        removedProfiles: Int,
        analyzerMillis: Long,
        started: Long,
    ): SyncReport {
        val causes = HashMap<String, Int>()
        built.flatMap { it.written }.forEach { causes.merge(it.cause, 1, Int::plus) }
        plan.profileFailures.values.forEach { causes.merge(it, 1, Int::plus) }
        round.genreFailure?.let { causes.merge(it, 1, Int::plus) }
        return SyncReport(
            mode = plan.mode,
            rebuiltApps = built.sumOf { it.rebuilt },
            removedPackages = plan.removed.size,
            removedProfiles = removedProfiles,
            analyzerLoadMillis = analyzerMillis,
            totalMillis = SystemClock.elapsedRealtime() - started,
            indexSize = apps.size,
            genreMillis = round.genreMillis,
            genreFailure = round.genreFailure,
            readPackages = round.readCount,
            profileResults = plan.profileResults,
            issueCounts = IssueCounts.of(nextIssues),
            archivedPackages = apps.filter { it.archived }.mapTo(HashSet()) { it.key }.size,
            failureCauses = causes,
        )
    }

    private fun readDeviceState(): DeviceState =
        DeviceState(device.bootCount(), device.locales(), device.buildFingerprint(), appVersionCode)

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

    /** まだ消費していない知らせの数。テストで消費の条件を確かめる。 */
    internal fun pendingNotificationCount(): Int = synchronized(requestLock) { pendingPackages.size }

    /** 頼んだ同期がすべて終わるまで待つ。テストで、データベースを閉じる前に使う。 */
    internal fun awaitIdle() {
        executor.submit { synchronized(syncLock) {} }.get()
        writer.run {}
    }

    private fun loadOnWriter(): Boolean {
        if (loaded) return false
        val stored = store.load()
        apps = stored.apps
        issues = stored.issues
        state = stored.state
        materialIssues = stored.issues
        lastSavedAt = stored.lastSavedAt
        loaded = true
        revision++
        return true
    }

    // 端末の読み取りと SyncPlanner の段を交互に行う。読む範囲は前の段の結果で決まる
    private fun plan(
        snapshot: Snapshot,
        deviceState: DeviceState,
        requested: Set<PackageRequest>,
        today: String,
    ): Plan {
        val scope = SyncPlanner.scope(snapshot.state, deviceState)
        val changes = device.changedPackages(scope.since)
        val listing = device.stampProfiles(withUpdateTime = !scope.sequenceUsable)
        val requestTargets = requestTargets(requested, listing.keys + snapshot.apps.map { it.app.id.profileSerial })
        val targets =
            SyncPlanner.targets(
                scope,
                snapshot.apps,
                snapshot.issues,
                snapshot::isPresent,
                listing,
                changes.packages,
                requestTargets.values.flatten().toHashSet(),
            )
        val readTargets = targets.readTargets
        // 保存する鍵は一覧の観測だけ（architecture.md の「鍵の比べ方」）。時刻を引かなかった一覧では、読む組の鍵をここで
        // （ジャンルと詳細を読む前に）一度に観測して確定し、保存まで保つ。組ごとに読む直前で観測すると、それまでの変化を見逃す
        val listedKeys =
            when {
                readTargets.isEmpty() -> emptyMap()
                scope.sequenceUsable -> device.listedKeys(readTargets)
                else -> readTargets.mapValues { (_, stamps) -> ApkKeys(stamps.first().packageUpdatedAt, stamps.first().apkFingerprint) }
            }
        val genreStarted = SystemClock.elapsedRealtime()
        // 失敗の単位は同期 1 回のジャンルの判定。前のジャンルのキーを保ち、型は報告に残す
        var genreFailure: String? = null
        val genres =
            try {
                device.genres(targets.stamps)
            } catch (e: RuntimeException) {
                genreFailure = MaterialCauses.causeOf(e)
                null
            }
        val genreMillis = SystemClock.elapsedRealtime() - genreStarted
        val candidates = if (readTargets.isEmpty()) emptyMap() else device.readPackages(readTargets, listedKeys)
        val plan =
            SyncPlanner.plan(
                scope,
                deviceState,
                targets,
                snapshot.apps,
                snapshot.issues,
                snapshot::isPresent,
                changes.sequence,
                genres,
                candidates,
                today,
                readProfileSerial(Process.myUserHandle()),
            )
        return Plan(plan, requestTargets, genreMillis, genreFailure, readTargets.size)
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

    private fun warmUpIfNeeded(packages: List<PackagePlan>): Long {
        // 正規化だけの代わりの行は読みを作らないので、辞書は要らない
        if (packages.none { !it.substitute && it.build.isNotEmpty() }) return 0
        val started = SystemClock.elapsedRealtime()
        return if (warmUpAnalyzer()) SystemClock.elapsedRealtime() - started else 0
    }

    // 組 1 つの行を作る。失敗の単位は入口 1 つのキーの生成で、ほかの入口とほかの組を止めない。
    // 一覧の入口は必ず行にする（キーを作れなければ旧索引の行、なければ正規化だけのキー、それも作れなければキー 0 個）。
    // 辞書の初期化が失敗した回（warmUpFailure）は、読みを作る入口のキーを作らず、その例外で KEYS の欠けにする。
    // 生成に進むと、入口ごとに辞書の読み込みを試し直すため
    private fun build(
        plan: PackagePlan,
        before: Snapshot,
        warmUpFailure: RuntimeException?,
    ): BuiltPackage {
        val oldById = before.apps.filter { it.key == plan.key }.associateBy { it.app.id }
        var keysFailure: Pair<AppEntry, RuntimeException>? = null
        val rows = ArrayList<StoredApp>()
        var rebuilt = 0
        for (entry in plan.build) {
            val keys =
                if (!plan.substitute && warmUpFailure != null) {
                    keysFailure = keysFailure ?: (entry to warmUpFailure)
                    null
                } else {
                    try {
                        rebuilt++
                        if (plan.substitute) substituteKeys(entry.label) else generator.generate(entry)
                    } catch (e: RuntimeException) {
                        rebuilt--
                        keysFailure = keysFailure ?: (entry to e)
                        null
                    }
                }
            rows +=
                if (keys != null) {
                    StoredApp(IndexedApp(entry.id, entry.label, keys), null)
                } else {
                    oldById[entry.id] ?: StoredApp(IndexedApp(entry.id, entry.label, fallbackKeys(entry.label)), null)
                }
        }
        rows += plan.kept
        val observed =
            rows.map {
                it.copy(
                    packageUpdatedAt = plan.keys.updatedAt,
                    apkFingerprint = plan.keys.fingerprint,
                    archived = plan.read.archived,
                    rebuildPending = false,
                )
            }
        val previous = before.issues.filter { it.key == plan.key }.associateBy { it.stage }
        val decisions = plan.issues.toMutableMap()
        decisions[MaterialStage.KEYS] =
            keysFailure?.let { (entry, e) ->
                SyncPlanner.issue(
                    plan.key,
                    MaterialStage.KEYS,
                    ReadFailure.of(e),
                    InputSummary.of(entry, if (plan.substitute) KeyGenerationException(KeyInput.LABEL, 0, e) else e),
                    previous[MaterialStage.KEYS],
                    plan.retryOnly,
                    appVersionCode,
                    utcDay(clock()),
                )
            } ?: if (plan.substitute) IssueDecision.Keep else IssueDecision.Delete
        return BuiltPackage(plan.key, observed, decisions, previous, rebuilt)
    }

    // 読みの生成を通さない代わりの行のキー（architecture.md の「材料の欠け」）。表示名の厳密形と緩い形
    private fun substituteKeys(label: String): List<SearchKey> {
        val strict = TextNormalizer.strict(Normalizer.normalize(label, Normalizer.Form.NFKC))
        if (strict.isEmpty) return emptyList()
        val loose = TextNormalizer.loose(strict)
        return listOf(SearchKey(strict, SearchKeyKind.NAME)) +
            if (loose != strict) listOf(SearchKey(loose, SearchKeyKind.NAME, form = KeyForm.LOOSE)) else emptyList()
    }

    // キーの生成が例外になった新しい入口。代わりの行のキーまで例外ならキー 0 個の行にする（行があれば読み直しは止まる）
    private fun fallbackKeys(label: String): List<SearchKey> =
        try {
            substituteKeys(label)
        } catch (_: RuntimeException) {
            emptyList()
        }

    private fun nextApps(
        before: Snapshot,
        plan: SyncPlan,
        built: List<BuiltPackage>,
    ): List<StoredApp> {
        val replaced = built.mapTo(HashSet()) { it.key } + plan.removed
        var next = before.apps.filter { it.key !in replaced && before.isPresent(it.app.id.profileSerial) } + built.flatMap { it.rows }
        if (plan.pending.isNotEmpty()) next = next.map { if (it.key in plan.pending) it.copy(rebuildPending = true) else it }
        val previousById = before.apps.associateBy { it.app.id }
        return next.map { stored ->
            val genres = plan.genres?.get(stored.app.id)
            if (stored.app.id.profileSerial !in plan.completed) {
                stored
            } else if (plan.genres == null) {
                val oldGenres =
                    previousById[stored.app.id]
                        ?.app
                        ?.keys
                        .orEmpty()
                        .filter { it.kind == SearchKeyKind.GENRE }
                val keys = stored.app.keys.filter { it.kind != SearchKeyKind.GENRE } + oldGenres
                if (keys == stored.app.keys) stored else stored.copy(app = stored.app.copy(keys = keys))
            } else {
                val keys =
                    stored.app.keys.filter { it.kind != SearchKeyKind.GENRE } +
                        genres.orEmpty().map { SearchKey(NormalizedText(it.value), SearchKeyKind.GENRE) }
                if (keys == stored.app.keys) stored else stored.copy(app = stored.app.copy(keys = keys))
            }
        }
    }

    // 読んだ組の行を書き直し、次の索引にない組と消えたプロファイルの行を落とす。読めないプロファイルの行は保つ
    private fun nextIssues(
        before: Snapshot,
        plan: SyncPlan,
        built: List<BuiltPackage>,
        next: List<StoredApp>,
    ): List<MaterialIssue> {
        val byKey = before.issues.groupByTo(LinkedHashMap()) { it.key }
        for (pkg in built) byKey[pkg.key] = pkg.issues().toMutableList()
        val present = next.mapTo(HashSet()) { it.key }
        return byKey.values.flatten().filter { it.key in present && before.isPresent(it.key.profileSerial) }
    }

    private fun change(
        before: Snapshot,
        plan: SyncPlan,
        next: List<StoredApp>,
        nextIssues: List<MaterialIssue>,
        now: Long,
    ): IndexChange {
        val oldIssues = before.issues.associateBy { it.key to it.stage }
        val newIssues = nextIssues.associateBy { it.key to it.stage }
        val putIssues = nextIssues.filter { oldIssues[it.key to it.stage] != it }
        val deletedIssues = oldIssues.keys - newIssues.keys
        if (before.state == null) {
            return IndexChange(true, next, putIssues = putIssues, deletedIssues = deletedIssues, state = plan.state, savedAt = now)
        }
        val old = before.apps.groupBy { it.key }
        val new = next.groupBy { it.key }
        val changed = (old.keys + new.keys).filterTo(HashSet()) { old[it] != new[it] }
        val genreOnly =
            changed.filterTo(
                HashSet(),
            ) { old[it] != null && new[it] != null && withoutGenres(old[it]) == withoutGenres(new[it]) }
        val pendingOnly =
            changed.filterTo(HashSet()) { key ->
                val o = old[key]
                val n = new[key]
                o != null && n != null && o.map { it.copy(rebuildPending = true) } == n
            }
        return IndexChange(
            replaceAll = false,
            apps = next.filter { it.key in changed && it.key !in genreOnly && it.key !in pendingOnly },
            removed = changed - new.keys,
            genreOnly = next.filter { it.key in genreOnly },
            pending = pendingOnly,
            putIssues = putIssues,
            deletedIssues = deletedIssues,
            state = plan.state,
            savedAt = now,
        )
    }

    private fun withoutGenres(apps: List<StoredApp>?) =
        apps?.map { stored -> stored.copy(app = stored.app.copy(keys = stored.app.keys.filter { it.kind != SearchKeyKind.GENRE })) }

    // 書き手の中で呼ばれる（ProfileReconciler の削除の後）。欠けの行の写しも、索引と同じ境界で差し替える
    private fun profilesRemoved(serials: Set<Long>) {
        val next = apps.filter { it.app.id.profileSerial !in serials }
        val nextIssues = issues.filter { it.key.profileSerial !in serials }
        if (next.size == apps.size && nextIssues.size == issues.size) return
        apps = next
        issues = nextIssues
        materialIssues = nextIssues
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
        val issues: List<MaterialIssue>,
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
        val genreFailure: String?,
        val readCount: Int,
    )

    /** 組 1 つの次の行と、4 つの段の欠けの行の扱い。 */
    private class BuiltPackage(
        val key: PackageKey,
        val rows: List<StoredApp>,
        val decisions: Map<MaterialStage, IssueDecision>,
        val previous: Map<MaterialStage, MaterialIssue>,
        val rebuilt: Int,
    ) {
        /** この同期で書いた行（報告の件数に使う）。 */
        val written: List<MaterialIssue> get() = decisions.values.filterIsInstance<IssueDecision.Put>().map { it.issue }

        /** 組の次の行。保つ行も retry_once は下ろす（読んだ組は、まず組のすべての行で 0 に下ろす）。 */
        fun issues(): List<MaterialIssue> =
            MaterialStage.entries.mapNotNull { stage ->
                when (val decision = decisions[stage] ?: IssueDecision.Keep) {
                    IssueDecision.Delete -> null
                    is IssueDecision.Put -> decision.issue
                    IssueDecision.Keep -> previous[stage]?.copy(retryOnce = false)
                }
            }
    }

    private companion object {
        const val TAG = "PiuIndexSync"

        fun utcDay(millis: Long): String =
            Instant
                .ofEpochMilli(millis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString()
    }
}

/** Kuromoji の辞書をまだ読んでいなければ読む。読んだら真。辞書を読めなければ例外（`RuntimeException`）。 */
private fun warmUpKuromoji(analyzer: ReadingAnalyzer): Boolean {
    if (analyzer !is KuromojiReadingAnalyzer || analyzer.isLoaded) return false
    analyzer.warmUp()
    return true
}

/**
 * 同期 1 回で計算し直す上限。計算し直すのは、計算中に端末の言語・ビルド・再起動の回数が変わったとき、
 * 書き手で索引かプロファイルが変わったとき（削除・プロファイルの削除）、保存先の版や状態が変わって読み直すとき。
 * どれも 1 回の出来事なので普段は 1〜2 回で終わる。1 回が全件の作り直しで数秒かかり得るので、重なりへの余裕を見て 5 回で打ち切る。
 */
internal const val MAX_SYNC_ATTEMPTS = 5

/** [MAX_SYNC_ATTEMPTS] 回計算しても前提が落ち着かず、保存できなかった。状態は [SyncFailure.SYNC] になる。 */
internal class SyncNotSettledException : IllegalStateException("同期の前提が変わり続け、保存できなかった")

/** 検索画面に知らせる同期の失敗（architecture.md の「知らせ」）。組の欠けとジャンルの失敗はここに入れない。 */
internal enum class SyncFailure {
    /** 最後に終わった同期で、あるプロファイルの一覧か詳細の問い合わせが例外になった。どれかは [SyncReport.failedProfiles]。 */
    PROFILE,

    /** 同期全体が失敗した。最後の索引を保つ。 */
    SYNC,
}

/**
 * 索引と独立した同期の状態。
 * @property running 同期の計算か保存を実行中か
 * @property lastResult 最後に終わった同期。同期全体が失敗しても、その前の結果を保つ
 * @property failure 最後の失敗。次に終わった同期で、条件がなければ消える
 */
internal data class IndexSyncStatus(
    val running: Boolean = false,
    val lastResult: SyncReport? = null,
    val failure: SyncFailure? = null,
) {
    /** 最後の同期の結果。同期全体の失敗なら [SyncOutcome.FAILED]。まだ終わった同期がなければ null。 */
    val outcome: SyncOutcome?
        get() = if (failure == SyncFailure.SYNC) SyncOutcome.FAILED else lastResult?.outcome
}
