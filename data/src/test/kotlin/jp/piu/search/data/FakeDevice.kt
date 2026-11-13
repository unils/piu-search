// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.reading.ReadingToken
import jp.piu.search.core.search.AppId

/** 偽の端末の主プロファイルのシリアル番号。 */
internal const val PERSONAL = 0L

/** 偽の端末の仕事用プロファイルのシリアル番号。 */
internal const val WORK = 11L

/** 偽の端末。パッケージの変更を番号つきで記録する（silently は番号に出ない変更）。 */
internal class FakeDevice : DeviceApps {
    var bootCount: Int? = 1
    var fingerprint = "test-build"

    override fun buildFingerprint(): String = fingerprint

    val failedProfiles = HashSet<Long>()
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

    fun existingProfiles(): Set<Long> {
        check(!failUserQuery)
        return profiles.toSet()
    }

    fun install(
        vararg installed: InstalledApp,
        silently: Boolean = false,
    ) {
        for (app in installed) {
            apps[app.entry.id] =
                if (app.complete && app.apkFingerprint == null &&
                    app.packageUpdatedAt != 0L
                ) {
                    app.copy(apkFingerprint = "apk:${app.packageUpdatedAt}")
                } else {
                    app
                }
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

    override fun profileSerials(): Set<Long> = profiles - unavailable

    override fun stamps(withUpdateTime: Boolean): List<ActivityStamp> {
        stampCalls++
        beforeStamps()
        return apps.values
            .filter { it.entry.id.profileSerial !in unavailable }
            .map {
                ActivityStamp(
                    it.entry.id,
                    if (withUpdateTime) it.packageUpdatedAt else 0,
                    it.apkFingerprint,
                    it.entry.id.profileSerial == PERSONAL,
                )
            }
    }

    override fun stampProfiles(withUpdateTime: Boolean): Map<Long, ProfileRead<ActivityStamp>> =
        super.stampProfiles(withUpdateTime).mapValues { (serial, result) ->
            if (serial in failedProfiles) ProfileRead(ProfileReadStatus.FAILED) else result
        }

    override fun genres(stamps: List<ActivityStamp>): Map<AppId, List<jp.piu.search.core.search.GenreId>> {
        genresCalls++
        return apps.values.associate { it.entry.id to it.entry.genres }
    }

    override fun read(packages: Set<String>?): List<InstalledApp> {
        beforeRead()
        return apps.values.filter { it.entry.id.profileSerial !in unavailable && (packages == null || it.entry.id.packageName in packages) }
    }
}

/** 呼ばれた回数を数える、読みを付けない形態素解析器。作り直さない同期で使わないことを確かめる。 */
internal class CountingAnalyzer : ReadingAnalyzer {
    var calls = 0

    override fun analyze(text: String): List<ReadingToken> {
        calls++
        return if (text.isBlank()) emptyList() else listOf(ReadingToken(text, null, 0))
    }
}
