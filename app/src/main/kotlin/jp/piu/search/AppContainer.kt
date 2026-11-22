// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.state.IndexSnapshot
import jp.piu.search.core.state.RecordsRequest
import jp.piu.search.core.state.RecordsResult
import jp.piu.search.core.state.RecordsState
import jp.piu.search.core.state.UserRecords
import jp.piu.search.data.ProfileWatcher
import jp.piu.search.data.index.IndexActor
import jp.piu.search.data.records.DeviceProfileProbe
import jp.piu.search.data.records.ProfileProbe
import jp.piu.search.data.records.RecordsActor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import jp.piu.search.data.index.IndexStatus as IndexWriterStatus

/**
 * 部品の組み立て（architecture.md の「モジュール」）。アプリの中で 1 つだけ作り、[PiuApplication] が持つ。
 *
 * 依存の注入のライブラリは使わず、ここで手で組む。記録と索引の書き手（actor）は、作った時点で読み込みを始める。
 * 遅延にしないのは、検索画面を開かないプロセス（タイル・アプリ情報から設定だけを開いたなど）でも、消えたプロファイルの記録の整理を行うため。
 * 作るのは画面のスレッドで、Room は `build()` だけ（ファイルは書き手の裏のスレッドで最初に触れたときに開く）。
 *
 * 書き手・読み・派生の索引は、それぞれ 1 本の裏のスレッドで動かす。索引の書き手（材料の読み取りとキーの生成）と読み・派生の索引は、
 * 描画と競わないよう `THREAD_PRIORITY_BACKGROUND` にする（0.2.0 の同期と同じ。共有の IO の池の優先度は変えない）。
 *
 * @param appVersionCode 今の Piu Search の `versionCode`。テストで版の変更を起こすために差し替えられる
 * @param profileProbe 記録の書き手がプロファイルの存在を問い合わせる口。テストで書き手を止めるために差し替えられる
 * @param indexPortOverride 画面が読む索引の口。テストで、端末を読まずに決まった索引を渡すために差し替えられる
 */
class AppContainer(
    context: Context,
    private val appVersionCode: Int = BuildConfig.VERSION_CODE,
    profileProbe: ProfileProbe = DeviceProfileProbe(context),
    indexPortOverride: IndexPort? = null,
) {
    private val appContext = context.applicationContext
    private val executors = ArrayList<ExecutorService>()

    private fun thread(
        name: String,
        priority: Int,
    ): CoroutineDispatcher = backgroundThread(name, priority).also(executors::add).asCoroutineDispatcher()

    private val recordsDispatcher = thread("piu-records", Process.THREAD_PRIORITY_DEFAULT)
    private val indexDispatcher = thread("piu-index", Process.THREAD_PRIORITY_BACKGROUND)

    /** 漢字の検索語に読みを付けるスレッド。索引の同期を待たずに読みを付けられるよう、同期とは分ける。 */
    val readingDispatcher: CoroutineDispatcher = thread("piu-query-reading", Process.THREAD_PRIORITY_BACKGROUND)

    /** 学習とプロファイルを重ねた索引を組み立てるスレッド。入力の読みと保存を待たせないため分ける。 */
    val derivedDispatcher: CoroutineDispatcher = thread("piu-derived-index", Process.THREAD_PRIORITY_BACKGROUND)

    /**
     * アプリの寿命の scope（画面のスレッド）。画面が閉じても続ける仕事（受理した起動・ファイル操作・記録の頼みの結果の知らせ）を動かす。
     * プロセスが終わるまで取り消さない（テストの片付けだけが [cancel] する）。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineName("piu-app"))

    /** 形態素解析器。索引のキーの生成と、漢字の検索語の読みで共有する。 */
    val analyzer: KuromojiReadingAnalyzer = KuromojiReadingAnalyzer()

    /** 記録（学習と起動の記録）の書き手。`piu.db`。 */
    val records: RecordsActor = RecordsActor.open(appContext, appScope, profileProbe, recordsDispatcher)

    /** 索引の書き手。`index.db`。このアプリの版が変われば、欠けのある組を読み直す。 */
    val index: IndexActor = IndexActor.open(appContext, appScope, appVersionCode, analyzer, indexDispatcher)

    /** プロファイルの状態。 */
    val profiles: ProfileWatcher = ProfileWatcher(appContext, appScope)

    /** 利用者の設定。 */
    val settings: SettingsStore = SettingsStore(appContext)

    /** プロファイルの識別子。 */
    val users: ProfileUsers = ProfileUsers(appContext)

    /** 結果のアプリを開く部品。 */
    val launcher: AppLauncher = AppLauncher(appContext, users)

    /** 結果の行のアイコン。読み込みは 1 本の裏のスレッドで行う。 */
    val icons: LauncherAppIcons =
        LauncherAppIcons(appContext, users, backgroundThread("piu-icons", Process.THREAD_PRIORITY_BACKGROUND).also(executors::add))

    /** パッケージの変更の知らせ。 */
    val packages: PackageWatcher =
        PackageWatcher(appContext.getSystemService(LauncherApps::class.java), index::packagesChanged, icons::invalidate)

    /** 画面が閉じても出す知らせ。 */
    val messages: UserMessages = ToastMessages(appContext)

    /** 計測の印。 */
    val timing: SearchTiming = LogTiming()

    /**
     * 直近にアプリを開いたときの、記録の保存に失敗したか。開くと検索の画面は閉じるので、次に画面を開いたときに控えめに知らせるためここに持つ。
     * 次の保存が成功したら下ろす。
     */
    val openRecordFailed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** 検索画面が前面か。プロファイルの放送の後に同期するかを決める。 */
    val searchForeground: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** 学習の書き出し・読み込みなどの結果で、学習の一覧の画面がまだ出し終えていないもの。画面を作り直しても失わないよう、ここに置く。 */
    val backupOutcome: ResultBox<BackupOutcome> = ResultBox()

    /** 照合に使う索引。写しが変わるたびに 1 回だけ、裏のスレッドで作る（検索・学習の一覧の表示名で共有する）。 */
    val searchIndex: StateFlow<SearchIndex> =
        index.snapshot
            .map { it.toSearchIndex() }
            .flowOn(derivedDispatcher)
            .stateIn(appScope, SharingStarted.Eagerly, SearchIndex.EMPTY)

    /** 画面が記録の書き手に頼む口。 */
    val recordsPort: RecordsPort =
        object : RecordsPort {
            override val state: StateFlow<RecordsState> get() = records.state
            override val rebuiltAfterCorruption: StateFlow<Boolean> get() = records.rebuiltAfterCorruption

            override fun submit(request: RecordsRequest): Deferred<RecordsResult> = records.submit(request)

            override fun refresh(): Deferred<Unit> = records.refresh()

            override fun consumeNotice(id: Long) = records.consumeNotice(id)

            override fun acknowledgeRebuilt() = records.acknowledgeRebuilt()
        }

    /** 画面が索引の書き手に頼む口。 */
    val indexPort: IndexPort =
        indexPortOverride ?: object : IndexPort {
            override val searchIndex: StateFlow<SearchIndex> get() = this@AppContainer.searchIndex
            override val snapshot: StateFlow<IndexSnapshot> get() = index.snapshot
            override val status: StateFlow<IndexWriterStatus> get() = index.status

            override fun setForeground(visible: Boolean) = index.setForeground(visible)

            override fun requestSync() {
                index.requestSync()
            }
        }

    /** 学習の書き出しと読み込み。 */
    val learningTransfer: LearningTransfer =
        LearningTransfer(
            object : LearningStore {
                override suspend fun <T> export(write: suspend (UserRecords) -> T): T? = records.export(write)

                override suspend fun import(
                    at: Long,
                    records: List<LearnedRecord>,
                ): RecordsResult = this@AppContainer.records.import(at) { records }
            },
            appContext.contentResolver,
            ::backupProfiles,
            Dispatchers.IO,
        )

    init {
        // replay がないので、最初の同期より前に購読する
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            index.staleIcons.collect { keys -> icons.invalidate(keys.mapTo(HashSet()) { it.packageName }) }
        }
        // 放送の後は、記録の整理を試し、検索画面が前面なら同期する（裏では次に開いたときの同期に任せる）
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            profiles.changes.collect {
                records.refresh()
                if (searchForeground.value) index.requestSync()
            }
        }
        // 書き手が止まったら、スレッドを片付ける（テストで部品を作り直すときだけ起きる）
        appScope.coroutineContext[Job]?.invokeOnCompletion { executors.forEach(ExecutorService::shutdown) }
    }

    /** 検索画面の部品。 */
    fun searchDependencies(): SearchDependencies =
        SearchDependencies(
            index = indexPort,
            records = recordsPort,
            profiles = profiles.profiles,
            refreshProfiles = profiles::refresh,
            settings = settings.settings,
            dismissOnboarding = settings::dismissOnboarding,
            openRecordFailed = openRecordFailed,
            searchForeground = searchForeground,
            launcher = launcher,
            messages = messages,
            analyzer = analyzer,
            appScope = appScope,
            readingDispatcher = readingDispatcher,
            derivedDispatcher = derivedDispatcher,
            timing = timing,
        )

    /** 学習の書き出しと読み込みで、個人用・仕事用を読み替えるための今の番号。 */
    fun backupProfiles(): BackupProfiles = profiles.profiles.value.let { BackupProfiles(it.own, it.work) }

    /** 放送と Callback の受け取りを始める。プロセスの起動時に呼ぶ。 */
    fun start() {
        profiles.start()
        packages.start()
    }

    /**
     * メモリが足りないとき（`onTrimMemory`）に、形態素解析器の辞書（端末で数十 MB）とアイコンを手放す。
     * 画面が裏に回った（BACKGROUND 以上）なら手放す。次に要るときに読み込み直す。
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            analyzer.release()
            icons.clear()
        }
    }

    /**
     * 受け取りを終え、アプリの寿命の scope を取り消す。終わるのは待たない（画面のスレッドで呼ぶので、画面のスレッドに戻る子を待つと止まるため）。
     * プロセスの寿命より短いテストで、部品を片付けるために使う。
     */
    fun cancel() {
        packages.close()
        profiles.close()
        appScope.cancel()
    }

    private companion object {
        fun backgroundThread(
            name: String,
            priority: Int,
        ): ExecutorService =
            Executors.newSingleThreadExecutor { task ->
                Thread({
                    Process.setThreadPriority(priority)
                    task.run()
                }, name)
            }
    }
}
