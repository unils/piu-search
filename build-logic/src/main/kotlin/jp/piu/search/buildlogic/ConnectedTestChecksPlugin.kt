// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property

/**
 * 計器テスト（`connected<Variant>AndroidTest`）の前後に検査を掛ける。
 *
 * - 前: [CheckNoAnrDialogTask]（端末に応答なしの窓がないこと）
 * - 後: [VerifyConnectedTestCountTask]（端末ごとに、ソースの `@Test` の数だけ回ったこと）
 *
 * Gradle のタスクに掛けるので、手元でも CI（android-emulator-runner から `./gradlew` を呼ぶ）でも同じに効く。
 * `com.android.application`・`com.android.library`・`com.android.test` のモジュールで使う。
 * `com.android.test` ではテストのソースが `src/main` にある。
 * variant ごとに回すテストを変えるモジュール（`:benchmark`）は、[ConnectedTestChecksExtension.countTests] で件数の検査を外す。
 */
class ConnectedTestChecksPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("connectedTestChecks", ConnectedTestChecksExtension::class.java)
        extension.countTests.convention(true)
        project.plugins.withId("com.android.application") { configure(project, extension, testSources = "src/androidTest") }
        project.plugins.withId("com.android.library") { configure(project, extension, testSources = "src/androidTest") }
        project.plugins.withId("com.android.test") { configure(project, extension, testSources = "src/main") }
    }

    private fun configure(
        project: Project,
        extension: ConnectedTestChecksExtension,
        testSources: String,
    ) {
        @Suppress("UNCHECKED_CAST")
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java) as AndroidComponentsExtension<*, *, Variant>
        val adb = components.sdkComponents.adb
        val anrCheck =
            project.tasks.register("checkNoAnrDialog", CheckNoAnrDialogTask::class.java) {
                group = "verification"
                description = "計器テストの前に、端末に応答なしの窓が出ていないことを確かめる"
                this.adb.set(adb)
            }
        val filtered =
            project.providers
                .gradlePropertiesPrefixedBy(RUNNER_ARGUMENTS)
                .map { arguments -> arguments.keys.any { it.removePrefix(RUNNER_ARGUMENTS) in FILTER_ARGUMENTS } }
                .orElse(false)
        components.onVariants(components.selector().all()) { variant ->
            val name = variant.name.replaceFirstChar(Char::uppercaseChar)
            val verify =
                project.tasks.register("verify${name}ConnectedTestCount", VerifyConnectedTestCountTask::class.java) {
                    group = "verification"
                    description = "${variant.name} の計器テストが、端末ごとにソースの @Test の数だけ回ったことを確かめる"
                    this.adb.set(adb)
                    resultsDir.set(project.layout.buildDirectory.dir("outputs/androidTest-results/connected/${variant.name}"))
                    sources.from(project.fileTree(testSources) { include("**/*.kt", "**/*.java") })
                    this.filtered.set(filtered)
                    moduleName.set(project.path)
                }
            val countTests = extension.countTests
            project.tasks.named { it == "connected${name}AndroidTest" }.configureEach {
                dependsOn(anrCheck)
                if (countTests.get()) finalizedBy(verify)
            }
        }
    }

    private companion object {
        const val RUNNER_ARGUMENTS = "android.testInstrumentationRunnerArguments."

        /** AndroidJUnitRunner の、回すテストを絞る引数。 */
        val FILTER_ARGUMENTS =
            setOf("class", "notClass", "package", "notPackage", "annotation", "notAnnotation", "size", "tests_regex", "filter")
    }
}

/** [ConnectedTestChecksPlugin] の設定。 */
abstract class ConnectedTestChecksExtension {
    /** 計器テストの後に件数を確かめるか。既定は確かめる。 */
    abstract val countTests: Property<Boolean>
}
