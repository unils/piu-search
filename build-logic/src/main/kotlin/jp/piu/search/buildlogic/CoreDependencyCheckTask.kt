// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * `core:*` の依存（`compileClasspath`・`runtimeClasspath`）に Android のライブラリがないことを確かめるタスク。
 */
@CacheableTask
abstract class CoreDependencyCheckTask : DefaultTask() {
    /** 構成の名前ごとの、解決した依存の一覧（推移的な依存を含む）。 */
    @get:Input
    abstract val components: MapProperty<String, List<CoreDependencies.Component>>

    /** 検査した依存の一覧。成功したときだけ書き、up-to-date の判定に使う。 */
    @get:OutputFile
    abstract val report: RegularFileProperty

    /** 検査する。持たせない依存があれば、構成ごとに並べて失敗する。 */
    @TaskAction
    fun check() {
        val byConfiguration = components.get()
        val found =
            byConfiguration.mapValues { (_, list) -> CoreDependencies.findForbidden(list) }.filterValues { it.isNotEmpty() }
        if (found.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("core:* の依存に Android のライブラリ（または core:* の外のプロジェクト）がある:")
                    found.forEach { (configuration, list) -> list.forEach { appendLine("  $configuration: $it") } }
                    append("どこから来たかは ./gradlew ${projectPathForHint()}:dependencies --configuration <構成> で確かめる")
                },
            )
        }
        report.get().asFile.writeText(
            byConfiguration.entries.joinToString(separator = "") { (configuration, list) ->
                list.joinToString(separator = "") { "$configuration\t$it\n" }
            },
        )
    }

    // 実行時に Project を触らないよう、タスクの道から組み立てる
    private fun projectPathForHint(): String = path.substringBeforeLast(':')
}
