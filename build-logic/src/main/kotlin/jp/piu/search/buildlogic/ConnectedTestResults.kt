// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 計器テストの前後の検査の中身（Gradle に依存しない部分）。
 *
 * - 前: 端末に応答なしの窓（ANR）が出ていないか。SystemUI の ANR の窓が残ると、HOME キーなどが失われ、
 *   60 秒待った末に「画面が前に出ない」と落ちて原因がわかりにくい（M5-02）
 * - 後: 端末ごとに、想定した数のテストが回ったか。本体の APK が入らないと、テストが 0 件のまま
 *   `connectedDebugAndroidTest` が成功する（API 26 の INSTALL_FAILED_ALREADY_EXISTS。2026-11-02）
 */
object ConnectedTestResults {
    /** 応答なしの窓の題。AOSP の `AppNotRespondingDialog` が言語によらずこの英語で付ける。 */
    const val ANR_WINDOW_TITLE = "Application Not Responding"

    /**
     * `dumpsys window windows` の出力から、応答なしの窓を出したプロセスの名前を抜き出す。
     *
     * 同じ窓が一覧・入力の焦点などの複数の行に出るので、名前でまとめる。
     */
    fun anrProcesses(dumpsys: String): List<String> =
        ANR_WINDOW
            .findAll(dumpsys)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /**
     * ソースの中のテストの数。`@Test` の注釈の行を数える。
     *
     * 実行の結果と比べる想定の数で、引数を変えて同じテストを回す形（parameterized）は使っていない前提。
     */
    fun declaredTestCount(sources: Sequence<String>): Int = sources.sumOf { text -> TEST_ANNOTATION.findAll(text).count() }

    /**
     * AGP の結果の XML（`TEST-<端末>-<モジュール>.xml`）から、端末のシリアルごとの testcase の数を数える。
     *
     * 端末は各 testsuite の `device` の property で見分ける（ファイル名は AVD の名前で、シリアルではない）。
     */
    fun countByDevice(xmls: List<InputStream>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val factory = DocumentBuilderFactory.newInstance()
        for (xml in xmls) {
            val document = xml.use { factory.newDocumentBuilder().parse(it) }
            val suites = document.getElementsByTagName("testsuite")
            for (i in 0 until suites.length) {
                val suite = suites.item(i) as Element
                val device = deviceOf(suite) ?: UNKNOWN_DEVICE
                val cases = suite.getElementsByTagName("testcase").length
                counts[device] = (counts[device] ?: 0) + cases
            }
        }
        return counts
    }

    /** 想定に届かなかった端末。 */
    data class Shortfall(
        /** 端末のシリアル。 */
        val device: String,
        /** 回った数。 */
        val actual: Int,
    )

    /** [devices] のうち、回った数が [expected] に届かなかった端末。結果のない端末は 0 件と数える。 */
    fun shortfalls(
        devices: List<String>,
        counts: Map<String, Int>,
        expected: Int,
    ): List<Shortfall> = devices.map { Shortfall(it, counts[it] ?: 0) }.filter { it.actual < expected }

    /** シリアルのわからない testsuite の端末の名前。 */
    const val UNKNOWN_DEVICE = "(不明)"

    private val ANR_WINDOW = Regex("""$ANR_WINDOW_TITLE: ([\w.:]+)""")

    private val TEST_ANNOTATION = Regex("""(?m)^\s*@(org\.junit\.)?Test\b""")

    private fun deviceOf(suite: Element): String? {
        val properties = suite.getElementsByTagName("property")
        for (i in 0 until properties.length) {
            val property = properties.item(i) as Element
            if (property.getAttribute("name") == "device") return property.getAttribute("value")
        }
        return null
    }
}
