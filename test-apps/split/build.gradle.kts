// SPDX-License-Identifier: Apache-2.0

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import jp.piu.search.buildlogic.InstallSplitApksTask

// split の APK を持つアプリ。動的な機能のモジュール（:test-apps:split:feature）の APK を split として一緒に入れる
plugins {
    id("piu.test-app")
}

android {
    dynamicFeatures += ":test-apps:split:feature"
}

val adbExecutable = extensions.getByType<ApplicationAndroidComponentsExtension>().sdkComponents.adb

// installDebug は base しか入れないので、続けて base と split を組で入れ直す（InstallSplitApksTask）
val installWithSplit =
    tasks.register<InstallSplitApksTask>("installWithSplit") {
        group = "install"
        description = "base と split の APK を組で入れる"
        dependsOn("assembleDebug", ":test-apps:split:feature:assembleDebug")
        // 機能のモジュールの出力は、既定の build ディレクトリにある（ほかのプロジェクトの設定に触れない）
        apks.from(
            layout.buildDirectory.dir("outputs/apk/debug").map { it.asFileTree.matching { include("*.apk") } },
            fileTree("feature/build/outputs/apk/debug") { include("*.apk") },
        )
        adb.set(adbExecutable)
    }

tasks.named { it == "installDebug" }.configureEach { finalizedBy(installWithSplit) }
