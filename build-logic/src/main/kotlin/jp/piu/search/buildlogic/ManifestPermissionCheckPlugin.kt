// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Android アプリのすべての variant（debug・release）に、マニフェストの権限の検査を足し、`check` に含める。
 *
 * `com.android.application` を掛けたモジュールで使う。
 */
class ManifestPermissionCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                val name = "check${variant.name.replaceFirstChar(Char::uppercaseChar)}ManifestPermissions"
                val task =
                    project.tasks.register(name, ManifestPermissionCheckTask::class.java) {
                        group = "verification"
                        description = "${variant.name} のマニフェストに持たせない権限がないことを確かめる"
                        mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                        forbiddenPermissions.set(ManifestPermissions.FORBIDDEN)
                        report.set(project.layout.buildDirectory.file("reports/manifest-permissions/${variant.name}.txt"))
                    }
                project.tasks.named("check") { dependsOn(task) }
            }
        }
    }
}
