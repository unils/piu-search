// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 辞書の資源（brands.tsv など）を検査する道具。どのモジュールからも依存させず、APK に入れない
plugins {
    alias(libs.plugins.kotlin.jvm)
    // core:* と同じ検査（依存に Android がないこと・網羅率）を掛ける。検査の誤りで表の誤りを見逃さないため
    id("piu.core-checks")
    id("piu.kdoc-check")
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

java {
    val target = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    sourceCompatibility = target
    targetCompatibility = target
}

dependencies {
    // 綴りの正規化を照合と同じ規則で確かめる
    implementation(project(":core:text"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val brandTable: File =
    rootProject.file("core/search/src/main/resources/jp/piu/search/core/search/brands.tsv")

tasks.test {
    useJUnitPlatform()
    // 同梱の表そのものもテストで読む。表を変えたらテストを回し直す
    inputs.file(brandTable).withPropertyName("brandTable").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("brandTable", brandTable.absolutePath)
}

val checkBrandTable = tasks.register<JavaExec>("checkBrandTable") {
    group = "verification"
    description = "core:search の brands.tsv の書式（列の数・綴りの正規化・読みの字・重複）を確かめる"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("jp.piu.search.tools.dict.MainKt")
    val report = layout.buildDirectory.file("reports/brand-table.txt")
    inputs.file(brandTable).withPropertyName("brandTable").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report).withPropertyName("report")
    args(brandTable.absolutePath, report.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(checkBrandTable)
}
