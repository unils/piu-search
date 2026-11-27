// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.releasetest

import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
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
 * R8 を通した版（app の benchmarkRelease）を、アプリの外から試す（testing.md の「release の APK の確認」の 3〜5）。
 *
 * R8 で名前が変わると壊れる部品を、使う操作で確かめる。
 * - ブランド表（`BrandTable` からの相対の資源）: 「やふー」で Yahoo!テスト
 * - Kuromoji の辞書（`Tokenizer$Builder` からの相対の資源）: 漢字の名前の索引と、漢字の検索語「乗り換え」
 * - Room（`_Impl` をリフレクションで探す）: 止めて開き直すと、保存した索引を読み戻して同期し（材料を読み直さず）、起動の記録が残る
 * - 呼び出しの入口（アシスタント・タイル）: マニフェストから引く Activity と TileService
 * - 「索引の状態」と報告用の文: 欠けの理由が `java.`・`android.` の名前のまま残る（R8 で変わらない名前だけを保存するため）。
 *   不具合の疑いの行に残すフレームが、行の番号とファイル名 `SourceFile` を持つ（proguard-rules.pro の属性の規則）
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
        // 新しく入れた直後の同期は、すべての組のキーを作る
        assertTrue("キーを作った記録: $sync", sync.any { BUILT.find(it) != null })
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
        waitFor("最初の同期が済む") { syncLog().any { BUILT.find(it) != null } }
        // 同期の記録は保存の前に出る。止める前に、索引を保存できたことを待つ（保存の前に止めると、開き直しで全件を作り直すため）
        waitFor("索引の保存が済む") { shell("logcat -d -s $SYNC_TAG:*").contains(SAVED) }
        findLabel(transit)!!.click()
        waitFor("テスト用のアプリが前に出る") { resumedActivityLines().any { TRANSIT in it } }

        // プロセスを止めて開き直す。保存した索引を読み戻し、同じ端末の状態なので材料もキーも作らない
        shell("am force-stop $APP")
        shell("logcat -c")
        openSearch()
        // 検索語が空の間は、最近使ったアプリを出す。起動の記録がデータベースから読めている
        waitFor("最近使ったアプリに「$transit」が出る") { hasLabel(transit) }
        waitFor("開き直した後の同期が済む") { syncLog().isNotEmpty() }
        val sync = syncLog()
        assertTrue("材料を読み直さない: $sync", sync.all { " read=0 " in it })
        assertTrue("キーを作り直さない: $sync", sync.all { " built=0 " in it })
        // 読み戻した索引で出ていても、同期そのものが失敗していれば通さない
        assertTrue("同期が失敗しない: $sync", sync.all { "failed=false" in it })
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

    @Test
    fun indexStatusOpensAndTheReportKeepsStableCauseNames() {
        // 設定は OS のアプリ情報の入口（ACTION_APPLICATION_PREFERENCES）から開く。exported なので shell から開ける
        shell("am start -W -n $APP/.SettingsActivity")
        checkNotNull(device.wait(Until.findObject(By.res(SETTINGS)), TIMEOUT_MILLIS)) { "設定が開かない" }
        waitFor("設定に「索引の状態」が出る") { scrolledTo(SETTINGS_INDEX_STATUS, SETTINGS) }
        checkNotNull(device.findObject(By.res(SETTINGS_INDEX_STATUS))).click()
        waitFor("「索引の状態」が開く") { device.findObject(By.res(INDEX_STATUS)) != null }
        waitFor("同期が済む", detail = { "同期の記録: ${syncLog()}" }) { syncLog().any { "索引を同期した" in it } }
        // 記録が出ても、画面がまだ同期の結果を取り込んでいないことがある。報告用の文は押した時点の中身で作るので、画面の反映を待つ
        val lastSync = label("最後の更新: ", "Last update: ")
        val notYet = lastSync + label("まだ更新していません", "Not updated yet")
        waitFor("画面に同期の結果が出る") { device.findObject(By.textStartsWith(lastSync))?.text?.let { it != notYet } == true }
        waitFor("報告のボタンに届く") { scrolledTo(INDEX_STATUS_REPORT, INDEX_STATUS) }
        checkNotNull(device.findObject(By.res(INDEX_STATUS_REPORT))).click()
        val text = checkNotNull(device.wait(Until.findObject(By.res(INDEX_REPORT_TEXT)), TIMEOUT_MILLIS)) { "報告用の文が出ない" }.text
        val lines = text.lines()
        assertEquals("Piu Search index status", lines.first())
        assertTrue("同期の結果: $text", lines.any { it.startsWith("last sync: result=") })
        // values-ja/ にだけ名前のあるテスト用のアプリは、英語の名前の資源がない（Resources.NotFoundException）。
        // ないことが決まっているので欠けにしない（成功した空）
        assertTrue("欠けの行: $text", lines.none { " $JA_ONLY " in it })
        // 理由は java.・android. の名前か固定の符号だけ
        val causes = lines.filter { it.startsWith("- ") }.map { it.substringAfter("cause=").substringBefore(' ') }
        assertTrue("理由: $causes", causes.all { it.startsWith("java.") || it.startsWith("android.") || it.matches(Regex("[A-Z_]+")) })
    }

    /**
     * 不具合の疑いの行に残すフレーム（architecture.md の「失敗の分け方」）が、R8 を通した版で行の番号を持ち、ファイル名が SourceFile になる。
     *
     * 外から本物の不具合を起こす手段がないので、入れた APK のクラスをこのテストのプロセスに読み込み、マニフェストにあって名前の残る
     * SearchTileService の onClick を、OS に結び付けずに呼んで例外を起こす。テストの側に keep の規則は要らない（名前で引くだけ）
     */
    @Test
    fun appFramesKeepLineNumbersAndSourceFile() {
        val apk =
            shell("pm path $APP")
                .lines()
                .first { it.endsWith("base.apk") }
                .removePrefix("package:")
                .trim()
        val loader = dalvik.system.PathClassLoader(apk, null)
        val tile = loader.loadClass("$APP.SearchTileService")
        val failure =
            try {
                tile.getMethod("onClick").invoke(tile.getDeclaredConstructor().newInstance())
                null
            } catch (e: java.lang.reflect.InvocationTargetException) {
                e.targetException
            }
        val frames = checkNotNull(failure) { "OS に結び付けずに呼んだので、Context がなく例外になるはず" }.stackTrace
        // このテストのフレーム（jp.piu.search.releasetest）は R8 にかからないので除く。Class.getPackageName は API 29 からなので使わない
        val own = frames.filter { it.className.startsWith("$APP.") && !it.className.startsWith("$APP.releasetest.") }
        assertTrue("アプリのフレーム: ${frames.joinToString()}", own.any { it.className == "$APP.SearchTileService" && it.methodName == "onClick" })
        for (frame in own) {
            // R8 は元のファイル名を残さない。API 28 の ART はファイル名を null（Unknown Source）で見せ、新しい版は SourceFile と見せる。
            // retrace に要るのは行の番号と mapping.txt なので、元の名前が出ないことだけを確かめる
            assertTrue("元のファイル名は出ない: $frame", frame.fileName == null || frame.fileName == "SourceFile")
            assertTrue("行の番号が残る: $frame", frame.lineNumber > 0)
        }
    }

    // 探す項目は画面の下にある。小さい画面では、縦に送る部品 [container] を送ってから探す
    private fun scrolledTo(
        target: String,
        container: String,
    ): Boolean {
        if (device.findObject(By.res(target)) != null) return true
        device.findObject(By.res(container))?.scroll(Direction.DOWN, 1f)
        return device.findObject(By.res(target)) != null
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

        // SettingsScreen.kt・IndexStatusScreen.kt の testTag
        const val SETTINGS = "settings"
        const val SETTINGS_INDEX_STATUS = "settings_index_status"
        const val INDEX_STATUS = "index_status"
        const val INDEX_STATUS_REPORT = "index_status_report"
        const val INDEX_REPORT_TEXT = "index_report_text"
        const val JA_ONLY = "jp.piu.search.testapp.labeljaonly"
        const val SYNC_TAG = "PiuIndexSync"
        const val FAILED = "failed=true"
        const val SAVED = "索引を保存した"

        // 索引の同期の記録の、キーを作った入口の数が 1 以上
        val BUILT = Regex(" built=[1-9]")

        // 最初の同期は全件の作り直し（辞書の読み込みを含む）で、遅いエミュレータでは数秒かかる
        const val TIMEOUT_MILLIS = 60_000L
        const val TILE_RETRY_MILLIS = 3_000L
        const val POLL_MILLIS = 200L
    }
}
