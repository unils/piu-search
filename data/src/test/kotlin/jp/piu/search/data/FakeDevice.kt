// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.UserHandle
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.reading.ReadingToken
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId

/** 偽の端末の主プロファイルのシリアル番号。 */
internal const val PERSONAL = 0L

/** 偽の端末の仕事用プロファイルのシリアル番号。 */
internal const val WORK = 11L

/** 偽の端末の仕事用プロファイルのユーザー id。 */
internal const val WORK_USER_ID = 10

/** ユーザー 1 つの uid の幅（AOSP の `UserHandle.PER_USER_RANGE`。公開 API にないので値を写す）。 */
private const val PER_USER_RANGE = 100_000

/** 偽の端末の入口の表示名の資源の id（[FakeDevice.labelReader] を渡したとき）。 */
internal const val LABEL_RES = 0x7f0b0001

/**
 * 偽の端末に入れるアプリの入口 1 つ。指紋を省くと、時刻から作る（時刻もなければ鍵なし）。
 *
 * @property archived アーカイブ済みか
 */
internal data class InstalledApp(
    val entry: AppEntry,
    val packageUpdatedAt: Long?,
    val apkFingerprint: String? = null,
    val archived: Boolean = false,
) {
    val key: PackageKey get() = PackageKey(entry.id.profileSerial, entry.id.packageName)

    val fingerprint: String? get() = apkFingerprint ?: packageUpdatedAt?.let { "apk:$it" }
}

/**
 * 偽の端末。パッケージの変更を番号つきで記録する（silently は番号に出ない変更）。
 *
 * 段の失敗は「読み切れたか」で渡さず、段ごとの例外か結果で渡す（[fail]・[vanished]・[listingLabelFailures]・[keysDuringRead]）。
 * 組み立てと分類は本物の [readPackage] と [MaterialCauses] を通す（testing.md の「分類」）。
 * [labelReader]・[signerReader] を渡すと、別の言語の名前と署名も本物の読み手で読む（入力の資源と PackageInfo はテストが偽装する）。
 * 渡さなければ、入口の [AppEntry.localizedLabels]・[AppEntry.signerNames] と [fail] の例外をそのまま返す。
 */
internal class FakeDevice : DeviceApps {
    var bootCount: Int? = 1
    var fingerprint = "test-build"

    override fun buildFingerprint(): String = fingerprint

    /** 一覧の問い合わせが例外になるプロファイル。 */
    val failedProfiles = HashSet<Long>()

    /** 詳細を読んだ後の確かめ直しが例外になるプロファイル。 */
    val failedDetailProfiles = HashSet<Long>()
    var genresCalls = 0
    var stampCalls = 0
    var locales = "ja-JP"
    private var sequence = 0
    private val changes = ArrayList<String>()
    private val apps = LinkedHashMap<AppId, InstalledApp>()
    private val profiles = linkedSetOf(PERSONAL, WORK)
    val unavailable = HashSet<Long>()
    var failUserQuery = false
    var beforeStamps: () -> Unit = {}
    var beforeRead: () -> Unit = {}

    /** 組の詳細の問い合わせの中で呼ぶ（読む前と読んだ後の鍵の観測の間）。 */
    var duringRead: (PackageKey) -> Unit = {}

    /** 別の言語の名前の本物の読み手。入口の表示名の資源の id は [LABEL_RES]。 */
    var labelReader: LocalizedLabelReader? = null

    /** 署名の本物の読み手。仕事用の入口の APK のパスはない（`ARCHIVE_UNREADABLE` になる）。 */
    var signerReader: SignerReader? = null

    /** 段の失敗。`ENTRY` は詳細の問い合わせ、`LABEL` は別の言語の名前、`SIGNER` は署名の読み取りで投げる。 */
    val failures = HashMap<Pair<PackageKey, MaterialStage>, Throwable>()

    /** 一覧の表示名の取得が例外になる入口。 */
    val listingLabelFailures = HashMap<AppId, RuntimeException>()

    /** 詳細から消える入口（`VANISHED`）。 */
    val vanished = HashSet<AppId>()

    /** 読み取りの前（0）・後（1）の鍵を差し替える。null なら今の値。 */
    var keysDuringRead: (PackageKey, Int) -> ApkKeys? = { _, _ -> null }

    /** ジャンルの問い合わせで投げる例外。 */
    var genreFailure: RuntimeException? = null

    /** 詳細を読みに行った組（同期をまたいで記録する）。 */
    val reads = ArrayList<PackageKey>()

    private var listed: Map<AppId, InstalledApp> = emptyMap()

    fun fail(
        app: InstalledApp,
        stage: MaterialStage,
        failure: Throwable,
    ) {
        failures[app.key to stage] = failure
    }

    fun existingProfiles(): Set<Long> {
        check(!failUserQuery)
        return profiles.toSet()
    }

    fun install(
        vararg installed: InstalledApp,
        silently: Boolean = false,
    ) {
        for (app in installed) {
            apps[app.entry.id] = app
            if (!silently && app.entry.id.profileSerial == PERSONAL) touch(app.entry.id.packageName)
        }
    }

    fun uninstall(
        packageName: String,
        profile: Long = PERSONAL,
        silently: Boolean = false,
    ) {
        apps.keys.removeAll { it.packageName == packageName && it.profileSerial == profile }
        if (!silently && profile == PERSONAL) touch(packageName)
    }

    fun touch(packageName: String) {
        changes += packageName
        sequence = changes.size
    }

    /** 再起動。変更の番号は 0 に戻る。 */
    fun reboot() {
        bootCount = bootCount!! + 1
        changes.clear()
        sequence = 0
    }

    fun removeProfile(serial: Long) {
        profiles -= serial
        apps.keys.removeAll { it.profileSerial == serial }
    }

    override fun bootCount(): Int? = bootCount

    override fun locales(): String = locales

    override fun changedPackages(sequence: Int): PackageChanges {
        val from = sequence.coerceIn(0, changes.size)
        return PackageChanges(this.sequence, changes.subList(from, changes.size).toSet())
    }

    override fun stampProfiles(withUpdateTime: Boolean): Map<Long, ProfileRead<ActivityStamp>> {
        stampCalls++
        val before = profiles - unavailable
        beforeStamps()
        val after = profiles - unavailable
        listed = apps.toMap()
        return profiles.associateWith { serial ->
            when {
                serial in failedProfiles -> {
                    ProfileRead(ProfileReadStatus.FAILED, failure = "android.os.DeadObjectException")
                }

                serial !in before || serial !in after -> {
                    ProfileRead(ProfileReadStatus.UNAVAILABLE)
                }

                else -> {
                    ProfileRead(
                        ProfileReadStatus.COMPLETE,
                        apps.values.filter { it.entry.id.profileSerial == serial }.map { it.stamp(withUpdateTime) },
                    )
                }
            }
        }
    }

    private fun InstalledApp.stamp(withUpdateTime: Boolean) =
        ActivityStamp(
            entry.id,
            if (withUpdateTime) packageUpdatedAt else null,
            fingerprint,
            entry.id.profileSerial == PERSONAL,
            isArchived = archived,
        )

    override fun listedKeys(targets: Map<PackageKey, List<ActivityStamp>>): Map<PackageKey, ApkKeys> =
        targets.keys.associateWith(::currentKeys)

    override fun readPackages(
        targets: Map<PackageKey, List<ActivityStamp>>,
        listedKeys: Map<PackageKey, ApkKeys>,
    ): Map<Long, ProfileRead<PackageRead>> {
        beforeRead()
        return targets.entries.groupBy { it.key.profileSerial }.mapValues { (serial, packages) ->
            if (serial in unavailable) return@mapValues ProfileRead(ProfileReadStatus.UNAVAILABLE)
            val result =
                packages.map { (key, stamps) ->
                    reads += key
                    val observe = { phase: Int -> keysDuringRead(key, phase) ?: currentKeys(key) }
                    readPackage(
                        key = key,
                        listed = stamps,
                        listedKeys = listedKeys.getValue(key),
                        labelOf = { id -> listingLabelFailures[id]?.let { throw it } ?: checkNotNull(listed[id]).entry.label },
                        observeKeys =
                            run {
                                var phase = 0
                                { observe(phase++) }
                            },
                        details = {
                            duringRead(key)
                            failures[key to MaterialStage.ENTRY]?.let { throw it }
                            apps.values.filter { it.key == key && it.entry.id !in vanished }.map { it.activity() }
                        },
                        readLabels = { activity ->
                            labelReader?.read(activity) ?: LabelRead(
                                apps[activity.id]?.entry?.localizedLabels.orEmpty(),
                                failures[key to MaterialStage.LABEL] as? Exception,
                            )
                        },
                        readSigners = { activity ->
                            signerReader?.read(activity) ?: run {
                                failures[key to MaterialStage.SIGNER]?.let { throw it }
                                apps[activity.id]?.entry?.signerNames.orEmpty()
                            }
                        },
                    )
                }
            when (serial) {
                in unavailable -> ProfileRead(ProfileReadStatus.UNAVAILABLE)
                in failedDetailProfiles -> ProfileRead(ProfileReadStatus.FAILED, failure = "java.lang.IllegalStateException")
                else -> ProfileRead(ProfileReadStatus.COMPLETE, result)
            }
        }
    }

    private fun currentKeys(key: PackageKey): ApkKeys =
        apps.values.firstOrNull { it.key == key }?.let { ApkKeys(it.packageUpdatedAt, it.fingerprint) } ?: ApkKeys(null, null)

    private fun InstalledApp.activity(): LaunchableActivity {
        val info =
            ApplicationInfo().apply {
                packageName = entry.id.packageName
                if (labelReader != null) labelRes = LABEL_RES
            }
        val user =
            if (entry.id.profileSerial ==
                PERSONAL
            ) {
                Process.myUserHandle()
            } else {
                UserHandle.getUserHandleForUid(WORK_USER_ID * PER_USER_RANGE)
            }
        return LaunchableActivity(entry.id, entry.label, user, info, activityLabelRes = 0)
    }

    override fun genres(stamps: List<ActivityStamp>): Map<AppId, List<GenreId>> {
        genresCalls++
        genreFailure?.let { throw it }
        return apps.values.associate { it.entry.id to it.entry.genres }
    }
}

/** 呼ばれた回数を数える、読みを付けない形態素解析器。作り直さない同期で使わないことを確かめる。[failOn] を含む文字列では落ちる。 */
internal class CountingAnalyzer : ReadingAnalyzer {
    var calls = 0
    var failOn: String? = null

    override fun analyze(text: String): List<ReadingToken> {
        calls++
        failOn?.let { check(it !in text) { "キーの生成の不具合" } }
        return if (text.isBlank()) emptyList() else listOf(ReadingToken(text, null, 0))
    }
}
