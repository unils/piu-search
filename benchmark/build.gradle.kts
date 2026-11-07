// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 起動の速さの計測（Macrobenchmark）と Baseline Profile の生成。手で回す（check に入れない）。
// どちらもエミュレータの値に頼り、1 回に数分かかるため。手順は CONTRIBUTING.md の「起動の速さ」
//
//   計測: ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest   （app の benchmarkRelease。R8 を通した版）
//   生成: ./gradlew :app:generateBaselineProfile                      （app の nonMinifiedRelease。R8 を通さない版）
//
// build type（benchmarkRelease・nonMinifiedRelease）は Baseline Profile のプラグインが作り、
// 回すテストも variant ごとに絞る（androidx.benchmark.enabledRules。計測は MacrobenchmarkRule、生成は BaselineProfileRule）
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
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

    // テストを自分のプロセスで動かす。計測のたびにアプリのプロセスを止めて起動し直すため（プラグインも同じ値にする）
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

baselineProfile {
    // つないだ端末（エミュレータか実機）で生成する。Gradle Managed Device はシステムイメージの取得とエミュレータの起動を
    // Gradle に任せることになり、メモリとディスクの使い方を手元で抑えにくい
    useConnectedDevices = true
    // プラグインの既定はエミュレータで計測を飛ばす。エミュレータで測ると決めた（M5-03）ので飛ばさない
    skipBenchmarksOnEmulator = false
}

// 生成の流れは結果が出るのを待つので、テスト用のアプリ（test-apps/）を先に入れる
tasks.named { it == "connectedNonMinifiedReleaseAndroidTest" }.configureEach {
    dependsOn(":test-apps:installTestApps")
}

// 本体（jp.piu.search）を入れて消すほかの計器テストと、同時に回さない
tasks.named { it.startsWith("connected") && it.endsWith("AndroidTest") }.configureEach {
    mustRunAfter(":app:connectedDebugAndroidTest", ":release-test:connectedBenchmarkReleaseAndroidTest")
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
