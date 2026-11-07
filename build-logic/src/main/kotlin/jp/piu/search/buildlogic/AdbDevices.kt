// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream

/**
 * 計器テストの相手の端末を、`installDebug` や `connectedDebugAndroidTest` と同じ決まりで選ぶ。
 *
 * 環境変数 `ANDROID_SERIAL` があればその端末だけ、なければ `adb devices` で使えるすべての端末。
 */
object AdbDevices {
    /** 相手の端末のシリアル。 */
    fun serials(
        exec: ExecOperations,
        adb: String,
    ): List<String> = System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }?.let(::listOf) ?: parse(run(exec, adb, "devices"))

    /** `adb devices` の出力から、使える端末（「シリアル<TAB>device」の行）を抜き出す。1 行目は見出し。 */
    fun parse(output: String): List<String> =
        output
            .lines()
            .drop(1)
            .map { it.split('\t') }
            .filter { it.size == 2 && it[1].trim() == "device" }
            .map { it[0] }

    /** `adb` を [args] で実行し、標準出力を返す。 */
    fun run(
        exec: ExecOperations,
        adb: String,
        vararg args: String,
    ): String {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine(adb, *args)
            standardOutput = out
        }
        return out.toString(Charsets.UTF_8)
    }
}
