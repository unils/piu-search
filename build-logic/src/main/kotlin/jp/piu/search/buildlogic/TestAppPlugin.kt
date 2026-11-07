// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * テスト用のアプリ（`testapps/` の下のモジュール）の共通の設定。
 *
 * パッケージ名は [PACKAGE_PREFIX] にモジュール名を足したもの。中身はマニフェストと資源だけで、
 * Activity のクラスとアイコンは `:testapps:common` から取る。
 * 計器テストの前に端末へ入れる相手で、配布物には入らない。
 *
 * `src/update` を持つモジュールには、更新の試験用の版（build type の [UPDATE_BUILD_TYPE]）も作る。
 * debug と同じ鍵で署名し、`versionCode` を [UPDATE_VERSION_CODE] に上げ、`src/update` の資源（名前を変えるなど）で上書きした版。
 * `pushUpdateApk` が端末の [UPDATE_APK_DIR] に置き、計器テストが `pm install -r` で入れて更新を起こす。
 */
class TestAppPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.application")
        // 他所のアプリの代わりなので要らないが、依存の追加で権限が紛れ込んでも気づけるよう、本体と同じ検査を掛ける
        project.pluginManager.apply("piu.manifest-permission-check")

        val hasUpdate = project.file("src/$UPDATE_BUILD_TYPE").isDirectory
        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

        fun version(name: String): Int =
            libs
                .findVersion(name)
                .get()
                .requiredVersion
                .toInt()

        project.extensions.configure(ApplicationExtension::class.java) {
            namespace = PACKAGE_PREFIX + project.name
            compileSdk = version("compileSdk")
            defaultConfig {
                applicationId = PACKAGE_PREFIX + project.name
                minSdk = version("minSdk")
                targetSdk = version("targetSdk")
                versionCode = 1
                versionName = "1"
            }
            if (hasUpdate) {
                buildTypes.create(UPDATE_BUILD_TYPE) {
                    // debug の鍵で署名し、入っている debug の版を上書きできるようにする
                    initWith(buildTypes.getByName("debug"))
                    // :testapps:common（ライブラリ）は debug と release しか持たない
                    matchingFallbacks += "debug"
                }
            }
            // ソースがなくても、AGP は Java のコンパイルのタスクに JDK を求める。本体と同じ JDK と出力の版にする
            compileOptions {
                val target = JavaVersion.toVersion(version("jvmTarget"))
                sourceCompatibility = target
                targetCompatibility = target
            }
            lint {
                warningsAsErrors = true
                abortOnError = true
                lintConfig =
                    project.isolated.rootProject.projectDirectory
                        .file("lint.xml")
                        .asFile
            }
        }

        project.extensions.configure(JavaPluginExtension::class.java) {
            toolchain.languageVersion.set(JavaLanguageVersion.of(version("jdkToolchain")))
        }

        project.extensions.configure(ApplicationAndroidComponentsExtension::class.java) {
            val components = this
            beforeVariants { variant ->
                // 端末に入れるのは debug（と更新の試験用の版）だけ。release とテストの variant は作らず、check を軽くする
                variant.enable = variant.buildType == "debug" || variant.buildType == UPDATE_BUILD_TYPE
                variant.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]?.enable = false
                variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
            }
            onVariants(selector().withBuildType(UPDATE_BUILD_TYPE)) { variant ->
                variant.outputs.forEach { it.versionCode.set(UPDATE_VERSION_CODE) }
                project.tasks.register("pushUpdateApk", PushApkTask::class.java) {
                    group = "install"
                    description = "更新の試験用の版を端末の $UPDATE_APK_DIR に置く"
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                    artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
                    adb.set(components.sdkComponents.adb)
                    remotePath.set("$UPDATE_APK_DIR/piu-testapp-${project.name}-update.apk")
                }
            }
        }

        project.dependencies.add("implementation", project.dependencies.project(mapOf("path" to COMMON_PATH)))
    }

    /** テスト用のアプリの名前の決まり。 */
    companion object {
        /** パッケージ名の頭。続けてモジュール名を書く。 */
        const val PACKAGE_PREFIX = "jp.piu.search.testapp."

        /** Activity とアイコンを持つ共通のライブラリ。 */
        const val COMMON_PATH = ":testapps:common"

        /** 更新の試験用の版の build type。 */
        const val UPDATE_BUILD_TYPE = "update"

        /** 更新の試験用の版の `versionCode`。debug の版は 1。 */
        const val UPDATE_VERSION_CODE = 2

        /** 更新の試験用の版を置く、端末の上のディレクトリ（`piu-testapp-<モジュール名>-update.apk`）。シェルのユーザーが書けて、`pm install` が読める場所。 */
        const val UPDATE_APK_DIR = "/data/local/tmp"
    }
}
