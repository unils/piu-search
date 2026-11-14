// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId

/**
 * 同期を始めたときの端末の状態。保存した [SyncState] と比べて差分の取り方を決め、保存後は次の [SyncState] になる。
 *
 * @property bootCount 再起動の回数。取れなければ null
 * @property locales 端末の言語
 * @property buildFingerprint OS のビルド
 */
internal data class DeviceState(
    val bootCount: Int?,
    val locales: String,
    val buildFingerprint: String,
)

/**
 * 差分の取り方。[SyncPlanner.scope] が決める。
 *
 * @property mode 取る方法
 * @property since `getChangedPackages` に渡す番号。番号を使わないなら 0（番号を進めるために、使わないときも問い合わせる）
 */
internal data class SyncScope(
    val mode: SyncMode,
    val since: Int,
) {
    /** 変更の番号で差分を取るか。取らないなら、入口一覧に更新の時刻を付けて全件を見直す。 */
    val sequenceUsable: Boolean get() = mode == SyncMode.CHANGES
}

/**
 * 入口一覧と保存した索引を比べた結果。詳細を読むパッケージ（[dirty]）とジャンルを問い合わせる入口（[stamps]）を決める。
 *
 * @property readable 入口一覧を最後まで読めたプロファイル
 * @property failedProfiles 入口一覧の問い合わせに失敗したプロファイル。一時停止（`UNAVAILABLE`）は含めない
 * @property stamps 読めて、まだ消えていないプロファイルの入口
 * @property listed [stamps] をプロファイルとパッケージの組でまとめたもの
 * @property dirty 詳細を読み直す組。読めないプロファイル・消えたプロファイルの組は入れない
 */
internal class SyncTargets(
    val readable: Set<Long>,
    val failedProfiles: Set<Long>,
    val stamps: List<ActivityStamp>,
    val listed: Map<PackageKey, List<ActivityStamp>>,
    val dirty: Set<PackageKey>,
) {
    /** 詳細を読むパッケージ。プロファイルを問わず読むので、名前だけにする。 */
    val dirtyPackages: Set<String> get() = dirty.mapTo(HashSet()) { it.packageName }
}

/**
 * 同期 1 回の計画。保存と反映は [IndexSync] が書き手で行う。
 *
 * @property mode 取った方法
 * @property state 保存する同期の状態
 * @property rebuild キーを作り直す入口の材料
 * @property removedPackages 索引から消す組
 * @property readable 一覧も詳細も読めたプロファイル。ここにないプロファイルの索引は保ち、指紋を消して次に読み直す
 * @property failedPackages 読み直しに失敗した組。旧索引を保ち、指紋を消す
 * @property changedPackages 読み直したパッケージ。アイコンを失効させる
 * @property genres 入口ごとのジャンル。問い合わせに失敗したら null で、以前のジャンルを保つ
 * @property failedProfiles 一覧か詳細の問い合わせに失敗したプロファイルの数。一時停止は含めない
 */
internal class SyncPlan(
    val mode: SyncMode,
    val state: SyncState,
    val rebuild: List<InstalledApp>,
    val removedPackages: Set<PackageKey>,
    val readable: Set<Long>,
    val failedPackages: Set<PackageKey>,
    val changedPackages: Set<String>,
    val genres: Map<AppId, List<GenreId>>?,
    val failedProfiles: Int,
)

/**
 * 差分の同期の規則。Room と端末に触れず、渡された読み取りの結果だけで決める。
 *
 * 端末の読み取りは前の段の結果で範囲が決まるので、3 段に分ける。[IndexSync] が段の間で端末を読む。
 * 1. [scope]: 保存した状態と今の端末の状態から、差分の取り方を決める。→ 変更の番号と入口一覧を読む
 * 2. [targets]: 入口一覧と保存した索引を比べ、読み直す組を決める。→ ジャンルと詳細を読む
 * 3. [plan]: 詳細の読み取り結果から、作り直す・消す・失敗した組と次の状態を決める
 *
 * プロファイルが消えたかは [isPresent] で渡す（[ProfileReconciler] が確定した削除）。消えたプロファイルの入口は読まず、索引から落とす。
 */
internal object SyncPlanner {
    /**
     * 差分の取り方を決める。索引がない（[previous] が null）・言語・OS のビルドが変われば全件を作り直す。
     * 再起動の回数が違う・不明、または前回の番号がなければ、変更の番号が使えないので全件の更新時刻を見直す。
     */
    fun scope(
        previous: SyncState?,
        device: DeviceState,
    ): SyncScope {
        val full = previous == null || previous.locales != device.locales || previous.buildFingerprint != device.buildFingerprint
        val sequence = previous?.changeSequence
        return when {
            full -> SyncScope(SyncMode.FULL_REBUILD, 0)
            device.bootCount != null && previous.bootCount == device.bootCount && sequence != null -> SyncScope(SyncMode.CHANGES, sequence)
            else -> SyncScope(SyncMode.FULL_REVIEW, 0)
        }
    }

    /**
     * 入口一覧 [profiles] と保存した索引 [stored] を比べ、読み直す組を決める。
     *
     * @param changed 変更の番号に出たパッケージ。[SyncScope.sequenceUsable] のときだけ見る
     * @param forced 指紋や時刻が同じでも読み直す組（Callback の知らせ）
     */
    fun targets(
        scope: SyncScope,
        stored: List<StoredApp>,
        isPresent: (Long) -> Boolean,
        profiles: Map<Long, ProfileRead<ActivityStamp>>,
        changed: Set<String>,
        forced: Set<PackageKey>,
    ): SyncTargets {
        val readable = profiles.filterValues { it.status == ProfileReadStatus.COMPLETE }.keys
        val stamps = profiles.values.flatMap { it.items }.filter { it.id.profileSerial in readable && isPresent(it.id.profileSerial) }
        val listed = stamps.groupBy { PackageKey(it.id.profileSerial, it.id.packageName) }
        val old = stored.groupBy { it.key }
        val dirty =
            (old.keys + listed.keys).filterTo(HashSet()) { key ->
                key.profileSerial in readable && isPresent(key.profileSerial) &&
                    isDirty(scope, key, old[key], listed[key], changed, forced)
            }
        val failedProfiles = profiles.filterValues { it.status == ProfileReadStatus.FAILED }.keys
        return SyncTargets(readable, failedProfiles, stamps, listed, dirty)
    }

    // 読めるプロファイルの組 1 つを読み直すか。全件の作り直しと、一覧か索引の片方にしかない組は必ず読む
    private fun isDirty(
        scope: SyncScope,
        key: PackageKey,
        old: List<StoredApp>?,
        now: List<ActivityStamp>?,
        changed: Set<String>,
        forced: Set<PackageKey>,
    ): Boolean {
        if (scope.mode == SyncMode.FULL_REBUILD || old == null || now == null) return true
        val stamp = now.first()
        // 仕事用は変更の番号に出ないので、APK の指紋で見る
        val fingerprintChanged =
            !stamp.isCurrentProfile &&
                (stamp.apkFingerprint == null || old.first().apkFingerprint != stamp.apkFingerprint)
        val timeChanged =
            !scope.sequenceUsable &&
                (stamp.packageUpdatedAt == 0L || old.first().packageUpdatedAt != stamp.packageUpdatedAt)
        return key in forced || (scope.sequenceUsable && key.packageName in changed) ||
            old.any { it.apkFingerprint == null } || fingerprintChanged || timeChanged ||
            old.mapTo(HashSet()) { it.app.id } != now.mapTo(HashSet()) { it.id }
    }

    /**
     * 詳細の読み取り結果 [candidates] から計画を作る。
     *
     * @param sequence 今の変更の番号。次の状態に保存する
     * @param genres ジャンルの問い合わせの結果。失敗なら null
     * @param candidates [SyncTargets.dirtyPackages] の詳細。読み直す組がなければ空で、読まなかったものとして扱う
     */
    fun plan(
        scope: SyncScope,
        device: DeviceState,
        targets: SyncTargets,
        isPresent: (Long) -> Boolean,
        sequence: Int,
        genres: Map<AppId, List<GenreId>>?,
        candidates: Map<Long, ProfileRead<InstalledApp>>,
    ): SyncPlan {
        val dirty = targets.dirty
        val listed = targets.listed
        // 一覧と詳細の両方を読めたプロファイルだけで、作り直しと削除を決める
        val completed =
            if (dirty.isEmpty()) {
                targets.readable
            } else {
                targets.readable intersect candidates.filterValues { it.status == ProfileReadStatus.COMPLETE }.keys
            }
        val failed = HashSet<PackageKey>()
        candidates.forEach { (serial, result) ->
            result.failedPackages.forEach { failed += PackageKey(serial, it) }
            result.items.filter { !it.complete || it.apkFingerprint == null }.forEach { failed += it.key }
        }
        dirty.filterTo(failed) { it.profileSerial in targets.readable && it.profileSerial !in completed }
        val read =
            candidates.values
                .flatMap { it.items }
                .filter { it.key in dirty && it.entry.id.profileSerial in completed && isPresent(it.entry.id.profileSerial) }
                .map { app ->
                    // 一覧と詳細の間に APK が替わったら、どちらの版の材料か決められない
                    val fingerprint = listed[app.key]?.firstOrNull()?.apkFingerprint
                    if (fingerprint != null && app.apkFingerprint != fingerprint) {
                        failed += app.key
                        app.copy(apkFingerprint = null, complete = false)
                    } else {
                        app
                    }
                }
        val present = read.mapTo(HashSet()) { it.key }
        // 一覧にあった入口が詳細で消えた場合は競合かもしれず、正常な削除と決めない
        dirty.filterTo(failed) { it in listed && it !in present }
        val removed = dirty.filterTo(HashSet()) { it !in listed && it.profileSerial in completed && it !in failed }
        return SyncPlan(
            mode = scope.mode,
            // 読めなかった入口は指紋なしで保存され、再開後に作り直される。前の言語・ビルドを残すと、
            // 再開までの同期がすべて全件の作り直しになり、読めたプロファイルを毎回作り直してしまう。
            state = SyncState(sequence, device.bootCount, device.locales, device.buildFingerprint),
            rebuild = read,
            removedPackages = removed,
            readable = completed,
            failedPackages = failed,
            changedPackages = targets.dirtyPackages,
            genres = genres,
            failedProfiles = (targets.failedProfiles + candidates.filterValues { it.status == ProfileReadStatus.FAILED }.keys).size,
        )
    }
}

internal val InstalledApp.key: PackageKey get() = PackageKey(entry.id.profileSerial, entry.id.packageName)

internal val StoredApp.key: PackageKey get() = PackageKey(app.id.profileSerial, app.id.packageName)
