// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import jp.piu.search.buildlogic.ConnectedTestResults.Shortfall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectedTestResultsTest {
    @Test
    fun `応答なしの窓をプロセスごとに 1 つ拾う`() {
        // API 37 のエミュレータで SystemUI が出したときの dumpsys window windows（抜粋）
        val dumpsys =
            """
            WINDOW MANAGER WINDOWS (dumpsys window windows)
              Window #0 Window{1a2b3c u0 Application Not Responding: com.android.systemui}:
                mDisplayId=0 rootTaskId=1 mSession=Session{9f 1234:1000} mClient=android.os.BinderProxy@5
              Window #1 Window{4d5e6f u0 NotificationShade}:
              mCurrentFocus=Window{1a2b3c u0 Application Not Responding: com.android.systemui}
            """.trimIndent()
        assertEquals(listOf("com.android.systemui"), ConnectedTestResults.anrProcesses(dumpsys))
    }

    @Test
    fun `応答なしの窓がなければ空`() {
        val dumpsys =
            """
            Window #0 Window{4d5e6f u0 NotificationShade}:
            mCurrentFocus=Window{7 u0 jp.piu.search/jp.piu.search.SearchActivity}
            """.trimIndent()
        assertEquals(emptyList<String>(), ConnectedTestResults.anrProcesses(dumpsys))
    }

    @Test
    fun `ソースの Test の注釈を数える`() {
        val source =
            """
            class A {
                @Test
                fun a() {}

                @org.junit.Test fun b() {}

                // @Test は注釈ではない
                @TestOnly
                fun c() {}
            }
            """.trimIndent()
        assertEquals(2, ConnectedTestResults.declaredTestCount(sequenceOf(source)))
        assertEquals(4, ConnectedTestResults.declaredTestCount(sequenceOf(source, source)))
    }

    @Test
    fun `結果の XML を端末ごとに数える`() {
        val api37 = xml(suite("emulator-5554", 2), suite("emulator-5554", 3))
        val api26 = xml(suite("emulator-5556", 1))
        assertEquals(
            mapOf("emulator-5554" to 5, "emulator-5556" to 1),
            ConnectedTestResults.countByDevice(listOf(api37.byteInputStream(), api26.byteInputStream())),
        )
    }

    @Test
    fun `テストが 0 件の XML は数に入らない`() {
        assertEquals(emptyMap<String, Int>(), ConnectedTestResults.countByDevice(listOf(xml().byteInputStream())))
    }

    @Test
    fun `結果のない端末と少ない端末を挙げる`() {
        val counts = mapOf("a" to 18, "b" to 17)
        assertEquals(
            listOf(Shortfall("b", 17), Shortfall("c", 0)),
            ConnectedTestResults.shortfalls(listOf("a", "b", "c"), counts, expected = 18),
        )
        assertEquals(emptyList<Shortfall>(), ConnectedTestResults.shortfalls(listOf("a"), counts, expected = 18))
    }

    private fun suite(
        device: String,
        cases: Int,
    ) = buildString {
        append("<testsuite name=\"S\" tests=\"$cases\"><properties><property name=\"device\" value=\"$device\" /></properties>")
        repeat(cases) { append("<testcase name=\"t$it\" classname=\"S\" />") }
        append("</testsuite>")
    }

    private fun xml(vararg suites: String) =
        "<?xml version='1.0' encoding='UTF-8' ?><testsuites tests=\"0\">${suites.joinToString("")}</testsuites>"
}
