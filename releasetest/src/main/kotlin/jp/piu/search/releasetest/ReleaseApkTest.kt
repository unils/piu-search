// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.releasetest

import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R8 を通した版（app の minified）を、アプリの外から試す（testing.md の「release の APK の確認」の 3〜5）。
 *
 * R8 で名前が変わると壊れる部品を、使う操作で確かめる。
 * - ブランド表（`BrandTable` からの相対の資源）: 「やふー」で Yahoo!テスト
 * - Kuromoji の辞書（`Tokenizer$Builder` からの相対の資源）: 漢字の名前の索引と、漢字の検索語「乗り換え」
 * - Room（`_Impl` をリフレクションで探す）: 止めて開き直すと、保存した索引から差分で同期し、起動の記録が残る
 * - 呼び出しの入口（アシスタント・タイル）: マニフェストから引く Activity と TileService
 */
@RunWith(AndroidJUnit4::class)
class ReleaseApkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val restore = ArrayList<() -> Unit>()

    @Before
    fun startClean() {
        // 前のテストの索引・記録・設定を残さない。最初の同期は全件の作り直しになる
        shell("am force-stop $APP")
        shell("pm clear $APP")
        shell("logcat -c")
    }

    @After
    fun cleanUp() {
        restore.asReversed().forEach { it() }
        shell("input keyevent KEYCODE_HOME")
        shell("am force-stop $APP")
        shell("am force-stop $TRANSIT")
    }

    @Test
    fun searchesByRomajiBrandReadingKanjiAndGenre() {
        openSearch()
        val cases =
            listOf(
                "norikae" to label("乗換案内テスト", "Transit Test"),
                // ブランド表の読み。M2-04 では release でだけ表が見つからず、索引を作れなかった
                "やふー" to "Yahoo!テスト",
                // 漢字の検索語は、Kuromoji で読みを付けてから照らす
                "乗り換え" to label("乗換案内テスト", "Transit Test"),
                "ちず" to label("テスト地図", "Test Map"),
            )
        for ((query, expected) in cases) {
            searchField().text = query
            // 出ないときは、索引を作れたかを添える（M2-04 では release でだけ同期が失敗した）
            waitFor("「$query」で「$expected」が出る", detail = { "同期の記録: ${syncLog()}" }) { hasLabel(expected) }
        }
        val sync = syncLog()
        assertTrue("全件を作り直した記録: $sync", sync.any { "mode=FULL_REBUILD" in it })
        assertFalse("同期の失敗: $sync", sync.any { FAILED in it })
    }

    @Test
    fun restartLoadsSavedIndexAndKeepsLaunchRecord() {
        val transit = label("乗換案内テスト", "Transit Test")
        openSearch()
        // 初回の案内を閉じる。案内は検索語が空の間、最近使ったアプリの代わりに出る
        device.wait(Until.findObject(By.res(ONBOARDING_CLOSE)), TIMEOUT_MILLIS)?.click()
        searchField().text = "norikae"
        waitFor("「$transit」が出る") { hasLabel(transit) }
        waitFor("全件の同期が済む") { syncLog().any { "mode=FULL_REBUILD" in it } }
        findLabel(transit)!!.click()
        waitFor("テスト用のアプリが前に出る") { resumedActivityLines().any { TRANSIT in it } }

        // プロセスを止めて開き直す。保存した索引を読み、変更の番号で差分だけを見る
        shell("am force-stop $APP")
        shell("logcat -c")
        openSearch()
        // 検索語が空の間は、最近使ったアプリを出す。起動の記録がデータベースから読めている
        waitFor("最近使ったアプリに「$transit」が出る") { hasLabel(transit) }
        waitFor("差分の同期が済む") { syncLog().isNotEmpty() }
        val sync = syncLog()
        assertTrue("差分で同期した記録: $sync", sync.all { "mode=CHANGES" in it })
        assertTrue("作り直したアプリがない: $sync", sync.all { "rebuiltApps=0" in it })
    }

    @Test
    fun assistKeyAndTileOpenTheSameScreen() {
        becomeAssistant()
        shell("input keyevent KEYCODE_HOME")
        // 電源ボタンの長押し・隅からのスワイプと同じく、SystemUI が ACTION_ASSIST で開く
        shell("input keyevent KEYCODE_ASSIST")
        val field = searchField()
        field.text = "norikae"
        waitFor("検索語が入る") { searchField().text == "norikae" }

        // タイルから呼び直すと、同じ画面が受けて空の検索語に戻る（singleTask）
        val tile = "$APP/.SearchTileService"
        restore += {
            shell("cmd statusbar collapse")
            shell("cmd statusbar remove-tile $tile")
        }
        shell("cmd statusbar add-tile $tile")
        shell("cmd statusbar expand-settings")
        // 足したばかりのタイルは、SystemUI がサービスにつなぎ終えるまで押しても無視される。空に戻るまで押し直す
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!searchFieldIsEmpty()) {
            assertTrue("時間内にタイルから開かなかった", SystemClock.uptimeMillis() < deadline)
            shell("cmd statusbar click-tile $tile")
            val until = SystemClock.uptimeMillis() + TILE_RETRY_MILLIS
            while (SystemClock.uptimeMillis() < until && !searchFieldIsEmpty()) SystemClock.sleep(POLL_MILLIS)
        }
        val records = shell("dumpsys activity activities").lines().count { "Hist" in it && "$APP/.SearchActivity" in it }
        assertEquals("アシスタントとタイルから開いた画面の数", 1, records)
    }

    private fun openSearch() {
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $APP/.SearchActivity")
        searchField()
    }

    private fun searchField(): UiObject2 =
        checkNotNull(device.wait(Until.findObject(By.res(SEARCH_FIELD)), TIMEOUT_MILLIS)) {
            "検索窓が出ない。前面: ${resumedActivityLines()}"
        }

    /** 検索窓から打った文字が消えたか。空の検索窓は、文字の代わりに案内（placeholder）を読ませるので、空の文字列とは比べない。 */
    private fun searchFieldIsEmpty(): Boolean {
        val field = device.findObject(By.res(SEARCH_FIELD)) ?: return false
        return runCatching { field.text != "norikae" }.getOrDefault(false)
    }

    /** 結果の行は名前と出た理由を 1 つの読み上げにまとめる（M5-02）ので、文字と説明の両方を見る。 */
    private fun findLabel(label: String): UiObject2? =
        device.findObject(By.textContains(label)) ?: device.findObject(By.descContains(label))

    private fun hasLabel(label: String): Boolean = findLabel(label) != null

    /** 索引の同期の記録（logcat のタグ PiuIndexSync）。 */
    private fun syncLog(): List<String> =
        shell("logcat -d -s $SYNC_TAG:*")
            .lines()
            .filter { "索引を同期した" in it || FAILED in it }

    private fun becomeAssistant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val before =
                shell("cmd role get-role-holders $ROLE")
                    .trim()
                    .split(';', '\n')
                    .filter { it.isNotBlank() }
            restore += {
                shell("cmd role remove-role-holder $ROLE $APP")
                before.forEach { shell("cmd role add-role-holder $ROLE $it") }
            }
            shell("cmd role add-role-holder $ROLE $APP")
            waitFor("既定のアシスタントになる") { APP in shell("cmd role get-role-holders $ROLE") }
        } else {
            // Android 9 以前は役割がなく、設定の値で既定のアシスタントを決める
            val before = shell("settings get secure assistant").trim()
            restore += {
                if (before == "null" || before.isEmpty()) {
                    shell("settings delete secure assistant")
                } else {
                    shell("settings put secure assistant $before")
                }
            }
            shell("settings put secure assistant $APP/.SearchActivity")
        }
    }

    private fun resumedActivityLines(): List<String> = shell("dumpsys activity activities").lines().filter { "ResumedActivity" in it }

    private fun shell(command: String): String = device.executeShellCommand(command)

    private fun waitFor(
        what: String,
        detail: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                fail("時間内に「$what」にならなかった。前面: ${resumedActivityLines()}。${detail()}")
            }
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun label(
        japanese: String,
        english: String,
    ): String =
        if (Resources
                .getSystem()
                .configuration.locales[0]
                .language == "ja"
        ) {
            japanese
        } else {
            english
        }

    private companion object {
        const val APP = "jp.piu.search"
        const val TRANSIT = "jp.piu.search.testapp.transit"
        const val ROLE = "android.app.role.ASSISTANT"

        // アプリの testTag（SearchScreen.kt・OnboardingCard.kt）。testTagsAsResourceId で resource-id として見える
        const val SEARCH_FIELD = "search_field"
        const val ONBOARDING_CLOSE = "onboarding_close"
        const val SYNC_TAG = "PiuIndexSync"
        const val FAILED = "索引の同期に失敗した"

        // 最初の同期は全件の作り直し（辞書の読み込みを含む）で、遅いエミュレータでは数秒かかる
        const val TIMEOUT_MILLIS = 60_000L
        const val TILE_RETRY_MILLIS = 3_000L
        const val POLL_MILLIS = 200L
    }
}
