// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.AppRow
import jp.piu.search.data.db.MetaRow
import jp.piu.search.data.db.PiuRoomDatabase
import jp.piu.search.data.db.SearchKeyRow

/**
 * 保存する入口 1 つ: 索引のアプリと、パッケージの最後の更新の時刻。
 *
 * @property app 識別子・表示名・検索キー
 * @property packageUpdatedAt パッケージの最後の更新の時刻（`PackageInfo.lastUpdateTime`、ミリ秒）。全件の見直しで、作り直すアプリを選ぶのに使う
 */
public data class StoredApp(
    val app: IndexedApp,
    val packageUpdatedAt: Long,
)

/**
 * 索引を作ったときの端末の状態（`meta` の表）。次に画面を開いたとき、差分の取り方を決めるのに使う（architecture.md の「索引の作成と更新」）。
 *
 * @property changeSequence `PackageManager.getChangedPackages` に渡す、前回の変更の番号。分からなければ null
 * @property bootCount `Settings.Global.BOOT_COUNT`。変わっていれば変更の番号が使えないので全件を見直す。取れなければ null
 * @property locales 索引を作ったときの端末の言語（`LocaleList.toLanguageTags` の形）。変わっていれば全件を作り直す
 */
public data class SyncState(
    val changeSequence: Int?,
    val bootCount: Int?,
    val locales: String,
)

/**
 * プロファイルの中のパッケージ 1 つ。パッケージの単位で索引を消すときの指定に使う。
 *
 * @property profileSerial プロファイルのシリアル番号
 * @property packageName パッケージ名
 */
public data class PackageKey(
    val profileSerial: Long,
    val packageName: String,
)

/**
 * 保存した索引を読み出したもの。
 *
 * @property apps 保存した入口。保存した順に並ぶ
 * @property state 索引を作ったときの状態。索引をまだ作っていない、または索引の版が変わって空にしたなら null
 */
public data class StoredIndex(
    val apps: List<StoredApp>,
    val state: SyncState?,
) {
    /** 照合に使うメモリ上の索引を作る（architecture.md の「索引の作成と更新」の 1）。 */
    public fun toSearchIndex(): SearchIndex = if (apps.isEmpty()) SearchIndex.EMPTY else SearchIndex(apps.map { it.app })
}

/**
 * 索引の表（`app`・`search_key`・`meta`）の読み書き。[LocalDatabase.index] で得る。
 *
 * 書き込みはどれも 1 つのトランザクションで行うので、途中で落ちても半端な索引は残らない。
 * 学習の表には触れない（索引の作り直しで学習を消さないため）。
 *
 * @property indexVersion 今の索引の版（`SearchIndexVersion.CURRENT`）。保存した索引の版と違えば、読むときに索引の表を空にする
 */
public class IndexStore internal constructor(
    private val database: PiuRoomDatabase,
    private val indexVersion: Int,
) {
    private val dao = database.indexDao()

    /**
     * 保存した索引を読む。索引の版が今の版と違えば、索引の表だけを空にして、空の索引（[StoredIndex.state] が null）を返す。
     * 空の索引を受け取った側（M2-04 の同期）が全件を作り直す。
     */
    public fun load(): StoredIndex =
        database.runInTransaction<StoredIndex> {
            val meta = dao.meta()
            if (meta == null || meta.indexVersion != indexVersion) {
                clearIndexTables()
                return@runInTransaction StoredIndex(emptyList(), null)
            }
            val keysByApp = dao.keys().groupBy({ it.appId }, { it.toSearchKey() })
            val apps = dao.apps().map { it.toStoredApp(keysByApp[it.id].orEmpty()) }
            StoredIndex(apps, meta.toSyncState())
        }

    /** 索引をすべて [apps] に置き換え、状態を [state] にする。 */
    public fun replaceAll(
        apps: List<StoredApp>,
        state: SyncState,
    ) {
        database.runInTransaction {
            dao.deleteAllApps()
            apps.forEach(::insert)
            dao.putMeta(state.toMetaRow())
        }
    }

    /**
     * 索引の一部を置き換える。[removedProfiles] のプロファイルと [removedPackages] のパッケージの入口を消し、
     * [apps] を入れる（同じプロファイルとパッケージの古い入口は消してから入れる）。状態を [state] にする。
     *
     * 今の版の索引があるときだけ呼べる（先に [load] か [replaceAll] を呼ぶ）。なければ [IllegalStateException]。
     */
    public fun update(
        apps: List<StoredApp>,
        removedPackages: Collection<PackageKey> = emptyList(),
        removedProfiles: Collection<Long> = emptyList(),
        state: SyncState,
    ) {
        database.runInTransaction {
            // 版の違う索引に一部だけ書き足すと、古いキーと新しいキーが混ざるため
            check(dao.meta()?.indexVersion == indexVersion) { "今の版（$indexVersion）の索引がない" }
            removedProfiles.forEach(dao::deleteProfile)
            val replaced = apps.mapTo(LinkedHashSet()) { PackageKey(it.app.id.profileSerial, it.app.id.packageName) }
            (removedPackages + replaced).toSet().forEach { dao.deletePackage(it.profileSerial, it.packageName) }
            apps.forEach(::insert)
            dao.putMeta(state.toMetaRow())
        }
    }

    /** 計算の間に版や同期の状態が変わったら書かない。照合と保存を同じトランザクションに入れるための境界。 */
    internal fun writeIfCurrent(
        expected: SyncState?,
        write: () -> Unit,
    ): Boolean =
        database.runInTransaction<Boolean> {
            val meta = dao.meta()
            val matches = if (expected == null) meta == null else meta == expected.toMetaRow()
            if (!matches) return@runInTransaction false
            write()
            true
        }

    private fun insert(stored: StoredApp) {
        val id = stored.app.id
        val appId =
            dao.insertApp(
                AppRow(
                    packageName = id.packageName,
                    activityName = id.activityName,
                    profileSerial = id.profileSerial,
                    label = stored.app.label,
                    packageUpdatedAt = stored.packageUpdatedAt,
                ),
            )
        dao.insertKeys(stored.app.keys.mapIndexed { position, key -> key.toRow(appId, position) })
    }

    private fun clearIndexTables() {
        dao.deleteAllApps()
        dao.deleteMeta()
    }

    private fun SyncState.toMetaRow(): MetaRow =
        MetaRow(indexVersion = indexVersion, changeSequence = changeSequence, bootCount = bootCount, locales = locales)
}

private fun MetaRow.toSyncState(): SyncState = SyncState(changeSequence, bootCount, locales)

private fun AppRow.toStoredApp(keys: List<SearchKey>): StoredApp =
    StoredApp(IndexedApp(AppId(packageName, activityName, profileSerial), label, keys), packageUpdatedAt)

private fun SearchKey.toRow(
    appId: Long,
    position: Int,
): SearchKeyRow = SearchKeyRow(appId, position, text.value, kind, form, wordStarts.joinToString(","))

private fun SearchKeyRow.toSearchKey(): SearchKey =
    SearchKey(
        text = NormalizedText(text),
        kind = kind,
        wordStarts = if (wordStarts.isEmpty()) emptyList() else wordStarts.split(',').map(String::toInt),
        form = form,
    )
