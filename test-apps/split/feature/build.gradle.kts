// SPDX-License-Identifier: Apache-2.0

plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "jp.piu.search.testapp.split.feature"
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

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get().toInt()))
}

androidComponents {
    // base（piu.test-app）と同じく debug だけ
    beforeVariants { variant ->
        variant.enable = variant.buildType == "debug"
        variant.deviceTests[com.android.build.api.variant.DeviceTestBuilder.ANDROID_TEST_TYPE]?.enable = false
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

dependencies {
    implementation(project(":test-apps:split"))
}
