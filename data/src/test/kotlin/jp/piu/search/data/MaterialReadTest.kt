// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

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

    @Test
    fun localizedLabelFailureMarksThePackageIncompleteAndTheNextReadRetries() {
        val activity = activity(labelRes = 0x7f7fffff)
        val labels = LocalizedLabelReader(context)
        assertThrows(MaterialReadException::class.java) { labels.read(activity) }
        val reader = InstalledAppReader(context)
        val partial = reader.readPackage(listOf(activity), readSigners = { emptyList() }).single()
        assertFalse(partial.complete)
        assertNull(partial.apkFingerprint)
        val retried = reader.readPackage(listOf(activity), readLabels = { listOf("Other name") }, readSigners = { emptyList() }).single()
        assertNotNull(retried.apkFingerprint)
    }

    @Test
    fun unreadableSignatureIsAFailureAndNotAnEmptySignerList() {
        val activity = activity()
        assertThrows(MaterialReadException::class.java) { SignerReader(context.packageManager).read(activity) }
        val partial = InstalledAppReader(context).readPackage(listOf(activity), readLabels = { emptyList() }).single()
        assertFalse(partial.complete)
        assertNull(partial.apkFingerprint)
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

    private fun activity(labelRes: Int = 0): LaunchableActivity {
        val base = files.newFile().apply { setLastModified(1_000) }
        val info =
            ApplicationInfo().apply {
                packageName = context.packageName
                sourceDir = base.path
            }
        return LaunchableActivity(AppId(context.packageName, "Main", 0), "名前", Process.myUserHandle(), info, labelRes)
    }
}
