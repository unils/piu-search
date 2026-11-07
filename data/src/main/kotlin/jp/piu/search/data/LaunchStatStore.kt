// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LaunchRecord
import jp.piu.search.data.db.LaunchStatRow
import jp.piu.search.data.db.PiuRoomDatabase

/**
 * 起動の記録の表（`launch_stat`）の読み書き。[LocalDatabase.launchStats] で得る。
 *
 * 索引の表とは別に持ち、索引を作り直しても消さない（消えてよくない表。architecture.md の「データの保存」）。
 */
public class LaunchStatStore internal constructor(
    private val database: PiuRoomDatabase,
) {
    private val dao = database.launchStatDao()

    /** すべての記録を読む。消えたアプリの記録も含む。 */
    public fun load(): LaunchHistory =
        LaunchHistory(
            dao.all().associate {
                AppId(it.packageName, it.activityName, it.profileSerial) to
                    LaunchRecord(it.launchCount, it.lastLaunchedAt)
            },
        )

    /** [app] を時刻 [at]（エポックからのミリ秒）に起動したことを記録する。 */
    public fun record(
        app: AppId,
        at: Long,
    ) {
        database.runInTransaction {
            if (dao.increment(app.profileSerial, app.packageName, app.activityName, at) == 0) {
                dao.insert(LaunchStatRow(app.packageName, app.activityName, app.profileSerial, launchCount = 1, lastLaunchedAt = at))
            }
        }
    }
}
