// SPDX-License-Identifier: Apache-2.0

plugins {
    // 根のプロジェクトにも check を用意し、書式の検査をそこに掛ける
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // build-logic の piu.core-checks・piu.kdoc-check が掛ける。クラスを根で読み込み、build-logic と共有する
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.spotless)
}

// 見出しと本文の間に空行を 1 つ置く。Spotless は見出しの後に本文を詰めて書くので、改行を 2 つ含める
val licenseHeader = "// SPDX-License-Identifier: Apache-2.0\n\n"
val ktlintVersion: String = libs.versions.ktlint.get()

/**
 * 書式の検査の対象。[includes] に合うファイルを、除くディレクトリに降りずに集める。
 *
 * Spotless の targetExclude は、除くファイルを別の木として根から集めて差し引くので、ビルドのディレクトリの中まで辿る。
 * 並列のビルドが同じディレクトリを書き換えている最中に読むと失敗するため、除外は木を辿る段で効かせる。
 * .claude/worktrees には別のタスクの作業ディレクトリが入るので、これも外す。
 */
fun sources(vararg includes: String): ConfigurableFileTree = fileTree(rootDir) {
    include(*includes)
    exclude("**/build/", "**/.gradle/", "**/.kotlin/", ".claude/")
}

spotless {
    kotlin {
        target(sources("**/*.kt"))
        ktlint(ktlintVersion).setEditorConfigPath(rootProject.file(".editorconfig"))
        licenseHeader(licenseHeader)
    }
    kotlinGradle {
        target(sources("**/*.gradle.kts"))
        ktlint(ktlintVersion)
            .setEditorConfigPath(rootProject.file(".editorconfig"))
            // 版の一覧の参照（libs.versions.minSdk.get().toInt()）は 4 段の連鎖で、既定では 1 段ずつ折られて読みにくい
            .editorConfigOverride(mapOf("ktlint_chain_method_rule_force_multiline_when_chain_operator_count_greater_or_equal_than" to 6))
        // 見出しの後の最初の文（import・plugins など）を本文の始まりとみなす
        licenseHeader(licenseHeader, "^(import|plugins|pluginManagement|rootProject|[a-z]+ *[({])")
    }
    format("misc") {
        // Markdown は行末の空白を改行に使うことがあり、文書の側の決まりに任せる
        target(sources("**/*.toml", "**/*.xml", ".gitignore", ".editorconfig"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") {
    // build-logic は別のビルドなので、根の check から明示して回す
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
