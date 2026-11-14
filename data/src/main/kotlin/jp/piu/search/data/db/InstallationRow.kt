// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 索引の版を変えても残すインストールの印。端末間転送の直後に、索引と仕事用の記録を捨てるために使う。 */
@Entity(tableName = "installation")
internal data class InstallationRow(
    @PrimaryKey val id: Int = 0,
    val token: String,
)
