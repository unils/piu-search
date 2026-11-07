// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * `core:*`（Android に依存しない JVM のモジュール）に共通の検査を足し、`check` に含める。
 *
 * - 依存に Android のライブラリがないこと（[CoreDependencyCheckTask]）
 * - 行の網羅率が [MIN_LINE_COVERAGE]% 以上であること（Kover）
 *
 * `org.jetbrains.kotlin.jvm` を掛けたモジュールで使う。
 */
class CoreChecksPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlinx.kover")
        project.extensions.configure(KoverProjectExtension::class.java) {
            reports.verify.rule {
                // 既定の単位は行、集計は網羅した割合
                minBound(MIN_LINE_COVERAGE)
            }
        }

        val task =
            project.tasks.register("checkCoreDependencies", CoreDependencyCheckTask::class.java) {
                group = "verification"
                description = "依存に Android のライブラリがないことを確かめる"
                CONFIGURATIONS.forEach { name ->
                    components.put(
                        name,
                        project.configurations
                            .named(name)
                            .flatMap { it.incoming.resolutionResult.rootComponent }
                            .map(::collect),
                    )
                }
                report.set(project.layout.buildDirectory.file("reports/core-dependencies.txt"))
            }
        project.tasks.named("check") { dependsOn(task) }
    }

    private companion object {
        /** 網羅率の下限（%）。testing.md の「静的な検査」。 */
        const val MIN_LINE_COVERAGE = 80

        /** 検査する構成。テストの依存は配布物に入らないので見ない。 */
        val CONFIGURATIONS = listOf("compileClasspath", "runtimeClasspath")

        /** [root] から辿れる依存を、[root] 自身を除いて重複なく集める。 */
        fun collect(root: ResolvedComponentResult): List<CoreDependencies.Component> {
            val seen = LinkedHashSet<ResolvedComponentResult>()
            val stack = ArrayDeque(listOf(root))
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                current.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .map { it.selected }
                    .filter { seen.add(it) }
                    .forEach(stack::addLast)
            }
            return seen.filter { it != root }.map { component ->
                when (val id = component.id) {
                    is ModuleComponentIdentifier -> CoreDependencies.Component(id.group, id.module, id.version)
                    is ProjectComponentIdentifier -> CoreDependencies.Component(name = id.projectName, projectPath = id.projectPath)
                    else -> CoreDependencies.Component(name = id.displayName)
                }
            }
        }
    }
}
