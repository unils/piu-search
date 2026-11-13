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
import jp.piu.search.core.search.SearchKeyGenerator
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
    /** 保存した索引がない・索引の版か端末の言語が変わった。すべての入口を作り直した。 */
    FULL_REBUILD,

    /** 再起動した（変更の番号が使えない）。すべての入口の更新の時刻を、保存した索引と比べた。 */
    FULL_REVIEW,

    /** 前回からの変更の番号で、変わったパッケージだけを見た。 */
    CHANGES,
}

/**
 * 同期 1 回の結果。記録と計器テストに使う。
 *
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
)

/**
 * 端末のアプリと索引を合わせる（architecture.md の「索引の作成と更新」）。
 *
 * 1. 保存した索引をメモリに読み、すぐ検索できるようにする（[index] に入る）
 * 2. 変更の番号（`getChangedPackages`）で、変わったパッケージだけを作り直す。`BOOT_COUNT` が変わっていたら番号が
 *    使えないので、すべての入口の更新の時刻を見直す
 * 3. 開いている間（[startWatching] から [stopWatching] まで）の変更は `LauncherApps.Callback` で受ける
 * 4. 端末の言語か索引の版が変わったら、全件を作り直す
 * 5. 作り直したら [index] を差し替え、[IndexListener] に知らせる
 *
 * どの方法でも、入口の一覧（名前を読まない軽いもの）と保存した索引を、プロファイルとパッケージの組で比べ、
 * 増えた・消えた入口を拾う。変更の番号は自分のプロファイルのパッケージしか返さず（仕事用プロファイルの変更が漏れる）、
 * 消えたパッケージは可視性の制限で返らないこともあるため。
 *
 * 形態素解析器の辞書は、作り直す入口があるときだけ読み込む（キーの生成が最初に使うとき）。
 * 計算は記録と別のスレッドで行い、[DatabaseWriter] には保存の前提の確認と保存・反映だけを並べる。
 * [sync] は呼んだスレッドで計算し、保存が済むまで待つので、画面のスレッドや書き手から呼ばない。
 */
public class IndexSync internal constructor(
    private val store: IndexStore,
    private val device: DeviceApps,
    generatorFactory: () -> SearchKeyGenerator,
    private val analyzer: ReadingAnalyzer,
    private val launcherApps: LauncherApps?,
    private val writer: DatabaseWriter = DatabaseWriter(),
    private val profiles: ProfileReconciler? = null,
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
        submit(emptySet())
    }

    /**
     * 保存した索引をまだ読んでいなければ読み、端末と合わせる。終わるまで戻らないので、画面のスレッドや書き手から呼ばない。
     *
     * @param packages 変更の番号に出なくても作り直すパッケージ（`LauncherApps.Callback` で知ったもの）
     */
    public fun sync(packages: Set<String> = emptySet()): SyncReport {
        check(!writer.isCurrentThread) { "索引の計算は書き手の外から呼ぶ" }
        return synchronized(syncLock) { syncSerial(packages) }
    }

    private fun syncSerial(packages: Set<String>): SyncReport {
        val started = SystemClock.elapsedRealtime()
        val removedProfiles = HashSet<Long>()
        var analyzerMillis = 0L
        while (true) {
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
            val bootCount = device.bootCount()
            val locales = device.locales()
            val previous = before.state
            val plan =
                if (previous == null || previous.locales != locales) {
                    rebuildAll(before, bootCount, locales)
                } else {
                    reconcile(before, previous, bootCount, locales, packages)
                }
            analyzerMillis += warmUpIfNeeded(plan.rebuild)
            val built = plan.rebuild?.let(::build).orEmpty()
            val next = nextApps(before.apps, plan, built)
            val nextIndex = if (plan.rebuild != null || plan.replaceAll) searchIndex(next) else null
            // 計算に使った端末の状態も古くなり得る。端末の読み取りは書き手の外でやり直す
            if (device.bootCount() != bootCount || device.locales() != locales) continue
            val report =
                writer.run {
                    removedProfiles += profiles?.reconcile().orEmpty()
                    if (revision != before.revision || profiles?.snapshot() != before.profiles) return@run null
                    if (!store.writeIfCurrent(before.state) { save(plan, built, next) }) {
                        // 保存済みの版や状態が変わったので、古い計画を当てずに保存先から読み直す
                        loaded = false
                        return@run null
                    }
                    apps = next
                    state = plan.state
                    revision++
                    if (nextIndex != null) publish(nextIndex)
                    SyncReport(
                        mode = plan.mode,
                        rebuiltApps = built.size,
                        removedPackages = plan.removedPackages.size,
                        removedProfiles = removedProfiles.size,
                        analyzerLoadMillis = analyzerMillis,
                        totalMillis = SystemClock.elapsedRealtime() - started,
                        indexSize = apps.size,
                    ).also { Log.i(TAG, "索引を同期した: $it") }
                }
            if (report != null) return report
        }
    }

    /**
     * 開いている間のアプリの変更を `LauncherApps.Callback` で受け、そのパッケージを作り直す。
     * 画面を開いたときに呼び、閉じたら [stopWatching] を呼ぶ。2 度呼んでも 1 つしか登録しない。
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
            ) = submit(setOf(packageName))

            override fun onPackageAdded(
                packageName: String,
                user: UserHandle,
            ) = submit(setOf(packageName))

            override fun onPackageChanged(
                packageName: String,
                user: UserHandle,
            ) = submit(setOf(packageName))

            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = submit(packageNames.toSet())

            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = submit(packageNames.toSet())
        }

    /** 頼んだ同期がすべて終わるまで待つ。テストで、データベースを閉じる前に使う。 */
    internal fun awaitIdle() {
        executor.submit { synchronized(syncLock) {} }.get()
        writer.run {}
    }

    private fun submit(packages: Set<String>) {
        executor.execute {
            // 同期に失敗しても、今の索引のまま検索を続けられる。次に画面を開いたときにもう一度試す
            try {
                sync(packages)
            } catch (e: RuntimeException) {
                Log.w(TAG, "索引の同期に失敗した: ${e.javaClass.simpleName}")
            }
        }
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

    private fun rebuildAll(
        snapshot: Snapshot,
        bootCount: Int?,
        locales: String,
    ): Plan {
        // 番号は一覧を取る前に得る。取っている間の変更は、次の同期で番号から拾う
        val sequence = device.changedPackages(0).sequence
        val before = device.profileSerials()
        val read = device.read(null)
        val readable = before intersect device.profileSerials()
        val preserved = snapshot.apps.filter { it.app.id.profileSerial !in readable && snapshot.isPresent(it.app.id.profileSerial) }
        return Plan(
            SyncMode.FULL_REBUILD,
            // 読めなかった分も再開後に新しい言語で作り直せるよう、全件が揃うまで言語を進めない
            SyncState(sequence, bootCount, if (preserved.isEmpty()) locales else snapshot.state?.locales ?: locales),
            read.filter { it.entry.id.profileSerial in readable && snapshot.isPresent(it.entry.id.profileSerial) },
            replaceAll = true,
            preserved = preserved,
        )
    }

    private fun reconcile(
        snapshot: Snapshot,
        previous: SyncState,
        bootCount: Int?,
        locales: String,
        requested: Set<String>,
    ): Plan {
        val sequenceUsable = bootCount != null && previous.bootCount == bootCount && previous.changeSequence != null
        val changes = device.changedPackages(if (sequenceUsable) previous.changeSequence else 0)
        val changed = if (sequenceUsable) changes.packages + requested else requested
        val beforeProfiles = device.profileSerials()
        val stamps = device.stamps(withUpdateTime = !sequenceUsable)
        // 一覧や材料の取得中にも一時停止へ移れる。前後とも読めた範囲だけで削除を判定する
        val readable = beforeProfiles intersect device.profileSerials()
        val listed =
            stamps
                .filter { it.id.profileSerial in readable && snapshot.isPresent(it.id.profileSerial) }
                .groupBy { PackageKey(it.id.profileSerial, it.id.packageName) }
        val stored =
            snapshot.apps
                .filter { it.app.id.profileSerial in readable && snapshot.isPresent(it.app.id.profileSerial) }
                .groupBy { PackageKey(it.app.id.profileSerial, it.app.id.packageName) }

        val dirty = HashSet<PackageKey>()
        for (key in stored.keys + listed.keys) {
            val before = stored[key]
            val now = listed[key]
            val isDirty =
                before == null ||
                    now == null ||
                    key.packageName in changed ||
                    before.mapTo(HashSet()) { it.app.id } != now.mapTo(HashSet()) { it.id } ||
                    (!sequenceUsable && before.first().packageUpdatedAt != now.first().packageUpdatedAt)
            if (isDirty) dirty += key
        }
        val mode = if (sequenceUsable) SyncMode.CHANGES else SyncMode.FULL_REVIEW
        val newState = SyncState(changes.sequence, bootCount, locales)
        if (dirty.isEmpty()) return Plan(mode, newState, null)
        val candidates = device.read(dirty.mapTo(HashSet()) { it.packageName })
        val completed = readable intersect device.profileSerials()
        val read =
            candidates.filter {
                it.key in dirty && it.entry.id.profileSerial in completed &&
                    snapshot.isPresent(it.entry.id.profileSerial)
            }
        val present = read.mapTo(HashSet()) { it.key }
        return Plan(mode, newState, read, removedPackages = (dirty - present).filterTo(HashSet()) { it.profileSerial in completed })
    }

    private fun warmUpIfNeeded(rebuild: List<InstalledApp>?): Long {
        if (rebuild.isNullOrEmpty() || analyzer !is KuromojiReadingAnalyzer || analyzer.isLoaded) return 0
        val started = SystemClock.elapsedRealtime()
        analyzer.warmUp()
        return SystemClock.elapsedRealtime() - started
    }

    private fun build(read: List<InstalledApp>): List<StoredApp> =
        read.map { StoredApp(IndexedApp(it.entry.id, it.entry.label, generator.generate(it.entry)), it.packageUpdatedAt) }

    private fun nextApps(
        before: List<StoredApp>,
        plan: Plan,
        built: List<StoredApp>,
    ): List<StoredApp> {
        if (plan.replaceAll) return built + plan.preserved
        if (plan.rebuild == null) return before
        val replaced = built.mapTo(HashSet()) { it.key } + plan.removedPackages
        return before.filter { it.key !in replaced } + built
    }

    private fun save(
        plan: Plan,
        built: List<StoredApp>,
        next: List<StoredApp>,
    ) {
        if (plan.replaceAll) {
            store.replaceAll(next, plan.state)
        } else if (plan.rebuild != null) {
            store.update(built, plan.removedPackages, state = plan.state)
        } else if (plan.state != state) {
            // 入口は変わらず、番号だけが進んだ
            store.update(emptyList(), state = plan.state)
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

    /**
     * 同期 1 回で行うこと。
     *
     * @property rebuild 作り直す入口。null は作り直すものがないこと
     * @property replaceAll 索引をすべて [rebuild] に置き換えるか
     */
    private class Plan(
        val mode: SyncMode,
        val state: SyncState,
        val rebuild: List<InstalledApp>?,
        val replaceAll: Boolean = false,
        val removedPackages: Set<PackageKey> = emptySet(),
        val preserved: List<StoredApp> = emptyList(),
    )

    private companion object {
        const val TAG = "PiuIndexSync"
    }
}

private val InstalledApp.key: PackageKey get() = PackageKey(entry.id.profileSerial, entry.id.packageName)

private val StoredApp.key: PackageKey get() = PackageKey(app.id.profileSerial, app.id.packageName)
