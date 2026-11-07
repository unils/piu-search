// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    id("piu.kdoc-check")
    id("piu.connected-test-checks")
}

android {
    namespace = "jp.piu.search.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        val target = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = target
        targetCompatibility = target
    }

    sourceSets {
        // MigrationTestHelper は、書き出したスキーマの JSON をテストの assets から読む。
        // Room の Gradle プラグインが足すのは計器テストの assets だけなので、Robolectric のテストにも足す
        getByName("test").assets.directories.add("schemas")
    }

    testOptions {
        // テストの assets（スキーマの JSON）を Robolectric に渡す
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric 4.17 は SDK 37 の像で JDK の内部（jdk.internal.access）に触れる（app と同じ回避策）
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    packaging {
        resources {
            // data のテスト用 APK にも Kuromoji の 2 つの jar が入る。同じ内容のファイルを片方だけ採る（app と同じ）
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/CONTRIBUTORS.md")
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        lintConfig = rootProject.file("lint.xml")
    }
}

room {
    // スキーマの JSON をリポジトリで管理し、データベースの版を上げるときの移行のテストに使う
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core:text"))
    api(project(":core:reading"))
    api(project(":core:search"))

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // 書き出したスキーマの JSON から古い版のデータベースを作り、移行を確かめる（MigrationTestHelper）
    testImplementation(libs.room.testing)
    // 計器テスト（エミュレータの上）。本物の PackageManager と LauncherApps を相手にする
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// 計器テストの前に、テスト用のアプリ（testapps/）を端末に入れる。本物の PackageManager を相手に索引を確かめるため
tasks.named { it == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testapps:installTestApps")
}
