// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.data.db.PiuRoomDatabase

/** プロファイル単位で、索引と利用者の記録をまとめて扱う保存先。 */
public interface ProfileStorage {
    /** 索引・学習・起動の記録に保存したシリアル番号の和集合。索引だけに頼らず孤立した学習も見つける。 */
    public fun serials(): Set<Long>

    /** [serials] の索引・検索キー・学習・起動の記録を、1 つのトランザクションで消す。 */
    public fun delete(serials: Set<Long>)
}

/** プロファイルの削除の保存境界。[LocalDatabase.profiles] から得る。 */
public class ProfileStore internal constructor(
    private val database: PiuRoomDatabase,
) : ProfileStorage {
    private val dao = database.profileDao()

    override fun serials(): Set<Long> = dao.serials().toSet()

    override fun delete(serials: Set<Long>) {
        database.runInTransaction {
            serials.forEach { serial ->
                // 索引への外部キーを張らない記録も、同じ確定の境界で消す必要があるため
                database.indexDao().deleteProfile(serial)
                dao.deleteLearning(serial)
                dao.deleteLaunchStats(serial)
            }
        }
    }
}
