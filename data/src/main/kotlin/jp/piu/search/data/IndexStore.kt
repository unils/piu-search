// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.IndexedApp
import jp.piu.search.core.search.SearchIndex
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.data.db.AppRow
import jp.piu.search.data.db.MaterialIssueRow
import jp.piu.search.data.db.MetaRow
import jp.piu.search.data.db.PiuRoomDatabase
import jp.piu.search.data.db.SearchKeyRow

/**
 * 保存する入口 1 つ: 索引のアプリと、組の一覧の観測（鍵・アーカイブ済みか）と作り直しの印。
 *
 * @property app 識別子・表示名・検索キー
 * @property packageUpdatedAt パッケージの最後の更新の時刻（ミリ秒）。全件の見直しで比べる。作れなかったら null（失敗ではない）
 * @property apkFingerprint base と split の APK の指紋。作れなかったら null（失敗ではない）
 * @property archived 読んだときの一覧でアーカイブ済みだったか
 * @property rebuildPending 作り直しの印。プロファイルが読めるようになったら必ず読み直す
 */
internal data class StoredApp(
    val app: IndexedApp,
    val packageUpdatedAt: Long?,
    val apkFingerprint: String? = null,
    val archived: Boolean = false,
    val rebuildPending: Boolean = false,
)

/**
 * 索引を作ったときの端末の状態（`meta` の表）。次に画面を開いたとき、差分の取り方を決めるのに使う（architecture.md の「索引の作成と更新」）。
 * 保存の前に、保存済みの値と一致するかを同じトランザクションで確かめる。
 *
 * @property buildFingerprint OS のビルド。変更時は全件を作り直す
 * @property changeSequence `PackageManager.getChangedPackages` に渡す、前回の変更の番号。分からなければ null
 * @property bootCount `Settings.Global.BOOT_COUNT`。変わっていれば変更の番号が使えないので全件を見直す。取れなければ null
 * @property locales 索引を作ったときの端末の言語（`LocaleList.toLanguageTags` の形）。変わっていれば全件を作り直す
 * @property appVersionCode 保存したときの Piu Search の `versionCode`。今の版と違えば、欠けの行のある組を読み直す。0.1.x の索引は null
 */
internal data class SyncState(
    val changeSequence: Int?,
    val bootCount: Int?,
    val locales: String,
    val buildFingerprint: String = "",
    val appVersionCode: Int? = null,
)

/**
 * プロファイルの中のパッケージ 1 つ（組）。読み直し・欠けの記録・削除の単位。
 *
 * @property profileSerial プロファイルのシリアル番号
 * @property packageName パッケージ名
 */
internal data class PackageKey(
    val profileSerial: Long,
    val packageName: String,
)

/**
 * 保存した索引を読み出したもの。
 *
 * @property apps 保存した入口。保存した順に並ぶ
 * @property state 索引を作ったときの状態。索引をまだ作っていない、または索引の版が変わって作り直しが要るなら null
 * @property issues 材料の欠けの行
 * @property lastSavedAt 最後に保存できた同期の時刻（ミリ秒）。表示と報告のためだけ
 */
internal data class StoredIndex(
    val apps: List<StoredApp>,
    val state: SyncState?,
    val issues: List<MaterialIssue> = emptyList(),
    val lastSavedAt: Long? = null,
) {
    /** 照合に使うメモリ上の索引を作る（architecture.md の「索引の作成と更新」の 1）。 */
    public fun toSearchIndex(): SearchIndex = if (apps.isEmpty()) SearchIndex.EMPTY else SearchIndex(apps.map { it.app })
}

/**
 * 同期 1 回で保存する差分。[IndexStore.save] が 1 つのトランザクションで当てる。
 *
 * @property replaceAll 索引をすべて [apps] に置き換えるか（今の版の索引がないとき）
 * @property apps 入口とキーを書き直す組の、すべての入口
 * @property removed 索引から消す組
 * @property genreOnly ジャンルのキーだけを書き直す入口
 * @property pending 作り直しの印だけを立てる組
 * @property putIssues 書く欠けの行
 * @property deletedIssues 消す欠けの行（組と段）
 * @property state 保存する状態
 * @property savedAt 保存した時刻（`meta` の `last_saved_at`）
 */
internal class IndexChange(
    val replaceAll: Boolean,
    val apps: List<StoredApp>,
    val removed: Collection<PackageKey> = emptyList(),
    val genreOnly: List<StoredApp> = emptyList(),
    val pending: Collection<PackageKey> = emptyList(),
    val putIssues: List<MaterialIssue> = emptyList(),
    val deletedIssues: Collection<Pair<PackageKey, MaterialStage>> = emptyList(),
    val state: SyncState,
    val savedAt: Long,
)

/**
 * 索引の表（`app`・`search_key`・`meta`・`material_issue`）の読み書き。[LocalDatabase.index] で得る。
 *
 * 書き込みはどれも 1 つのトランザクションで行うので、途中で落ちても半端な索引は残らない。
 * 学習と起動の記録の表には触れない（索引の作り直しで記録を消さないため）。
 * 不明の時刻の番兵（`Long.MIN_VALUE`）はこの中に閉じ、外には null で見せる（architecture.md の「同期のための列」）。
 *
 * @property indexVersion 今の索引の版（`SearchIndexVersion.CURRENT`）。保存した索引の版と違えば、読むときに状態を消し、全入口に作り直しの印を立てる
 */
internal class IndexStore internal constructor(
    private val database: PiuRoomDatabase,
    private val indexVersion: Int,
) {
    private val dao = database.indexDao()

    /**
     * 保存した索引を読む。索引の版が違えば、`meta` を消して状態を null にし、全入口に作り直しの印を立てる。
     * 入口・キー・欠けの行は保ち、一時停止などで読めないプロファイルも、読み直せるまで旧索引で検索できる。
     */
    public fun load(): StoredIndex =
        database.runInTransaction<StoredIndex> {
            val meta = dao.meta()
            val needsRebuild = meta == null || meta.indexVersion != indexVersion
            // 印を先に保存しておき、同期の前にプロセスが終わっても全件の作り直しを忘れない。
            // 読めないプロファイルにも印が残るので、新版で個人用の同期を済ませても、再開後に作り直す
            if (needsRebuild) {
                dao.deleteMeta()
                dao.markAllPending()
            }
            val keysByApp = dao.keys().groupBy({ it.appId }, { it.toSearchKey() })
            StoredIndex(
                dao.apps().map { it.toStoredApp(keysByApp[it.id].orEmpty()) },
                if (needsRebuild) null else meta.toSyncState(),
                dao.issues().mapNotNull { it.toIssue() },
                meta?.lastSavedAt,
            )
        }

    /** 索引をすべて [apps] に置き換え、状態を [state] にする。欠けの行は、[apps] にない組の分だけ消す。 */
    public fun replaceAll(
        apps: List<StoredApp>,
        state: SyncState,
    ) {
        database.runInTransaction { write(IndexChange(replaceAll = true, apps = apps, state = state, savedAt = 0)) }
    }

    /**
     * 索引の一部を置き換える。[removedProfiles] のプロファイルと [removedPackages] のパッケージの入口を消し、
     * [apps] を入れる（同じプロファイルとパッケージの古い入口は消してから入れる）。状態を [state] にする。
     *
     * 今の版で保存済みの索引があるときだけ呼べる（先に [replaceAll] を呼ぶ）。なければ [IllegalStateException]。
     */
    public fun update(
        apps: List<StoredApp>,
        removedPackages: Collection<PackageKey> = emptyList(),
        removedProfiles: Collection<Long> = emptyList(),
        state: SyncState,
    ) {
        database.runInTransaction {
            removedProfiles.forEach {
                dao.deleteProfile(it)
                dao.deleteProfileIssues(it)
            }
            write(IndexChange(replaceAll = false, apps = apps, removed = removedPackages, state = state, savedAt = 0))
        }
    }

    /**
     * 計算の間に版や同期の状態（このアプリの版を含む）が変わったら書かない。照合と保存を同じトランザクションに入れるための境界。
     * 一致すれば [change] を当てる。
     */
    internal fun saveIfCurrent(
        expected: SyncState?,
        change: IndexChange,
    ): Boolean =
        database.runInTransaction<Boolean> {
            val meta = dao.meta()
            val current = meta?.takeIf { it.indexVersion == indexVersion }?.toSyncState()
            if (current != expected || (expected == null && meta != null)) return@runInTransaction false
            write(change)
            true
        }

    private fun write(change: IndexChange) {
        if (change.replaceAll) {
            dao.deleteAllApps()
        } else {
            // 作り直しを始める前に差分だけ保存すると、版変更を忘れて旧索引を確定してしまうため
            check(dao.meta()?.indexVersion == indexVersion) { "今の版（$indexVersion）の索引がない" }
            val replaced = change.apps.mapTo(LinkedHashSet()) { PackageKey(it.app.id.profileSerial, it.app.id.packageName) }
            (change.removed + replaced).toSet().forEach { dao.deletePackage(it.profileSerial, it.packageName) }
        }
        change.apps.forEach(::insert)
        updateGenres(change.genreOnly)
        change.pending.forEach { dao.markPending(it.profileSerial, it.packageName) }
        change.deletedIssues.forEach { (key, stage) -> dao.deleteIssue(key.profileSerial, key.packageName, stage.code) }
        change.putIssues.forEach { dao.putIssue(it.toRow()) }
        // 次の索引にない組の行を消す。一覧にある組は必ず索引にあるので、ほかの経路で孤立した行はここで片づく
        dao.deleteOrphanIssues()
        dao.putMeta(change.state.toMetaRow(change.savedAt.takeIf { it > 0 } ?: dao.meta()?.lastSavedAt))
    }

    /** ジャンルだけの変化では、名前のキーと入口の行を保存し直さない。 */
    private fun updateGenres(apps: List<StoredApp>) {
        for (stored in apps) {
            val id = stored.app.id
            val rowId = dao.appRowId(id.profileSerial, id.packageName, id.activityName)
            dao.deleteGenreKeys(rowId)
            val position = dao.nextKeyPosition(rowId)
            dao.insertKeys(
                stored.app.keys
                    .filter { it.kind == SearchKeyKind.GENRE }
                    .mapIndexed { offset, key -> key.toRow(rowId, position + offset) },
            )
        }
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
                    packageUpdatedAt = stored.packageUpdatedAt ?: UNKNOWN_UPDATED_AT,
                    apkFingerprint = stored.apkFingerprint,
                    archived = stored.archived,
                    rebuildPending = stored.rebuildPending,
                ),
            )
        dao.insertKeys(stored.app.keys.mapIndexed { position, key -> key.toRow(appId, position) })
    }

    private fun SyncState.toMetaRow(lastSavedAt: Long?): MetaRow =
        MetaRow(
            indexVersion = indexVersion,
            changeSequence = changeSequence,
            bootCount = bootCount,
            locales = locales,
            buildFingerprint = buildFingerprint,
            appVersionCode = appVersionCode,
            lastSavedAt = lastSavedAt,
        )
}

private fun MetaRow.toSyncState(): SyncState = SyncState(changeSequence, bootCount, locales, buildFingerprint, appVersionCode)

// 列は NOT NULL で、null にするには app の作り直しが要る（architecture.md の「同期のための列」）。どの端末の時刻にも一致しない値で
// 「不明」を表す。0 は APEX の中の APK の本当の時刻なので使えない。0.1.0 で保存した「不明の 0」は区別できず、時刻 0 として読む
private const val UNKNOWN_UPDATED_AT = Long.MIN_VALUE

private fun AppRow.toStoredApp(keys: List<SearchKey>): StoredApp =
    StoredApp(
        IndexedApp(AppId(packageName, activityName, profileSerial), label, keys),
        packageUpdatedAt.takeIf { it != UNKNOWN_UPDATED_AT },
        apkFingerprint,
        archived,
        rebuildPending,
    )

// 知らない段の行は、新しい版で書かれた行。古い版で読み戻したときは扱わずに残す（次の保存で孤立しない限り消さない）
private fun MaterialIssueRow.toIssue(): MaterialIssue? =
    MaterialStage.of(stage)?.let {
        MaterialIssue(
            PackageKey(profileSerial, packageName),
            it,
            cause,
            frames,
            inputSummary,
            versionCode,
            retryOnce,
            firstFailedOn,
            lastFailedOn,
        )
    }

private fun MaterialIssue.toRow(): MaterialIssueRow =
    MaterialIssueRow(
        key.profileSerial,
        key.packageName,
        stage.code,
        cause,
        frames,
        inputSummary,
        versionCode,
        retryOnce,
        firstFailedOn,
        lastFailedOn,
    )

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
