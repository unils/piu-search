// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.IOException
import java.security.cert.CertificateException

@RunWith(AndroidJUnit4::class)
class MaterialReadTest {
    @get:Rule val files = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun fingerprintIncludesBaseAndSplitPathsAndTimes() {
        val base = files.newFile("base.apk").apply { setLastModified(1_000) }
        val split = files.newFile("language.apk").apply { setLastModified(1_000) }
        val info =
            ApplicationInfo().apply {
                sourceDir = base.path
                splitSourceDirs = arrayOf(split.path)
            }
        val before = apkFingerprint(info)
        assertNotNull(before)
        split.setLastModified(2_000)
        assertNotEquals(before, apkFingerprint(info))
        val updated = apkFingerprint(info)
        info.splitSourceDirs = emptyArray()
        assertNotEquals(updated, apkFingerprint(info))
        info.sourceDir = files.newFile("moved-base.apk").apply { setLastModified(1_000) }.path
        assertNotEquals(before, apkFingerprint(info))
    }

    // 時刻が同じでも大きさが違えば違う指紋（時刻が 0 に固定される APEX の中の APK の版の違い）
    @Test
    fun fingerprintIncludesTheSize() {
        val base = files.newFile("base.apk").apply { setLastModified(0) }
        val info = ApplicationInfo().apply { sourceDir = base.path }
        val before = apkFingerprint(info)
        base.writeBytes(ByteArray(10))
        base.setLastModified(0)
        assertNotEquals(before, apkFingerprint(info))
        assertTrue(checkNotNull(apkFingerprint(info)).endsWith(":0:10"))
    }

    @Test
    fun missingFileHasNoFingerprint() {
        val base = files.newFile("base.apk")
        val info = ApplicationInfo().apply { sourceDir = base.path }
        base.delete()
        assertNull(apkFingerprint(info))
        info.sourceDir = files.newFolder("split-dir").path
        assertNull(apkFingerprint(info))
    }

    // APEX の中の APK は時刻が 0 になる。読めないことと区別し、指紋を持たせる（毎回の同期で失敗し続けないため）
    @Test
    fun fileWithZeroTimestampHasAStableFingerprint() {
        val base = files.newFile("base.apk").apply { setLastModified(0) }
        val info = ApplicationInfo().apply { sourceDir = base.path }
        val fingerprint = apkFingerprint(info)
        assertNotNull(fingerprint)
        assertEquals(fingerprint, apkFingerprint(info))
        info.sourceDir = files.newFile("next-version.apk").apply { setLastModified(0) }.path
        assertNotEquals(fingerprint, apkFingerprint(info))
    }

    // パッケージの情報を引けないときはファイルの時刻を使う。時刻 0 は値として返し、読めないときだけ不明にする
    @Test
    fun fileUpdateTimeOfZeroIsATimeAndAMissingFileIsUnknown() {
        val base = files.newFile("gone.apk").apply { setLastModified(0) }
        val info =
            ApplicationInfo().apply {
                packageName = GONE
                sourceDir = base.path
            }
        val activity = LaunchableActivity(AppId(GONE, "Main", 0), "名前", Process.myUserHandle(), info, 0)
        val source = AppSource(context)
        assertEquals(0L, source.packageUpdatedAt(activity))
        base.delete()
        assertNull(source.packageUpdatedAt(activity))
    }

    // 0.1.x は読み手が MaterialReadException に丸め、型を捨てていた。元の型を段の境界まで通す
    @Test
    fun missingLabelResourceIsADeterministicLabelIssueWithItsType() {
        val activity = activity(labelRes = 0x7f7fffff)
        val read = LocalizedLabelReader(context).read(activity)
        assertEquals(emptyList<String>(), read.names)
        assertTrue(read.failure is android.content.res.Resources.NotFoundException)
        val failure = ReadFailure.of(checkNotNull(read.failure))
        assertEquals("android.content.res.Resources\$NotFoundException", failure.cause)
        assertEquals(IssueCategory.DETERMINISTIC, MaterialCauses.categoryOf(MaterialStage.LABEL, failure.cause))
        assertNull(failure.frames(MaterialStage.LABEL))
    }

    @Test
    fun labelThatIsNotAResourceIsANormalAbsence() {
        val read = LocalizedLabelReader(context).read(activity(labelRes = null))
        assertEquals(emptyList<String>(), read.names)
        assertNull(read.failure)
    }

    @Test
    fun signerFailuresKeepTheirOwnCodes() {
        val signers = SignerReader(context.packageManager)
        // 自分のプロファイルで一覧の後に消えたアプリは NameNotFound（一時的）。APK のファイルの読み取りに流さない
        val gone =
            assertThrows(PackageManager.NameNotFoundException::class.java) {
                signers.read(activity(packageName = GONE))
            }
        assertEquals(IssueCategory.TRANSIENT, MaterialCauses.categoryOf(MaterialCauses.causeOf(gone)))
        // 仕事用は APK のファイルから読む。解釈できなければ ARCHIVE_UNREADABLE（決定的）
        val unreadable = assertThrows(MaterialReadException::class.java) { signers.read(activity(user = UserHandle.getUserHandleForUid(1_000_000))) }
        assertEquals(MaterialCauses.ARCHIVE_UNREADABLE, ReadFailure.of(unreadable).cause)
        // 署名のない PackageInfo は NO_SIGNERS（決定的）で、ARCHIVE_UNREADABLE と別の符号
        val none = assertThrows(MaterialReadException::class.java) { signers.read(activity()) }
        assertEquals(MaterialCauses.NO_SIGNERS, ReadFailure.of(none).cause)
        assertEquals(IssueCategory.DETERMINISTIC, MaterialCauses.categoryOf(MaterialCauses.NO_SIGNERS))
    }

    // 失敗の分け方の表の行ごと
    @Test
    fun causesAndCategoriesFollowTheTable() {
        val cases =
            listOf(
                PackageManager.NameNotFoundException() to IssueCategory.TRANSIENT,
                DeadObjectException() to IssueCategory.TRANSIENT,
                RuntimeException(DeadObjectException()) to IssueCategory.TRANSIENT,
                IOException() to IssueCategory.TRANSIENT,
                java.io.FileNotFoundException() to IssueCategory.TRANSIENT,
                android.content.res.Resources.NotFoundException() to IssueCategory.DETERMINISTIC,
                CertificateException() to IssueCategory.DETERMINISTIC,
                SecurityException() to IssueCategory.DETERMINISTIC,
                IllegalStateException() to IssueCategory.SUSPECTED_BUG,
                RuntimeException() to IssueCategory.SUSPECTED_BUG,
                Exception() to IssueCategory.SUSPECTED_BUG,
            )
        for ((throwable, category) in cases) {
            val failure = ReadFailure.of(throwable)
            assertEquals(throwable.toString(), category, MaterialCauses.categoryOf(MaterialStage.ENTRY, failure.cause))
        }
        assertEquals("android.os.DeadObjectException", MaterialCauses.causeOf(RuntimeException(DeadObjectException())))
        // 固定の符号
        assertEquals(IssueCategory.TRANSIENT, MaterialCauses.categoryOf(MaterialCauses.VANISHED))
        assertEquals(IssueCategory.TRANSIENT, MaterialCauses.categoryOf(MaterialCauses.CHANGED_DURING_READ))
        assertEquals(IssueCategory.DETERMINISTIC, MaterialCauses.categoryOf(MaterialCauses.ARCHIVE_UNREADABLE))
        // キーの生成は理由によらず不具合の疑い
        assertEquals(IssueCategory.SUSPECTED_BUG, MaterialCauses.categoryOf(MaterialStage.KEYS, IOException::class.java.name))
    }

    // このアプリ・ライブラリ・Kotlin の例外の理由は、java.・android. で始まる最寄りの親（R8 で名前が変わらない）
    @Test
    fun ownLibraryAndKotlinExceptionsAreNamedByTheirNearestPlatformParent() {
        assertEquals("java.lang.IllegalStateException", MaterialCauses.causeOf(OwnFailure()))
        assertEquals("java.lang.RuntimeException", MaterialCauses.causeOf(UninitializedPropertyAccessException()))
        assertEquals("java.lang.RuntimeException", MaterialCauses.causeOf(jp.piu.search.core.search.KeyGenerationException(jp.piu.search.core.search.KeyInput.LABEL, 0, RuntimeException())))
    }

    @Test
    fun onlySuspectedBugsKeepFiveFramesWithoutTheMessage() {
        val bug = ReadFailure.of(OwnFailure("/data/app/secret/base.apk"))
        val frames = checkNotNull(bug.frames(MaterialStage.ENTRY))
        assertEquals(5, frames.lines().size)
        assertTrue(frames, frames.lines().first().startsWith("jp.piu.search.data.MaterialReadTest"))
        assertFalse(frames, "secret" in frames)
        assertNull(ReadFailure.of(IOException("/data/app/secret")).frames(MaterialStage.ENTRY))
        assertFalse(bug.cause.contains("secret"))
    }

    // 分類 → 保存 → 読み戻し → 分類で区分が変わらない
    @Test
    fun categorySurvivesStoringAndReloading() {
        val database = LocalDatabase.open(context, null, allowMainThread = true)
        try {
            val key = PackageKey(0, "io.example.app")
            val issues =
                listOf(
                    RuntimeException(DeadObjectException()) to MaterialStage.ENTRY,
                    CertificateException() to MaterialStage.SIGNER,
                    OwnFailure() to MaterialStage.LABEL,
                ).map { (throwable, stage) ->
                    val decision = SyncPlanner.issue(key, stage, ReadFailure.of(throwable), null, null, false, 3, "2026-11-15")
                    (decision as IssueDecision.Put).issue
                }
            database.index.replaceAll(listOf(StoredApp(jp.piu.search.core.search.IndexedApp(AppId(key.packageName, "Main", 0), "名前", emptyList()), 1)), SyncState(1, 1, "ja-JP"))
            database.index.saveIfCurrent(SyncState(1, 1, "ja-JP"), IndexChange(false, emptyList(), putIssues = issues, state = SyncState(1, 1, "ja-JP"), savedAt = 9))
            val reloaded = database.index.load().issues
            assertEquals(issues.toSet(), reloaded.toSet())
            assertEquals(
                listOf(IssueCategory.TRANSIENT, IssueCategory.SUSPECTED_BUG, IssueCategory.DETERMINISTIC),
                reloaded.sortedBy { it.stage.ordinal }.map { it.category },
            )
            assertEquals("android.os.DeadObjectException", reloaded.single { it.stage == MaterialStage.ENTRY }.cause)
            assertEquals(3, reloaded.first().versionCode)
            assertEquals(IssueCategory.SUSPECTED_BUG, MaterialCauses.categoryOf("x.y.NotAClass"))
        } finally {
            database.close()
        }
    }

    @Test
    fun keysInputSummaryNamesTheInputWithoutItsText() {
        val analyzer = CountingAnalyzer().apply { failOn = "爆" }
        val generator = jp.piu.search.core.search.SearchKeyGenerator(analyzer, jp.piu.search.core.search.LatinWordReader(jp.piu.search.core.search.BrandTable.parse(emptyList())))
        val id = AppId("io.example.app", "Main", 0)
        fun summary(entry: jp.piu.search.core.search.AppEntry): String {
            val e = assertThrows(RuntimeException::class.java) { generator.generate(entry) }
            return InputSummary.of(entry, e)
        }
        assertEquals("label:len6:kanji,kana", summary(jp.piu.search.core.search.AppEntry(id, "爆発テスト乗")))
        assertEquals("localized:len4:kanji,alnum", summary(jp.piu.search.core.search.AppEntry(id, "名前", localizedLabels = listOf("Ok", "爆Map"))))
        val signer = summary(jp.piu.search.core.search.AppEntry(id, "名前", signerNames = listOf("O=爆😀")))
        assertEquals("signer:len4:kanji,alnum,emoji,other", signer)
        assertFalse(signer.contains("爆"))
        assertEquals("unknown", InputSummary.of(jp.piu.search.core.search.AppEntry(id, "名前"), IllegalStateException()))
    }

    @Test
    fun unlockedAndUnavailableProfilesAreDifferentFromACompleteEmptyList() {
        val manager = context.getSystemService(android.os.UserManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        val user = Process.myUserHandle()
        shadow.setSerialNumberForUser(user, 0)
        shadow.setUserState(user, org.robolectric.shadows.ShadowUserManager.UserState.STATE_RUNNING_UNLOCKED)
        val source = AppSource(context)
        org.junit.Assert.assertEquals(ProfileReadStatus.COMPLETE, source.profileReadStatus(0))
        shadow.setUserState(user, org.robolectric.shadows.ShadowUserManager.UserState.STATE_RUNNING_LOCKED)
        org.junit.Assert.assertEquals(ProfileReadStatus.UNAVAILABLE, source.profileReadStatus(0))
        org.junit.Assert.assertEquals(ProfileReadStatus.UNAVAILABLE, source.stampProfiles(false).getValue(0).status)
    }

    private companion object {
        // 端末に入っていないパッケージ。PackageManager から引けず、ファイルの時刻に落ちる
        const val GONE = "io.example.gone"
    }

    private fun activity(
        labelRes: Int? = 0,
        packageName: String = context.packageName,
        user: UserHandle = Process.myUserHandle(),
    ): LaunchableActivity {
        val base = files.newFile().apply { setLastModified(1_000) }
        val info =
            ApplicationInfo().apply {
                this.packageName = packageName
                sourceDir = base.path
            }
        return LaunchableActivity(AppId(packageName, "Main", 0), "名前", user, info, labelRes)
    }

    /** このアプリの例外。R8 で名前が変わる側。 */
    private class OwnFailure(
        message: String? = null,
    ) : IllegalStateException(message)
}
