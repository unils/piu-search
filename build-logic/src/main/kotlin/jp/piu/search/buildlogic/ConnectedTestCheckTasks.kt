// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * 計器テストの前に、端末に応答なしの窓（ANR）が出ていないことを確かめる。出ていれば、そう分かる文で落とす。
 *
 * 端末の状態を見るので、毎回動かす（up-to-date にしない）。
 */
abstract class CheckNoAnrDialogTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** `adb` の実行ファイル。 */
        @get:Internal
        abstract val adb: RegularFileProperty

        init {
            outputs.upToDateWhen { false }
        }

        /** 確かめる。端末がなければ何もしない（計器テストのタスクの側が「端末がない」と落とす）。 */
        @TaskAction
        fun check() {
            val adbPath = adb.get().asFile.absolutePath
            val found =
                AdbDevices
                    .serials(exec, adbPath)
                    .associateWith { serial ->
                        val windows = AdbDevices.run(exec, adbPath, "-s", serial, "shell", "dumpsys", "window", "windows")
                        ConnectedTestResults.anrProcesses(windows)
                    }.filterValues { it.isNotEmpty() }
            if (found.isEmpty()) return
            throw GradleException(
                buildString {
                    appendLine("端末に応答なし（ANR）の窓が出ているので、計器テストを始めない:")
                    found.forEach { (serial, processes) -> appendLine("  $serial: ${processes.joinToString()}") }
                    appendLine("窓が残ると、テストの HOME キーなどが失われ、画面を待つテストが時間切れで落ちる。")
                    append(
                        "エミュレータの起動中に重いビルドを回すと SystemUI が出しやすい。" +
                            "端末を起動し直すか、窓の「待つ」を押して閉じ、回し直す（確かめるには " +
                            "adb shell dumpsys window windows | grep '${ConnectedTestResults.ANR_WINDOW_TITLE}'）",
                    )
                },
            )
        }
    }

/**
 * 計器テストの後に、端末ごとに想定した数のテストが回ったことを、結果の XML で確かめる。
 *
 * 想定の数はソースの `@Test` の数。テストを絞って回した（`-Pandroid.testInstrumentationRunnerArguments.class=…` など）ときは、
 * 1 件以上回ったことだけを確かめる。
 */
abstract class VerifyConnectedTestCountTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** `adb` の実行ファイル。 */
        @get:Internal
        abstract val adb: RegularFileProperty

        /** 結果の XML を置くディレクトリ（`build/outputs/androidTest-results/connected/<variant>`）。 */
        @get:Internal
        abstract val resultsDir: DirectoryProperty

        /** 計器テストのソース。 */
        @get:Internal
        abstract val sources: ConfigurableFileCollection

        /** テストを絞って回したか。 */
        @get:Internal
        abstract val filtered: Property<Boolean>

        /** 報告に出すモジュールの名前。 */
        @get:Internal
        abstract val moduleName: Property<String>

        init {
            outputs.upToDateWhen { false }
        }

        /** 確かめる。 */
        @TaskAction
        fun verify() {
            val declared =
                ConnectedTestResults.declaredTestCount(
                    sources.asFileTree.files
                        .asSequence()
                        .map { it.readText() },
                )
            val expected = if (filtered.get()) 1 else declared
            val xmls =
                resultsDir
                    .get()
                    .asFile
                    .listFiles { file -> file.isFile && file.name.endsWith(".xml") }
                    .orEmpty()
                    .sortedBy { it.name }
            val counts = ConnectedTestResults.countByDevice(xmls.map { it.inputStream() })
            val devices = AdbDevices.serials(exec, adb.get().asFile.absolutePath).ifEmpty { counts.keys.toList() }
            val shortfalls = ConnectedTestResults.shortfalls(devices, counts, expected)
            logger.lifecycle("${moduleName.get()} の計器テストの件数（想定 $expected 件）: " + devices.joinToString { "$it ${counts[it] ?: 0} 件" })
            if (shortfalls.isEmpty()) return
            throw GradleException(
                buildString {
                    append("${moduleName.get()} の計器テストが想定より少ない（想定 $expected 件")
                    if (filtered.get()) append("。絞って回したので 1 件以上") else append("。ソースの @Test の数")
                    appendLine("）:")
                    shortfalls.forEach { appendLine("  ${it.device}: ${it.actual} 件") }
                    appendLine("結果: ${resultsDir.get().asFile}")
                    append(
                        "APK が入らなかった見込み。ログの「AndroidTestRunner failed」や INSTALL_FAILED_* を見る。" +
                            "手で入れた本体が残っているなら（API 26 で INSTALL_FAILED_ALREADY_EXISTS）、adb uninstall jp.piu.search で消して回し直す",
                    )
                },
            )
        }
    }
