// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// テスト用のアプリが共有する Activity とアイコン。テスト用のアプリだけが依存する
plugins {
    alias(libs.plugins.android.library)
    id("piu.kdoc-check")
}

android {
    namespace = "jp.piu.search.testapp.common"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        val target = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = target
        targetCompatibility = target
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        lintConfig = rootProject.file("lint.xml")
    }
}

androidComponents {
    beforeVariants { variant ->
        variant.enable = variant.buildType == "debug"
        variant.deviceTests[com.android.build.api.variant.DeviceTestBuilder.ANDROID_TEST_TYPE]?.enable = false
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        allWarningsAsErrors.set(true)
    }
}
