// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 起動の速さの計測（Macrobenchmark）と Baseline Profile の生成。手で回す（check に入れない）。
// どちらもエミュレータの値に頼り、1 回に数分かかるため。手順は CONTRIBUTING.md の「起動の速さ」
//
//   計測: ./gradlew :benchmark:connectedMinifiedAndroidTest        （app の minified。R8 を通した版）
//   生成: ./gradlew :benchmark:connectedNonMinifiedAndroidTest :benchmark:copyBaselineProfile
plugins {
    alias(libs.plugins.android.test)
    id("piu.connected-test-checks")
}

android {
    namespace = "jp.piu.search.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Macrobenchmark は、エミュレータの上では値が実機を表さないとして止まる。エミュレータで測ると決めた（M5-03）ので止めない。
        // 値は前後の比較にだけ使い、実機の値は M6 で見る
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    buildTypes {
        // app の同じ名前の build type を相手にする。テストの APK は R8 にかけない。
        // app を通して見える data などのライブラリは debug と release しか持たない
        create("minified") {
            isDebuggable = true
            // debug 以外の build type には既定の署名がない。署名がないとテストの APK が入らない
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("nonMinified") {
            isDebuggable = true
            // debug 以外の build type には既定の署名がない。署名がないとテストの APK が入らない
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // テストを自分のプロセスで動かす。計測のたびにアプリのプロセスを止めて起動し直すため
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
    beforeVariants { it.enable = it.buildType == "minified" || it.buildType == "nonMinified" }
    // 計測は R8 を通した版で、生成は R8 を通さない版で回す（生成した規則を元のコードの名前で書くため）
    onVariants(selector().withBuildType("minified")) {
        it.instrumentationRunnerArguments.put("class", "jp.piu.search.benchmark.StartupBenchmark")
    }
    onVariants(selector().withBuildType("nonMinified")) {
        it.instrumentationRunnerArguments.put("class", "jp.piu.search.benchmark.BaselineProfileGenerator")
    }
}

// 本体（jp.piu.search）を入れて消すほかの計器テストと、同時に回さない
tasks.named { it.startsWith("connected") && it.endsWith("AndroidTest") }.configureEach {
    mustRunAfter(":app:connectedDebugAndroidTest", ":release-test:connectedMinifiedAndroidTest")
}

// variant ごとに回すテストを絞るので、ソースの @Test の数とは比べない。応答なしの窓の検査は掛ける
connectedTestChecks {
    countTests.set(false)
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
    implementation(libs.androidx.benchmark.macro.junit4)
}

// 生成した Baseline Profile を app に写す。リポジトリに入れ、ビルドのたびには生成しない
// （生成はエミュレータに頼り、F-Droid の再現可能なビルドで同じ結果にならないため）
tasks.register<Copy>("copyBaselineProfile") {
    group = "benchmark"
    description = "connectedNonMinifiedAndroidTest が集めた Baseline Profile を app/src/main/baselineProfiles/ に写す"
    from(layout.buildDirectory.dir("outputs/connected_android_test_additional_output/nonMinified/connected")) {
        include("**/BaselineProfileGenerator_generate-baseline-prof.txt")
        eachFile { path = "baseline-prof.txt" }
        includeEmptyDirs = false
    }
    into(rootProject.layout.projectDirectory.dir("app/src/main/baselineProfiles"))
    // 写す先は app のソースで、app の組み立てが読む。同じ実行で続けて回すとき、組み立ての後に写す
    mustRunAfter("connectedNonMinifiedAndroidTest")
}
