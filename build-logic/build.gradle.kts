// SPDX-License-Identifier: Apache-2.0

plugins {
    `kotlin-dsl`
    alias(libs.plugins.detekt)
}

kotlin {
    // 本体のモジュールと同じ JDK でコンパイルする。PC の Java が JRE だけでも動くように
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

dependencies {
    // 実行時は app に掛かった AGP そのものを使うので、コンパイルにだけ API を置く
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kover.gradle.plugin)
    compileOnly(libs.detekt.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// 本体のモジュールは piu.kdoc-check で設定する。build-logic は自分のプラグインを掛けられないので、同じ設定をここに書く
detekt {
    buildUponDefaultConfig = false
    config.setFrom(layout.projectDirectory.file("../config/detekt/detekt.yml"))
    source.setFrom(layout.projectDirectory.dir("src/main/kotlin"))
}

gradlePlugin {
    plugins {
        register("manifestPermissionCheck") {
            id = "piu.manifest-permission-check"
            implementationClass = "jp.piu.search.buildlogic.ManifestPermissionCheckPlugin"
        }
        register("kdocCheck") {
            id = "piu.kdoc-check"
            implementationClass = "jp.piu.search.buildlogic.KdocCheckPlugin"
        }
        register("coreChecks") {
            id = "piu.core-checks"
            implementationClass = "jp.piu.search.buildlogic.CoreChecksPlugin"
        }
        register("connectedTestChecks") {
            id = "piu.connected-test-checks"
            implementationClass = "jp.piu.search.buildlogic.ConnectedTestChecksPlugin"
        }
        register("testApp") {
            id = "piu.test-app"
            implementationClass = "jp.piu.search.buildlogic.TestAppPlugin"
        }
    }
}
