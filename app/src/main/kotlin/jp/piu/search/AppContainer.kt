// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Process
import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.data.IndexSync
import jp.piu.search.data.LocalDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 部品の組み立て（architecture.md の「モジュール」）。アプリの中で 1 つだけ作り、[PiuApplication] が持つ。
 *
 * 依存の注入のライブラリは使わず、ここで手で組む。プロセスが生きている間は索引をメモリに残し、次に画面を開いたときに読み直さない。
 * 作るのは画面のスレッドなので、ファイルや資源を読む部品（データベース・ブランド表・辞書）は、使うときに裏のスレッドで開く。
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** 形態素解析器。索引の作り直しと、漢字の検索語の読みで共有する。 */
    val analyzer: KuromojiReadingAnalyzer = KuromojiReadingAnalyzer()

    /** データベース。Room はファイルを最初の読み書きで開くので、作るだけなら軽い。 */
    val database: LocalDatabase by lazy { LocalDatabase.open(appContext) }

    /** 索引の同期。 */
    val indexSync: IndexSync by lazy { IndexSync(appContext, database, analyzer) }

    /** 漢字の検索語に読みを付けるスレッド。索引の作り直しを待たずに読みを付けられるよう、同期とは分ける。 */
    val queryExecutor: ExecutorService = backgroundThread("piu-query-reading")

    /** 起動の記録と学習の読み書きのスレッド。辞書の読み込みや索引の作り直しの後ろで待たないよう、ほかと分ける。 */
    private val recordExecutor: ExecutorService = backgroundThread("piu-launch-record")

    /** プロファイルの識別子と、仕事用プロファイルの状態。 */
    val users: ProfileUsers by lazy { ProfileUsers(appContext) }

    /** 起動の記録。 */
    val launchHistory: LaunchHistoryRepository = LaunchHistoryRepository({ database.launchStats }, recordExecutor)

    /** 学習。起動の記録と同じスレッドで読み書きする（どちらも小さく、アプリを開いたときに続けて書くため）。 */
    val learning: LearningRepository = LearningRepository({ database.learned }, recordExecutor)

    /** 学習の書き出し・読み込みの結果で、学習の一覧の画面がまだ出し終えていないもの。画面を作り直しても失わないよう、ここに置く。 */
    val backupOutcome: PendingResult<BackupOutcome> = PendingResult()

    /** 利用者の設定（初回の案内を閉じたかなど）。 */
    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    /** 結果のアプリを開く部品。 */
    val launcher: AppLauncher by lazy { AppLauncher(appContext, users) }

    /** 結果の行のアイコン。読み込みは 1 本の裏のスレッドで行う。 */
    val icons: LauncherAppIcons by lazy { LauncherAppIcons(appContext, users, backgroundThread("piu-icons")) }

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

    private companion object {
        fun backgroundThread(name: String): ExecutorService =
            Executors.newSingleThreadExecutor { task ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                }, name)
            }
    }
}
