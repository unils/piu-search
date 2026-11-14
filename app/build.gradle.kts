// SPDX-License-Identifier: Apache-2.0

import app.cash.licensee.UnusedAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("piu.manifest-permission-check")
    id("piu.kdoc-check")
    id("piu.connected-test-checks")
    alias(libs.plugins.licensee)
    alias(libs.plugins.roborazzi)
    // release から benchmarkRelease（R8 を通し、debug の鍵で署名する）と nonMinifiedRelease（R8 を通さない）の
    // build type を作り、:benchmark が生成した Baseline Profile を取り込む
    alias(libs.plugins.androidx.baselineprofile)
}

// APK に入る依存（debug と release の runtimeClasspath。core:* と data の依存も含む）のライセンスを確かめる。
// 一覧は build/reports/licensee/<variant>/artifacts.json。許すのは、このアプリ（Apache-2.0）と一緒に配れる緩いライセンス
// （著作権の表示と条文を添えれば、閉じた改変版も含めて再配布できるもの）だけで、
// ここにないライセンスや、ライセンスを宣言していない依存があれば check が落ちる
licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    // 上の一覧は「一緒に配れる」方針の表で、依存ごとの許可ではない。使っていないものの警告は出さない
    unusedAction(UnusedAction.IGNORE)
    // 同じ一覧を variant ごとに APK の assets/licenses/artifacts.json に入れ、ライセンスの画面で描く（M5-01）。
    // 検査に使った一覧をそのまま出すので、画面の一覧と APK の中身がずれない
    bundleAndroidAsset.set(true)
    androidAssetReportPath.set("licenses/artifacts.json")
}

/**
 * 根の NOTICE（Kuromoji と IPADIC の注意書きを含む）と LICENSE（Apache-2.0 の全文）を、APK の assets/licenses/ に写す。
 * リポジトリに写しを置かず、根のファイルを正にする。
 */
abstract class CopyLegalTexts : DefaultTask() {
    /** 写すファイル。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val texts: ConfigurableFileCollection

    /** assets の根になるディレクトリ。AGP が variant ごとに決める。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** 写す。 */
    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licenses")
        dir.deleteRecursively()
        dir.mkdirs()
        texts.forEach { it.copyTo(dir.resolve(it.name)) }
    }
}

androidComponents {
    onVariants { variant ->
        val task =
            tasks.register<CopyLegalTexts>("copy${variant.name.replaceFirstChar(Char::uppercase)}LegalTexts") {
                texts.from(rootProject.file("NOTICE"), rootProject.file("LICENSE"))
            }
        variant.sources.assets?.addGeneratedSourceDirectory(task, CopyLegalTexts::outputDir)
    }
}

android {
    namespace = "jp.piu.search"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // 配布の後は変えられない（decisions.md の 2026-10-29）
        applicationId = "jp.piu.search"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.0.2"
        // ソースの置き場（設定画面の「このアプリについて」）。公開前の仮の値。公開の場所が決まったら（M7）ここだけを差し替える
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/unils/piu-search\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // release の鍵はビルドの設定に入れない（リポジトリの外に置き、組み立てた後に署名する）。
            // そのため Baseline Profile のプラグインが作る benchmarkRelease・nonMinifiedRelease は debug の鍵で署名される
        }
    }

    compileOptions {
        val target = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = target
        targetCompatibility = target
    }

    buildFeatures {
        compose = true
        // 版の名前とソースの URL を設定画面に出すため
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric 4.17 は SDK 37 の像で JDK の内部（jdk.internal.access）に触れる。
            // 公式の回避策（robolectric/robolectric#11434）
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    packaging {
        resources {
            // Kuromoji の 2 つの jar（core と ipadic）が、同じ内容の LICENSE.md と CONTRIBUTORS.md を持つ。
            // IPADIC の注意書き（NOTICE.md）とともに APK に残すため、除かずに片方を採る
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/CONTRIBUTORS.md")
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // 依存のモジュール（data）も app の lint でまとめて見る
        checkDependencies = true
        lintConfig = rootProject.file("lint.xml")
    }
}

// 生成は手で回す（./gradlew :app:generateBaselineProfile。API 33 以上の端末かエミュレータが要る）。
// 生成はエミュレータに頼り、ビルドのたびに回すと同じソースから同じ APK を作れない（再現可能なビルド。decisions.md の 2026-11-03）。
// 生成したもの（baseline-prof.txt と、起動用の startup-prof.txt）は src/release/generated/baselineProfiles/ に置いて
// リポジトリに入れ、release と benchmarkRelease が読む
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // R8 が startup-prof.txt のクラスを classes.dex にまとめる（DEX の並べ替え）。AGP 9.4 では既定で有効だが、
    // 既定が変わっても release の APK の中身が黙って変わらないよう、公式の文書と Now in Android に合わせて明示する
    dexLayoutOptimization = true
}

// スクリーンショットの正解の画像はリポジトリに入れ、変わったら差分を見て更新する（testing.md）。
// 単体テスト（check）では比べ（gradle.properties の roborazzi.test.verify）、記録し直すときは recordRoborazziDebug を回す
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // activity・Compose が既に使う ViewModel と保存状態を直接参照するため明示する
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Compose のテストが推移的に引く Espresso 3.5 は、新しい SDK にない InputManager.getInstance() を呼んで落ちる
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.roborazzi)
    // 計器テスト（エミュレータの上）。Robolectric のテストと同じ道具で書く
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // Robolectric の上で Compose のテスト用の空の Activity を起動するため
    debugImplementation(libs.compose.ui.test.manifest)
    // Baseline Profile を生成するモジュール
    baselineProfile(project(":benchmark"))
}

// 計器テストの前に、テスト用のアプリ（test-apps/）を端末に入れる。本物の PackageManager を相手に索引を確かめるため
tasks.named { it == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":test-apps:installTestApps")
}
