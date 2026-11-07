// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 出来上がったマニフェストに、持たせない権限がないことを確かめるタスク。
 *
 * 依存のライブラリが足した権限も、マージの後のマニフェストに現れるので、ここで捕まえられる。
 */
@CacheableTask
abstract class ManifestPermissionCheckTask : DefaultTask() {
    /** マージの後のマニフェスト（`SingleArtifact.MERGED_MANIFEST`）。 */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** 持たせない権限の名前。 */
    @get:Input
    abstract val forbiddenPermissions: SetProperty<String>

    /** 検査した権限の一覧。成功したときだけ書き、up-to-date の判定に使う。 */
    @get:OutputFile
    abstract val report: RegularFileProperty

    /** 検査する。持たせない権限があれば、どこで確かめるかを添えて失敗する。 */
    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile
        val permissions = manifest.inputStream().use(ManifestPermissions::read)
        val found = ManifestPermissions.findForbidden(permissions, forbiddenPermissions.get())
        if (found.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("持たせない権限がマニフェストにある: ${found.joinToString()}")
                    appendLine("マニフェスト: $manifest")
                    append(
                        "どこから来たかは build/outputs/logs/manifest-merger-*-report.txt で確かめる。" +
                            "ライブラリが足したなら、app のマニフェストで tools:node=\"remove\" を付けて外す",
                    )
                },
            )
        }
        report.get().asFile.writeText(permissions.joinToString(separator = "\n", postfix = "\n"))
    }
}
