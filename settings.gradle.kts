// SPDX-License-Identifier: Apache-2.0

pluginManagement {
    // 自前の Gradle プラグイン（マニフェストの検査など）
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // PC に JRE しかなくても、コンパイルとテストに使う JDK を Gradle が取得できるようにする。
    // settings のプラグインには版の一覧を使えないので、版をここに書く
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
}

rootProject.name = "piu-search"

include(
    ":core:text",
    ":core:reading",
    ":core:search",
    ":data",
    ":app",
    // R8 を通した版（app の minified）を外から試す計器テスト（APK に入らない）
    ":releasetest",
    // 起動の速さの計測と Baseline Profile の生成（APK に入らない。check に入れない）
    ":benchmark",
    // 辞書の資源の検査（APK に入らない）
    ":tools:dict",
    // 計器テストで端末に入れるテスト用のアプリ（APK に入らない）。一覧は testapps/build.gradle.kts の説明
    ":testapps:common",
    ":testapps:transit",
    ":testapps:maps",
    ":testapps:mail",
    ":testapps:game",
    ":testapps:yahoo",
    ":testapps:nolauncher",
    ":testapps:twolaunchers",
)
