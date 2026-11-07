// SPDX-License-Identifier: Apache-2.0

tasks {
    register("installTestApps") {
        group = "install"
        description = "テスト用のアプリ（debug）を、つないだすべての端末に入れる。入っていれば上書きする。更新の試験用の版は端末に置く"
        dependsOn(testApps().map { ":testapps:$it:installDebug" })
        dependsOn(updatableTestApps().map { ":testapps:$it:pushUpdateApk" })
    }

    register("uninstallTestApps") {
        group = "install"
        description = "テスト用のアプリを、つないだすべての端末から消す"
        dependsOn(testApps().map { ":testapps:$it:uninstallDebug" })
    }
}

/** 端末に入れるモジュールの名前。settings.gradle.kts に足したアプリが自動で加わる。 */
fun testApps(): List<String> = childProjects.keys.filter { it != "common" }.sorted()

/** 更新の試験用の版（`src/update`）を持つモジュールの名前。build-logic の TestAppPlugin の説明。 */
fun updatableTestApps(): List<String> = testApps().filter { file("$it/src/update").isDirectory }
