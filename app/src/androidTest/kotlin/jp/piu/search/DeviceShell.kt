// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.res.Resources
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.io.FileInputStream

/** 計器テストで、端末の shell のコマンドを使い、状態を待つ道具。 */
class DeviceShell {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** shell の権限で [command] を実行し、標準出力を返す。 */
    fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }

    /** 前面の Activity を示す行（API で書式が違う: mResumedActivity・ResumedActivity・topResumedActivity）。 */
    fun resumedActivityLines(): List<String> = shell("dumpsys activity activities").lines().filter { "ResumedActivity" in it }

    /** [condition] が成り立つまで待つ。[TIMEOUT_MILLIS] を過ぎたら失敗にする。 */
    fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            assertTrue("時間内に「$what」にならなかった", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    /** 定数。 */
    companion object {
        /** テスト用の乗換のアプリ。 */
        const val TRANSIT = "jp.piu.search.testapp.transit"

        /** テスト用の将棋のアプリ。 */
        const val GAME = "jp.piu.search.testapp.game"

        /** 最初の同期は全件の作り直し（辞書の読み込みを含む）で、遅いエミュレータでは数秒かかる。 */
        const val TIMEOUT_MILLIS = 60_000L

        private const val POLL_MILLIS = 200L

        /** 端末の言語が日本語か。テスト用のアプリは日本語と英語の名前を持つ。 */
        val isJapanese: Boolean get() =
            Resources
                .getSystem()
                .configuration.locales[0]
                .language == "ja"

        /** 端末の言語での表示名。 */
        fun label(
            japanese: String,
            english: String,
        ): String = if (isJapanese) japanese else english
    }
}
