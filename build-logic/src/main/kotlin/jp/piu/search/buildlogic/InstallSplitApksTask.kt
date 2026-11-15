// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * base と split の APK を、つないだすべての端末に 1 つのアプリとして入れる（`adb install-multiple -r`）。
 *
 * 動的な機能のモジュールを持つアプリの `installDebug` は、base の APK しか入れない（AGP 9.5.1 で、App Bundle から
 * 端末向けに取り出した `base-master` だけを入れた。2026-11-16 確認）。split のあるアプリを試すため、自分で組にして入れる。
 * 端末の選び方は [AdbDevices]。
 */
abstract class InstallSplitApksTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** base と split の APK。 */
        @get:InputFiles
        abstract val apks: ConfigurableFileCollection

        /** `adb` の実行ファイル。 */
        @get:InputFile
        abstract val adb: RegularFileProperty

        /** 入れる。端末がなければ失敗する。 */
        @TaskAction
        fun install() {
            val files = apks.files.filter { it.name.endsWith(".apk") }.sorted()
            if (files.size < 2) throw GradleException("base と split の APK がそろっていない: $files")
            val adbPath = adb.get().asFile.absolutePath
            val serials = AdbDevices.serials(exec, adbPath)
            if (serials.isEmpty()) throw GradleException("端末がつながっていない")
            for (serial in serials) {
                exec.exec { commandLine(listOf(adbPath, "-s", serial, "install-multiple", "-r") + files.map { it.absolutePath }) }
            }
        }
    }
