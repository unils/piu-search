// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// R8 を通した版（app の minified）を端末に入れ、アプリの外から試す計器テスト（testing.md の「release の APK の確認」）。
// テストのコードは R8 にかからず、アプリのクラスにも触れない（UiAutomator と shell で操作する）。
// 名前を残すべきクラスを R8 が変えたときに、debug の計器テストでは見えない誤りとして落ちる
plugins {
    alias(libs.plugins.android.test)
    id("piu.connected-test-checks")
}

android {
    namespace = "jp.piu.search.releasetest"
    compileSdk = libs.versions.compileSdk.get().toInt()
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // app の minified を相手にする。テストの APK は R8 にかけない
        create("minified") {
            isDebuggable = true
            // debug 以外の build type には既定の署名がない。署名がないとテストの APK が入らない
            signingConfig = signingConfigs.getByName("debug")
            // app を通して見える data などのライブラリは debug と release しか持たない
            matchingFallbacks += "release"
        }
    }

    // テストを自分のプロセスで動かす。アプリのプロセスに入ると、アプリを止めて開き直す試験でテストごと止まるため
    experimentalProperties["android.experimental.self-instrumenting"] = true

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
    // 相手は minified だけ。debug の版は app の計器テストが試す
    beforeVariants { it.enable = it.buildType == "minified" }
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

// テスト用のアプリ（testapps/）を先に入れる。app の計器テストと同じく、本物の PackageManager を相手に索引を確かめる
tasks.named { it == "connectedMinifiedAndroidTest" }.configureEach {
    dependsOn(":testapps:installTestApps")
    // app の計器テストも本体（jp.piu.search）を入れて消すので、同時に回さない
    mustRunAfter(":app:connectedDebugAndroidTest")
}
