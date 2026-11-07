// SPDX-License-Identifier: Apache-2.0

plugins {
    // PC に JRE しかないことがある。本体と同じく、コンパイルに使う JDK を Gradle に取得させる
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        // 本体と同じ版の一覧を使い、AGP の版を一か所で決める
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
