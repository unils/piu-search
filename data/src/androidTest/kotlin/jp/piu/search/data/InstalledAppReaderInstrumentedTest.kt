// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.PublisherWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 本物の LauncherApps と PackageManager を相手に、テスト用のアプリ（test-apps/）から索引の材料が作れることを確かめる。
// テスト用のアプリは connectedDebugAndroidTest の前に :test-apps:installTestApps が入れる
@RunWith(AndroidJUnit4::class)
class InstalledAppReaderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source = AppSource(context)

    /** テスト用のアプリの入口ごとの、日本語と英語の名前とジャンル（test-apps/ の資源とマニフェスト）。 */
    private val expected =
        listOf(
            Expected("game", "TestActivity", ja = "将棋テスト", en = "Shogi Test", genres = listOf("game")),
            Expected("mail", "TestActivity", ja = "テストメール", en = "Test Mail", genres = listOf("email")),
            Expected("maps", "TestActivity", ja = "テスト地図", en = "Test Map", genres = listOf("maps")),
            Expected("transit", "TestActivity", ja = "乗換案内テスト", en = "Transit Test", genres = emptyList()),
            Expected("twolaunchers", "SecondTestActivity", ja = "入口二つテスト 設定", en = "Two Entries Test Settings", genres = emptyList()),
            Expected("twolaunchers", "TestActivity", ja = "入口二つテスト", en = "Two Entries Test", genres = emptyList()),
            Expected("yahoo", "TestActivity", ja = "Yahoo!テスト", en = "Yahoo!テスト", genres = emptyList()),
        )

    @Test
    fun testAppsAreReadWithNamesAndGenres() {
        val me = source.profiles().first()
        assertTrue(me.isCurrent)
        val entries = testEntries().filter { it.id.profileSerial == me.serial }
        entries.forEach { Log.i(TAG, "${it.id.packageName}/${it.id.activityName}: ${it.label} ${it.localizedLabels} ${it.genres}") }

        // LAUNCHER を持たない nolauncher は出ず、2 つ持つ twolaunchers は 2 件
        assertEquals(expected.map { it.key }, entries.map { it.key }.sorted())

        val deviceLanguage =
            Resources
                .getSystem()
                .configuration.locales[0]
                .language
        for (want in expected) {
            val entry = entries.single { it.key == want.key }
            // 表示名は端末の言語の名前。別の言語の名前は、表示名と違う方だけ
            val label = if (deviceLanguage == "ja") want.ja else want.en
            if (deviceLanguage == "ja" || deviceLanguage == "en") assertEquals(want.key, label, entry.label)
            assertEquals(want.key, setOf(want.ja, want.en) - entry.label, entry.localizedLabels.toSet())
            assertEquals(want.key, want.genres.map(::GenreId), entry.genres)
        }
    }

    @Test
    fun signerOrganizationIsReadFromTheCertificate() {
        // テスト用のアプリは debug の鍵（C=US, O=Android, CN=Android Debug）で署名してある。権限なしで組織名まで読める（M6-01）
        val me = source.profiles().first()
        val entries = testEntries().filter { it.id.profileSerial == me.serial }
        assertTrue(entries.isNotEmpty())
        for (entry in entries) {
            Log.i(TAG, "${entry.id.packageName}: ${entry.signerNames}")
            val signer = entry.signerNames.single()
            // RFC 2253 の形（X500Principal.getName()）で、組織名まで読める
            assertEquals(entry.id.packageName, listOf("Android"), organizationsOf(signer))
            assertTrue(signer, "CN=Android Debug" in signer)
            // debug の鍵は提供元を表さないので、提供元の語にはしない
            assertEquals(signer, emptyList<String>(), PublisherWords.organizations(signer))
        }
    }

    @Test
    fun signerNamesOfAppsOutsideTheTestAppsAreReadable() {
        // 端末に初めから入っているアプリ（AOSP の像では試験用の鍵）の DN も読める。中身は端末で違うので、読めることだけを見る
        val others = InstalledAppReader(context).readAll().filterNot { it.id.packageName.startsWith(PREFIX) }
        others.forEach { Log.i(TAG, "${it.id.packageName}: ${it.signerNames}") }
        assertTrue(others.isEmpty() || others.any { it.signerNames.isNotEmpty() })
        // 手元で Google Play の版の APK を入れたときだけ、本物の証明書の組織名が読めることを見る（research/package-names.md の DN）。
        // 分割なしで入るのは、API 30 以上の楽天市場（分割がない）と、API 26 の Y!乗換案内（API 26 は必須の分割を確かめない）。APK はリポジトリに入れない
        val real = others.filter { it.id.packageName in REAL_ORGANIZATIONS }
        if (real.isEmpty()) return skip("Google Play の版の APK が入っていない")
        for (app in real) {
            Log.i(TAG, "本物の APK ${app.id.packageName}（${app.label}）: ${app.signerNames}")
            assertEquals(
                app.signerNames.toString(),
                REAL_ORGANIZATIONS[app.id.packageName],
                app.signerNames.flatMap(PublisherWords::organizations),
            )
        }
    }

    // PublisherWords は debug の鍵の組織名を外すので、読めたことは DN から直に見る
    private fun organizationsOf(dn: String): List<String> =
        dn
            .split(',')
            .map { it.trim() }
            .filter { it.startsWith("O=") }
            .map { it.removePrefix("O=") }

    @Test
    fun workProfileAppsHaveAnotherSerial() {
        // 仕事用プロファイルは CI の端末にない。手元で作ったときだけ確かめる（手順は CONTRIBUTING への提案）。
        // Assume で飛ばすと AGP の結果の XML に failure として残り紛らわしいので、記録して戻る
        val profiles = source.profiles()
        if (profiles.size < 2) return skip("仕事用プロファイルがない")
        val serials = profiles.map { it.serial }
        assertEquals(serials.distinct(), serials)

        val entries = testEntries()
        val work = profiles.drop(1).map { it.serial }.toSet()
        val workEntries = entries.filter { it.id.profileSerial in work }
        workEntries.forEach { Log.i(TAG, "work ${it.id}: ${it.label} ${it.localizedLabels} ${it.genres}") }
        if (workEntries.isEmpty()) return skip("仕事用プロファイルにテスト用のアプリが入っていない")
        val personal = entries.filter { it.id.profileSerial == profiles.first().serial }
        for (entry in workEntries) {
            val twin = personal.single { it.key == entry.key }
            assertNotEquals(twin.id, entry.id)
            // 同じ APK なので、名前とジャンルは主プロファイルと同じ
            assertEquals(twin.label, entry.label)
            assertEquals(twin.localizedLabels, entry.localizedLabels)
            assertEquals(twin.genres, entry.genres)
        }
    }

    private fun skip(reason: String) {
        Log.w(TAG, "検査を飛ばした: $reason")
    }

    private fun testEntries(): List<AppEntry> = InstalledAppReader(context).readAll().filter { it.id.packageName.startsWith(PREFIX) }

    private val AppEntry.key get() = id.packageName.removePrefix(PREFIX) + "/" + id.activityName.substringAfterLast('.')

    private class Expected(
        module: String,
        activity: String,
        val ja: String,
        val en: String,
        val genres: List<String>,
    ) {
        val key = "$module/$activity"
    }

    private companion object {
        const val PREFIX = "jp.piu.search.testapp."
        const val TAG = "InstalledAppReaderTest"

        /**
         * Google Play の版の APK（2026-11-06 に取り出した）のパッケージ → どの API でも読めるはずの組織名。
         *
         * Y!乗換案内は鍵を替えていて（v3.1 の lineage）、API 32 以下の署名は Yahoo Japan Corporation、33 以上は LY Corporation。
         * API 28 以上は前の鍵も読むので、どの API でも Yahoo Japan Corporation が入る。
         */
        val REAL_ORGANIZATIONS =
            mapOf(
                "jp.co.rakuten.android" to listOf("Rakuten,Inc."),
                "jp.co.yahoo.android.apps.transit" to listOf("Yahoo Japan Corporation"),
            )
    }
}
