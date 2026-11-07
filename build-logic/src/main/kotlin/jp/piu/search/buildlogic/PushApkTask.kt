// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * APK を、つないだすべての端末の [remotePath] に置く（入れはしない）。
 *
 * 計器テストが、テストの中から `pm install` でアプリを更新するため。端末の選び方は [AdbDevices]。
 */
abstract class PushApkTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** APK のあるディレクトリ（`SingleArtifact.APK`）。 */
        @get:InputDirectory
        abstract val apkDirectory: DirectoryProperty

        /** [apkDirectory] の中の APK を探す部品。 */
        @get:Internal
        abstract val artifactsLoader: Property<BuiltArtifactsLoader>

        /** `adb` の実行ファイル。 */
        @get:InputFile
        abstract val adb: RegularFileProperty

        /** 端末の上の置き場所。 */
        @get:Input
        abstract val remotePath: Property<String>

        /** 置く。端末がなければ失敗する。 */
        @TaskAction
        fun push() {
            val artifacts =
                artifactsLoader.get().load(apkDirectory.get())
                    ?: throw GradleException("APK の一覧が読めない: ${apkDirectory.get()}")
            val apk = artifacts.elements.single().outputFile
            val adbPath = adb.get().asFile.absolutePath
            val serials = AdbDevices.serials(exec, adbPath)
            if (serials.isEmpty()) throw GradleException("端末がつながっていない")
            for (serial in serials) {
                exec.exec { commandLine(adbPath, "-s", serial, "push", apk, remotePath.get()) }
            }
        }
    }
