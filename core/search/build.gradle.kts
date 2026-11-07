// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
    id("piu.core-checks")
    id("piu.kdoc-check")
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    explicitApi()
    compilerOptions {
        // Android（data・app）から使うので、Android のモジュールと同じ版のバイトコードにそろえる
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

java {
    val target = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    sourceCompatibility = target
    targetCompatibility = target
}

lint {
    warningsAsErrors = true
    abortOnError = true
    lintConfig = rootProject.file("lint.xml")
}

dependencies {
    // SearchKey が NormalizedText を公開するので api にする
    api(project(":core:text"))
    // SearchKeyGenerator が ReadingAnalyzer を公開の引数に取るので api にする。
    // 照合とキーの生成は interface だけに頼り、Kuromoji の実装を差し込むのは app
    api(project(":core:reading"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 評価セットの報告（EvalRunTest）の置き場所。作業ディレクトリに頼らず、ビルドの出力に置く
    val evalReportDir = layout.buildDirectory.dir("reports/eval")
    outputs.dir(evalReportDir)
    systemProperty("eval.reportDir", evalReportDir.get().asFile.absolutePath)
    // 前回の results.tsv を -PevalBaseline=<パス> で渡すと、報告に差を足す
    providers.gradleProperty("evalBaseline").orNull?.let {
        systemProperty("eval.baseline", file(it).absolutePath)
        inputs.file(it)
    }
}
