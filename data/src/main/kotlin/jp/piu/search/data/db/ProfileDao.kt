// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.db

import androidx.room.Dao
import androidx.room.Query

/** プロファイル単位の整理。索引が空でも、記録だけに残った番号を照合の対象にする。 */
@Dao
internal interface ProfileDao {
    @Query("SELECT profile_serial FROM app UNION SELECT profile_serial FROM learned UNION SELECT profile_serial FROM launch_stat")
    fun serials(): List<Long>

    @Query("DELETE FROM learned WHERE profile_serial = :serial")
    fun deleteLearning(serial: Long)

    @Query("DELETE FROM launch_stat WHERE profile_serial = :serial")
    fun deleteLaunchStats(serial: Long)
}
