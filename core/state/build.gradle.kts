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
        // Android（data）から使うので、Android のモジュールと同じ版のバイトコードにそろえる
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
    // 写しが AppId・SearchKey・LearnedMemory などを公開するので api にする
    api(project(":core:search"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 法則のテストは、失敗の文に種と入力の列を出す。CI の記録でも読めるよう文を出す
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = false
    }
}
