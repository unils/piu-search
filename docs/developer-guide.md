# 開発環境と検査の手順

手元で開発環境を作り、ビルド・静的な検査・計器テスト・起動時間の計測を行う手順をまとめる。参加の方針やコード・コミットの規約は [CONTRIBUTING.md](../CONTRIBUTING.md) にある。

## ディレクトリとモジュール

| ディレクトリ | 内容 |
|---|---|
| `app/` | アプリ本体（画面と呼び出しの入口） |
| `core/text/` | 文字の正規化、かなとローマ字の変換 |
| `core/reading/` | 形態素解析（Kuromoji）による読みの付与 |
| `core/search/` | 索引、照合と並べ替え、ジャンルの語彙、学習の規則 |
| `data/` | 端末のアプリの読み取りとデータベース |
| `build-logic/` | 独自の Gradle プラグイン（マニフェストや依存関係などの検査） |
| `release-test/` | リリース版（R8 による最適化を通したもの）を外から操作する計器テスト |
| `benchmark/` | 起動時間の計測と Baseline Profile の生成 |
| `test-apps/` | 計器テストで端末に入れる、テスト用の小さなアプリ |
| `tools/dict/` | 辞書データ（ブランド名の読みの表など）の検査 |
| `tools/reproducible-build/` | 同じソースから同じ APK ができるかの確認 |
| `config/detekt/` | 静的解析（detekt）の設定 |
| `docs/` | 要件・設計・調査・決定の記録と、開発・版の出し方の手順 |
| `fastlane/metadata/` | F-Droid 向けの説明文と更新履歴 |

`core/` の 3 モジュールは Android に依存しない純粋な Kotlin で書かれており、JVM の上だけでテストできる。`release-test/`・`benchmark/`・`test-apps/`・`tools/` はテストと検査のためのもので、配布する APK には含まれない。

## 開発環境の構築

以下は Linux（Ubuntu 26.04）で確かめた手順である。

### Java

Gradle 9 を動かすために、Java 17 以上の実行環境（JRE で構わない）が必要である。**JDK を別に入れる必要はない**。コンパイルとテストに使う JDK 21 は、Gradle の toolchain（指定した版の JDK を探し、なければ自動で取得する機能。foojay のプラグイン）が `~/.gradle/jdks/` に取得する。

```bash
java -version   # 17 以上であれば動作します
```

### Android SDK とエミュレータ

アプリが対応する端末は Android 9（API 28）以上であり、`test-apps` も同じ最小 SDK を共有する。

SDK の部品は `android sdk`（Android SDK Command-line Tools に含まれる Android CLI。[公式の文書](https://d.android.com/tools/agents/android-cli)）で入れる。旧来の `sdkmanager` も動くが、非推奨の警告が出る。

1. Command-line Tools を取得し、`~/Android/Sdk/cmdline-tools/latest` に置く。ファイル名と SHA-256 は <https://developer.android.com/studio#command-line-tools-only> の値を使う（以下は 2026-10-27 時点の値である）。

   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
   curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
   echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  commandlinetools-linux-15859902_latest.zip" | sha256sum -c
   unzip -q commandlinetools-linux-15859902_latest.zip && mv cmdline-tools latest
   rm commandlinetools-linux-15859902_latest.zip
   ```

2. ビルドに必要な部品を入れる。部品の名前は `/` で区切る（`sdkmanager` の `;` ではない）。

   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/android --sdk="$HOME/Android/Sdk" sdk install \
       platform-tools platforms/android-37.0 build-tools/36.0.0
   ```

   **`android sdk install` は、部品のライセンスへの同意を尋ねずに自動で記録する**（`~/Android/Sdk/licenses/` に書き込む。2026-10-27 に CLI 1.1.16717390 で確認）。実行する前に、Android SDK のライセンス（<https://developer.android.com/studio/terms>）を読んでおく。

3. 計器テスト（エミュレータや実機の上で動くテスト）を手元で回す場合は、エミュレータとシステムイメージも入れ、仮想端末（AVD）を作る。Linux ではエミュレータの高速化に KVM が必要である（ユーザーを `kvm` グループに入れ、ログインし直す）。

   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/android --sdk="$HOME/Android/Sdk" sdk install \
       emulator system-images/android-37.0/default/x86_64 system-images/android-28/default/x86_64
   ~/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd -n api37 \
       -k "system-images;android-37.0;default;x86_64" -d pixel_8
   ```

入れた部品は `android --sdk="$HOME/Android/Sdk" sdk list` で確かめられる。

**Android CLI は、実行したコマンドの名前などの利用状況を既定で Google に送る**（[公式の文書](https://developer.android.com/tools/agents/android-cli)の「データの収集」。`sdkmanager` も内部で Android CLI を使う）。送らないようにするには、すべての実行に効く設定ファイルに書いておく。

```bash
echo '--no-metrics' >> ~/.androidrc
```

SDK の場所を Gradle に教えるには、リポジトリの根に `local.properties` を作る（PC ごとにパスが異なるため、git の管理外である）。環境変数 `ANDROID_HOME` を設定してもよい。

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

## ビルドと検査

デバッグ版の APK は `./gradlew :app:assembleDebug` で組み立てられ、`app/build/outputs/apk/debug/` に出力される。

pull request を出す前に、CI と同じ形の検査を手元で通す。

```bash
./gradlew --console=plain --no-build-cache clean check :app:assembleDebug :app:assembleRelease assembleAndroidTest
```

`:release-test` または `:benchmark` を変えたときは、続けて次のコマンドも回す（CI の `check` は、R8 がもう 1 回走って時間がかかり、壊れるのもテストコードのコンパイル程度のため、これらを組み立てない）。上のコマンドと 1 回にまとめると、Gradle のメモリ使用量が 9 GB を超えることがある（2026-11-03 に 9 GB の制限の下で停止したことを確認）。

```bash
./gradlew --console=plain --no-build-cache :release-test:assembleBenchmarkRelease :benchmark:assembleBenchmarkRelease :benchmark:assembleNonMinifiedRelease
```

`check` には次の検査が含まれる。

| 検査 | 道具 | 失敗したときの対処 |
|---|---|---|
| 書式・ライセンスの見出し | Spotless ＋ ktlint | `./gradlew spotlessApply` で自動修正する |
| Android 特有の誤り | Android Lint（警告も失敗扱い） | 修正する。抑える場合は理由を添えて `lint.xml` に書く |
| 単体テスト・評価セット・スクリーンショット | JUnit 6（`core:*`・`build-logic`）、JUnit 4 ＋ Robolectric ＋ Roborazzi（`data`・`app`） | 報告は `*/build/reports/tests/` に出る。評価セットとスクリーンショットの更新は [CONTRIBUTING.md](../CONTRIBUTING.md#テストと基準の更新) を参照 |
| 網羅率 | Kover（`core:*` の行の網羅率がモジュールごとに 80% 未満で失敗） | テストを足す。数値を上げるためだけのテストは書かない |
| 公開 API の KDoc | detekt（`config/detekt/detekt.yml`） | 公開 API に KDoc を書く |
| `core:*` の依存関係 | `build-logic` の `checkCoreDependencies` | `core:*` に Android のライブラリを入れない |
| ブランド表の書式 | `tools:dict` の `checkBrandTable` | 出力された行番号の箇所を直す |
| データベースのスキーマ | Room（`data/schemas/` の JSON） | 表の定義を変えたら、ビルドで書き直された JSON をコミットする（CI が差分を検査する） |
| マニフェストの権限 | `build-logic` の `check<Variant>ManifestPermissions` | `INTERNET` と `QUERY_ALL_PACKAGES` を持たせない |
| 依存のライセンス | Licensee（一覧は `app/build/reports/licensee/`） | 許可一覧（`app/build.gradle.kts`）にないライセンスの依存は入れない |

detekt が `Could not initialize class ...PluginEnabler` で落ちたときは、`./gradlew --stop` で Gradle の daemon を止めてから実行し直す（KSP を追加したときに一度発生。原因は未確認）。

## 計器テスト

### 実行と前後の検査

計器テストは、エミュレータか実機をつないで `./gradlew connectedDebugAndroidTest` で実行する。実行の前に、テスト用のアプリ（`test-apps/`）がつないだすべての端末に自動で入る。テスト用のアプリを入れるだけなら `./gradlew :test-apps:installTestApps`、消すなら `./gradlew :test-apps:uninstallTestApps` を使う（`installTestApps` は、更新の試験に使う `transit` の `versionCode` 2 の APK を端末の `/data/local/tmp/` にも置く）。対象にする端末を絞るときは環境変数 `ANDROID_SERIAL` を指定する。

計器テストは端末の既定のアシスタントとクイック設定のタイルを一時的に変更し、終了時に元へ戻す。タスクには前後の検査が付いている（`build-logic` の `piu.connected-test-checks`）。

- **前の検査（`checkNoAnrDialog`）**: 端末に「応答なし」（ANR）の窓が出ていれば、テストを始めずに失敗させる。エミュレータの起動中に重いビルドを同時に回すと、SystemUI が起動を終えられずにこの窓を出すことがある。窓が残ったままだと、テストの HOME キーで窓が閉じるときに SystemUI が終了し（logcat の `user request after error`）、再起動する間のキー操作が失われて呼び出しの入口のテストが落ちる。エミュレータの起動が済んでからビルドする。窓の有無は `adb shell dumpsys window windows | grep 'Application Not Responding'` で確かめられる
- **後の検査（`verify<Variant>ConnectedTestCount`）**: 結果の XML（`*/build/outputs/androidTest-results/connected/<variant>/`）を読み、端末ごとのテスト実行数がソースの `@Test` の数に届かなければ失敗させる。`-Pandroid.testInstrumentationRunnerArguments.class=…` などで絞って回したときは、1 件以上の実行を求める

手元で他の署名の APK を入れて試した後は、計器テストの前に `adb uninstall jp.piu.search` で消す。以前の API 26 の試験（現在はサポート対象外）で、本体が入ったままだとテスト対象の APK が入らず（`INSTALL_FAILED_ALREADY_EXISTS`）、テストが 0 件のまま終わることがあった（2026-11-02 に確認）。現在は上の件数の検査が検出する。

計器テストを続けて何度も回す場合は、エミュレータを `-gpu lavapipe` で起動する。`-gpu swiftshader_indirect`（`android-emulator-runner` の既定）では、全体を 1 回回すたびにエミュレータのプロセスのメモリが約 1 GB 増えて戻らない（エミュレータ 37.2.12.0・API 37 で 2026-11-02 に計測。`lavapipe` では 1 回あたり約 0.2 GB）。

### 仕事用プロファイル

仕事用プロファイルの計器テストを手元で回すときは、エミュレータで次のようにプロファイルを作ってテスト用のアプリを入れる。AVD に残るため、終わったら `adb shell pm remove-user 10` で消す。

```bash
adb shell pm create-user --profileOf 0 --managed work   # 作られたユーザーの番号（例: 10）を控える
adb shell am start-user 10
adb shell cmd package install-existing --user 10 <パッケージ名>
```

作成したプロファイルは、コマンドの終了コードだけでなく端末のユーザー一覧でも確かめる。

### R8 を通した版の計器テスト

リリース版でだけ起きる不具合（R8 がクラス名を変えて同梱の資源が見つからなくなるなど）は、debug 版の計器テストでは見つからない。`:release-test` は、app の `benchmarkRelease`（Baseline Profile のプラグインが release から作る build type。release と同じく R8 を通し、debug の鍵で署名するもの）を端末に入れ、アプリの外から UiAutomator と shell で操作して確かめる。テストのコード自体は R8 にかけない。

```bash
./gradlew :release-test:connectedBenchmarkReleaseAndroidTest
```

このテストでは次の 5 つを確かめる。

- ローマ字・ブランド表の読み（「やふー」）・漢字の検索語（Kuromoji）・ジャンルで検索結果が出ること
- プロセスを止めて開き直すと、保存した索引から差分で同期し、起動の記録が残ること
- デジタルアシスタントとクイック設定のタイルから同じ検索画面が開くこと
- 設定画面から「索引の状態」と「報告用の文」が開き、欠けの理由が R8 で変わらない名前（`java.`・`android.` のクラス名か固定の符号）のまま残ること
- アプリの例外のフレームに行の番号が残り、元のファイル名が出ない（`SourceFile` または null になる）こと

## 起動時間の計測と Baseline Profile

起動の速さは `:benchmark`（Macrobenchmark）で測る。エミュレータの性能に左右され、実行に数分かかるため、`check` には入れていない。計測の対象は app の `benchmarkRelease`（R8 を通した版）である。

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

結果は `benchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/<端末>/jp.piu.search.benchmark-benchmarkData.json` に出る。起動の状態（`COLD`: プロセスなし、`WARM`: プロセスあり・画面なし）とコンパイルの状態（`None`: 事前コンパイルなし、`BaselineProfile`: APK の Baseline Profile でコンパイル）の組ごとに 15 回測り、`timeToInitialDisplayMs`（最初の描画まで）と `timeToFullDisplayMs`（検索窓に焦点が来て打てるまで。アプリの `reportFullyDrawn`）の中央値・最小・最大と各回の値が記録される。エミュレータの値はぶれるため、比較は同じ端末の前後だけで行う。

Baseline Profile（起動に使うコードを事前にコンパイルしておく規則）は、公式の Baseline Profile Gradle プラグイン（`androidx.baselineprofile`。[公式の文書](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)）で生成する。生成した `baseline-prof.txt` と起動用の `startup-prof.txt` は `app/src/release/generated/baselineProfiles/` に置いてリポジトリにコミットし、release の APK の `assets/dexopt/baseline.prof` に組み込む。ビルドのたびには生成しない（`automaticGenerationDuringBuild = false`。生成がエミュレータに頼るため、ビルドのたびに回すと再現可能なビルドにならないためである）。画面や起動の流れを大きく変えたときは、API 33 以上のエミュレータか実機をつないで生成し直す。

```bash
./gradlew :app:generateBaselineProfile
```

生成は app の `nonMinifiedRelease`（R8 を通さない版）で動き、R8 で名前が変わる前のクラス名で規則を書き出し、release のビルドで R8 が名前を合わせる。生成の流れが検索結果を待つため、テスト用のアプリは生成の前に自動で入る。

生成時には、呼び出してから検索窓に焦点が来るまでの流れを起動用のプロファイル（`startup-prof.txt`）としても集める（`includeInStartupProfile = true`。[公式の文書](https://developer.android.com/topic/performance/baselineprofiles/dex-layout-optimizations)）。release のビルドでは、R8 がこのクラス群を先頭の `classes.dex` にまとめる（DEX の並べ替え。`dexLayoutOptimization = true`）。効いているかは、AAB の R8 の記録で `classes.dex` が `"startup": true` になっていることで確かめられる。

```bash
./gradlew :app:bundleRelease
unzip -p app/build/outputs/bundle/release/app-release.aab BUNDLE-METADATA/com.android.tools/r8.json | jq .dexFiles
```

## CI と手元の分担

- `check` のジョブ（書式・Lint・単体テストなど）は push と pull request のたびに自動で回る
- CI（GitHub Actions）の計器テスト（API 28 と 37）は、無料の runner ではエミュレータの起動が時間切れになることがあるため、手動で起動したとき（`workflow_dispatch`）だけ回す。仕事用プロファイルの計器テストは、端末に仕事用プロファイルがない場合は黙って飛ばす
- `:release-test` と `:benchmark` は `check` では組み立てない。変更したときは上の 2 つ目のコマンドを手で回す
- セカンダリユーザーとプライベートスペースでの振る舞いは、実機・エミュレータでは確かめていない
- 再現可能なビルド（同じソースから同じ APK ができること）は CI では確かめていない。版を出すときに `tools/reproducible-build/check.sh` で確かめる（[release.md](release.md)）
