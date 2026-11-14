// SPDX-License-Identifier: Apache-2.0

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
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

// 評価セットの報告（EvalRunTest）の置き場所。作業ディレクトリに頼らず、ビルドの出力に置く
val evalReportDir = layout.buildDirectory.dir("reports/eval")

tasks.test {
    useJUnitPlatform()
    // 評価セットが基準より下がったとき、どの検索語が下がったかを失敗の文に書くので、CI の記録でも読めるよう文を出す
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = false
    }
    outputs.dir(evalReportDir)
    systemProperty("eval.reportDir", evalReportDir.get().asFile.absolutePath)
}

// 評価セットの基準（src/test/resources/eval/baseline.tsv）を今の結果で書き直す。照合や評価セットを意図して変えたときに回し、
// 差分をレビューに出す。check には入れない（書き直しは人が決める）
tasks.register<Test>("updateEvalBaseline") {
    description = "評価セットの基準（eval/baseline.tsv）を今の結果で書き直す"
    group = "verification"
    val test = sourceSets.test.get()
    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("jp.piu.search.core.search.EvalRunTest")
    systemProperty("eval.reportDir", evalReportDir.get().asFile.absolutePath)
    systemProperty("eval.updateBaseline", file("src/test/resources/eval/baseline.tsv").absolutePath)
    // 書き直しは結果がキャッシュから出ても意味がないので、毎回回す
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
