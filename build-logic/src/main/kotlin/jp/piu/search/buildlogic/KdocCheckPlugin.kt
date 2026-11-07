// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 公開 API に KDoc がないことを detekt で検出し、`check` に含める。
 *
 * 規則は根の `config/detekt/detekt.yml` に置き、モジュールごとに設定を書かずに済ませる。
 * テストのコードは公開 API ではないので、`src/main/kotlin` だけを見る。
 */
class KdocCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("dev.detekt")
        project.extensions.configure(DetektExtension::class.java) {
            buildUponDefaultConfig.set(false)
            config.setFrom(
                project.isolated.rootProject.projectDirectory
                    .file(CONFIG_PATH),
            )
            source.setFrom(project.layout.projectDirectory.dir("src/main/kotlin"))
        }
    }

    private companion object {
        /** 根のプロジェクトからの、detekt の設定の道。 */
        const val CONFIG_PATH = "config/detekt/detekt.yml"
    }
}
