# 開発への参加

不具合の報告・改善の提案・pull request を歓迎します。設計の文書は [docs/](docs/) にあり、すべて日本語で書いています。大きな変更は、作業を始める前に issue で相談してください。

参加する方には [行動規範](CODE_OF_CONDUCT.md) を守っていただくようお願いしています。脆弱性は公開の issue には書かず、[SECURITY.md](SECURITY.md) の手順で知らせてください。

## 開発環境を作る

以下は Linux（Ubuntu 26.04）で確かめた手順です。

### Java

Gradle 9 を動かすために、Java 17 以上の実行環境（JRE で構いません）が必要です。**JDK を別に入れる必要はありません**。コンパイルとテストに使う JDK 21 は、Gradle の toolchain（指定した版の JDK を探し、なければ自動で取得する機能。foojay のプラグイン）が `~/.gradle/jdks/` に取得します。

```bash
java -version   # 17 以上であれば動作します
```

### Android SDK

アプリが対応する端末は Android 9（API 28）以上です。`test-apps` も同じ最小 SDK を共有します。

SDK の部品は `android sdk`（Android SDK Command-line Tools に含まれる Android CLI。[公式の文書](https://d.android.com/tools/agents/android-cli)）で入れます。旧来の `sdkmanager` も動きますが、非推奨の警告が出ます。

1. Command-line Tools を取得し、`~/Android/Sdk/cmdline-tools/latest` に置きます。ファイル名と SHA-256 は <https://developer.android.com/studio#command-line-tools-only> の値を使ってください（以下は 2026-10-27 時点の値です）。

   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
   curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
   echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  commandlinetools-linux-15859902_latest.zip" | sha256sum -c
   unzip -q commandlinetools-linux-15859902_latest.zip && mv cmdline-tools latest
   rm commandlinetools-linux-15859902_latest.zip
   ```

2. ビルドに必要な部品を入れます。部品の名前は `/` で区切ります（`sdkmanager` の `;` ではありません）。

   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/android --sdk="$HOME/Android/Sdk" sdk install \
       platform-tools platforms/android-37.0 build-tools/36.0.0
   ```

   **`android sdk install` は、部品のライセンスへの同意を尋ねずに自動で記録します**（`~/Android/Sdk/licenses/` に書き込みます。2026-10-27 に CLI 1.1.16717390 で確認）。実行する前に、Android SDK のライセンス（<https://developer.android.com/studio/terms>）を読んでおいてください。

3. 計器テスト（エミュレータや実機の上で動くテスト）を手元で回す場合は、エミュレータとシステムイメージも入れ、仮想端末（AVD）を作ります。Linux ではエミュレータの高速化に KVM が必要です（ユーザーを `kvm` グループに入れ、ログインし直してください）。

   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/android --sdk="$HOME/Android/Sdk" sdk install \
       emulator system-images/android-37.0/default/x86_64 system-images/android-28/default/x86_64
   ~/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd -n api37 \
       -k "system-images;android-37.0;default;x86_64" -d pixel_8
   ```

   CI（GitHub Actions）の計器テスト（API 28 と 37）は、無料の runner ではエミュレータの起動が時間切れになることがあるため、手動で起動したとき（`workflow_dispatch`）だけ回します。`check` のジョブ（書式・Lint・単体テストなど）は push と pull request のたびに自動で回ります。

入れた部品は `android --sdk="$HOME/Android/Sdk" sdk list` で確かめられます。

**Android CLI は、実行したコマンドの名前などの利用状況を既定で Google に送ります**（[公式の文書](https://developer.android.com/tools/agents/android-cli)の「データの収集」。`sdkmanager` も内部で Android CLI を使います）。送らないようにするには、すべての実行に効く設定ファイルに書いておきます。

```bash
echo '--no-metrics' >> ~/.androidrc
```

### SDK の場所を Gradle に教える

リポジトリの根に `local.properties` を作ります（PC ごとにパスが異なるため、git の管理外です）。環境変数 `ANDROID_HOME` を設定しても構いません。

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

## 検査のコマンド

pull request を出す前に、CI と同じ形の検査を手元で通してください。

```bash
./gradlew --console=plain --no-build-cache clean check :app:assembleDebug :app:assembleRelease assembleAndroidTest
```

`:release-test` または `:benchmark` を変えたときは、続けて次のコマンドも回します（CI の `check` は、R8 がもう 1 回走って時間がかかり、壊れるのもテストコードのコンパイル程度のため、これらを組み立てません）。上のコマンドと 1 回にまとめると、Gradle のメモリ使用量が 9 GB を超えることがあります（2026-11-03 に 9 GB の制限の下で停止したことを確認）。

```bash
./gradlew --console=plain --no-build-cache :release-test:assembleBenchmarkRelease :benchmark:assembleBenchmarkRelease :benchmark:assembleNonMinifiedRelease
```

`check` には次の検査が含まれます。

| 検査 | 道具 | 失敗したときの対処 |
|---|---|---|
| 書式・ライセンスの見出し | Spotless ＋ ktlint | `./gradlew spotlessApply` で自動修正します |
| Android 特有の誤り | Android Lint（警告も失敗扱い） | 修正します。抑える場合は理由を添えて `lint.xml` に書きます |
| 単体テスト・評価セット・スクリーンショット | JUnit 6（`core:*`・`build-logic`）、JUnit 4 ＋ Robolectric ＋ Roborazzi（`data`・`app`） | 報告は `*/build/reports/tests/` に出ます。評価セットとスクリーンショットの更新は下の[テスト](#テスト)を参照してください |
| 網羅率 | Kover（`core:*` の行の網羅率がモジュールごとに 80% 未満で失敗） | テストを足します。数値を上げるためだけのテストは書きません |
| 公開 API の KDoc | detekt（`config/detekt/detekt.yml`） | 公開 API に KDoc を書きます |
| `core:*` の依存関係 | `build-logic` の `checkCoreDependencies` | `core:*` に Android のライブラリを入れません |
| ブランド表の書式 | `tools:dict` の `checkBrandTable` | 出力された行番号の箇所を直します |
| データベースのスキーマ | Room（`data/schemas/` の JSON） | 表の定義を変えたら、ビルドで書き直された JSON をコミットします（CI が差分を検査します） |
| マニフェストの権限 | `build-logic` の `check<Variant>ManifestPermissions` | `INTERNET` と `QUERY_ALL_PACKAGES` を持たせません |
| 依存のライセンス | Licensee（一覧は `app/build/reports/licensee/`） | 許可一覧（`app/build.gradle.kts`）にないライセンスの依存は入れません |

計器テストは、エミュレータか実機をつないで `./gradlew connectedDebugAndroidTest` で実行します。実行の前に、テスト用のアプリ（`test-apps/`）がつないだすべての端末に自動で入ります。テスト用のアプリを入れるだけなら `./gradlew :test-apps:installTestApps`、消すなら `./gradlew :test-apps:uninstallTestApps` を使います（`installTestApps` は、更新の試験に使う `transit` の `versionCode` 2 の APK を端末の `/data/local/tmp/` にも置きます）。対象にする端末を絞るときは環境変数 `ANDROID_SERIAL` を指定します。

計器テストのタスクには、前後の検査が付いています（`build-logic` の `piu.connected-test-checks`）。

- **前の検査（`checkNoAnrDialog`）**: 端末に「応答なし」（ANR）の窓が出ていれば、テストを始めずに失敗させます。エミュレータの起動中に重いビルドを同時に回すと、SystemUI が起動を終えられずにこの窓を出すことがあります。窓が残ったままだと、テストの HOME キーで窓が閉じるときに SystemUI が終了し（logcat の `user request after error`）、再起動する間のキー操作が失われて呼び出しの入口のテストが落ちます。エミュレータの起動が済んでからビルドしてください。窓の有無は `adb shell dumpsys window windows | grep 'Application Not Responding'` で確かめられます
- **後の検査（`verify<Variant>ConnectedTestCount`）**: 結果の XML（`*/build/outputs/androidTest-results/connected/<variant>/`）を読み、端末ごとのテスト実行数がソースの `@Test` の数に届かなければ失敗させます。`-Pandroid.testInstrumentationRunnerArguments.class=…` などで絞って回したときは、1 件以上の実行を求めます

手元で他の署名の APK を入れて試した後は、計器テストの前に `adb uninstall jp.piu.search` で消してください。以前の API 26 の試験（現在はサポート対象外）で、本体が入ったままだとテスト対象の APK が入らず（`INSTALL_FAILED_ALREADY_EXISTS`）、テストが 0 件のまま終わることがありました（2026-11-02 に確認）。現在は上の件数の検査が検出します。

計器テストを続けて何度も回す場合は、エミュレータを `-gpu lavapipe` で起動してください。`-gpu swiftshader_indirect`（`android-emulator-runner` の既定）では、全体を 1 回回すたびにエミュレータのプロセスのメモリが約 1 GB 増えて戻りません（エミュレータ 37.2.12.0・API 37 で 2026-11-02 に計測。`lavapipe` では 1 回あたり約 0.2 GB）。

detekt が `Could not initialize class ...PluginEnabler` で落ちたときは、`./gradlew --stop` で Gradle の daemon を止めてから実行し直してください（KSP を追加したときに一度発生。原因は未確認です）。

仕事用プロファイルの計器テストを手元で回すときは、エミュレータで次のようにプロファイルを作ってテスト用のアプリを入れます。AVD に残るため、終わったら `adb shell pm remove-user 10` で消してください。

```bash
adb shell pm create-user --profileOf 0 --managed work   # 作られたユーザーの番号（例: 10）を控える
adb shell am start-user 10
adb shell cmd package install-existing --user 10 <パッケージ名>
```

作成したプロファイルは、コマンドの終了コードだけでなく端末のユーザー一覧でも確かめてください。また、計器テストは端末の既定のアシスタントとクイック設定のタイルを一時的に変更し、終了時に元へ戻します。

### CI が確かめる範囲と、手で確かめる範囲

- 計器テストは CI で自動では回りません（手動で起動したときだけです）。仕事用プロファイルの計器テストは、端末に仕事用プロファイルがない場合は黙って飛ばします
- `:release-test` と `:benchmark` は `check` では組み立てません。変更したときは上の 2 つ目のコマンドを手で回してください
- セカンダリユーザーとプライベートスペースでの振る舞いは、実機・エミュレータでは確かめていません
- 再現可能なビルド（同じソースから同じ APK ができること）は CI では確かめていません。版を出すときに `tools/reproducible-build/check.sh` で確かめます（[docs/release.md](docs/release.md)）

### R8 を通した版の計器テスト

リリース版でだけ起きる不具合（R8 がクラス名を変えて同梱の資源が見つからなくなるなど）は、debug 版の計器テストでは見つかりません。`:release-test` は、app の `benchmarkRelease`（Baseline Profile のプラグインが release から作る build type。release と同じく R8 を通し、debug の鍵で署名するもの）を端末に入れ、アプリの外から UiAutomator と shell で操作して確かめます。テストのコード自体は R8 にかけません。

```bash
./gradlew :release-test:connectedBenchmarkReleaseAndroidTest
```

このテストでは次の 5 つを確かめます。

- ローマ字・ブランド表の読み（「やふー」）・漢字の検索語（Kuromoji）・ジャンルで検索結果が出ること
- プロセスを止めて開き直すと、保存した索引から差分で同期し、起動の記録が残ること
- デジタルアシスタントとクイック設定のタイルから同じ検索画面が開くこと
- 設定画面から「索引の状態」と「報告用の文」が開き、欠けの理由が R8 で変わらない名前（`java.`・`android.` のクラス名か固定の符号）のまま残ること
- アプリの例外のフレームに行の番号が残り、元のファイル名が出ない（`SourceFile` または null になる）こと

### 起動の速さと Baseline Profile

起動の速さは `:benchmark`（Macrobenchmark）で測ります。エミュレータの性能に左右され、実行に数分かかるため、`check` には入れていません。計測の対象は app の `benchmarkRelease`（R8 を通した版）です。

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

結果は `benchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/<端末>/jp.piu.search.benchmark-benchmarkData.json` に出ます。起動の状態（`COLD`: プロセスなし、`WARM`: プロセスあり・画面なし）とコンパイルの状態（`None`: 事前コンパイルなし、`BaselineProfile`: APK の Baseline Profile でコンパイル）の組ごとに 15 回測り、`timeToInitialDisplayMs`（最初の描画まで）と `timeToFullDisplayMs`（検索窓に焦点が来て打てるまで。アプリの `reportFullyDrawn`）の中央値・最小・最大と各回の値が記録されます。エミュレータの値はぶれるため、比較は同じ端末の前後だけで行ってください。

Baseline Profile（起動に使うコードを事前にコンパイルしておく規則）は、公式の Baseline Profile Gradle プラグイン（`androidx.baselineprofile`。[公式の文書](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)）で生成します。生成した `baseline-prof.txt` と起動用の `startup-prof.txt` は `app/src/release/generated/baselineProfiles/` に置いてリポジトリにコミットし、release の APK の `assets/dexopt/baseline.prof` に組み込みます。ビルドのたびには生成しません（`automaticGenerationDuringBuild = false`。生成がエミュレータに頼るため、ビルドのたびに回すと再現可能なビルドにならないためです）。画面や起動の流れを大きく変えたときは、API 33 以上のエミュレータか実機をつないで生成し直してください。

```bash
./gradlew :app:generateBaselineProfile
```

生成は app の `nonMinifiedRelease`（R8 を通さない版）で動き、R8 で名前が変わる前のクラス名で規則を書き出し、release のビルドで R8 が名前を合わせます。生成の流れが検索結果を待つため、テスト用のアプリは生成の前に自動で入ります。

生成時には、呼び出してから検索窓に焦点が来るまでの流れを起動用のプロファイル（`startup-prof.txt`）としても集めます（`includeInStartupProfile = true`。[公式の文書](https://developer.android.com/topic/performance/baselineprofiles/dex-layout-optimizations)）。release のビルドでは、R8 がこのクラス群を先頭の `classes.dex` にまとめます（DEX の並べ替え。`dexLayoutOptimization = true`）。効いているかは、AAB の R8 の記録で `classes.dex` が `"startup": true` になっていることで確かめられます。

```bash
./gradlew :app:bundleRelease
unzip -p app/build/outputs/bundle/release/app-release.aab BUNDLE-METADATA/com.android.tools/r8.json | jq .dexFiles
```

## コードの書き方

このプロジェクトのコードの多くは AI（Claude）が書いています。書くのが人でも AI でも同じ決まりに従うことで、出力をそろえ、レビューや提案をしやすくしています。

### 書式

- Kotlin の書式は ktlint に従います（Spotless から動かします）。`./gradlew spotlessApply` で直せます。TOML・XML などは行末の空白と末尾の改行だけをそろえます
- ソースファイル（`.kt`・`.gradle.kts`・XML など）の先頭に `// SPDX-License-Identifier: Apache-2.0`（XML は `<!-- … -->`、TOML・YAML・シェルスクリプトは `#`）を置きます。`.kt` と `.gradle.kts` は Spotless が検査します
- 識別子は英語、コメント・KDoc・文書は日本語で書きます
- Gradle のモジュール名は、Gradle の勧めに合わせて小文字とハイフンで書きます（`release-test`・`test-apps:two-launchers`）。Kotlin のパッケージ名にはハイフンを使えないため、パッケージ名ではハイフンを除きます
- 画面の文字列は、`values/strings.xml`（英語・既定）と `values-ja/strings.xml`（日本語）の両方に置きます。日本語以外の端末でも表示が壊れないようにするためです

### コメント

1. 常体（だ・である調）で書き、敬体（です・ます調）は使いません
2. 簡潔に書きます。「何であるか」は体言止め、「何をするか」は動詞で終えます。コードの逐語訳は書かず、**なぜそうしたか**（他の書き方を選ばなかった理由・OS やライブラリの癖・確かめた日付と版）を残します
3. 公開 API には KDoc を書きます（detekt が検査します）

例（`core/search` の `SearchQuery`）:

```kotlin
/**
 * 形態素解析器 [analyzer] で読みを付けた検索語。漢字を含まない検索語にはこの検索語自身を返す。
 *
 * 形態素解析をするので、入力のスレッドではなく裏のスレッドで呼ぶ。
 * 語の読みをつなぎ、読みのない語（英字・辞書にない漢字）は表記のまま残す（「乗り換え」→ のりかえ、「yahoo天気」→ yahooてんき）。
 */
public fun withReading(analyzer: ReadingAnalyzer): SearchQuery
```

### テスト

- 新しい処理にはテストを書きます。不具合を直すときは、その不具合を再現するテストを先に足します
- 照合の規則を変えたら、評価セット（`core/search/src/test/resources/eval/`）の結果が下がっていないことを確かめます（[docs/design/testing.md](docs/design/testing.md#評価セット)）。`check` は、検索語ごとの順位が基準（`eval/baseline.tsv`）より 1 件でも下がると失敗します。意図して変えたとき（照合の調整で一部の語が下がるのを受け入れる場合や、評価セットに行を足す場合）は、次のコマンドで基準を書き直し、`baseline.tsv` の差分を pull request に載せて理由を説明します

  ```bash
  ./gradlew :core:search:updateEvalBaseline
  ```
- 網羅率の数値を上げるためだけのテストは書きません
- `core:*` は Android に依存させません（照合と学習を JVM だけで速くテストするためです。[docs/design/architecture.md](docs/design/architecture.md)）
- 画面を変えてスクリーンショットの正解画像（`app/src/test/screenshots/`）が変わったときは、`./gradlew :app:recordRoborazziDebug` で撮り直し、差分を pull request に示します

### 失敗と再試行

コードを書くときとレビューするときは、次の点を確かめてください。

- `0`・`null`・空を失敗や不明とみなす箇所で、その値が正当な入力として現れることはないか（例: APEX の中の APK の更新時刻は正常でも `0` になります）
- 再試行が収束するか。同じ入力でもう一度実行して同じ結果になるとき、何が変わって再試行が止まるのか
- 利用者への知らせ（失敗の表示など）を出す条件と消す条件が対になっているか。「成功したら消す」としたときに、その成功が来ない経路はないか
- `catch` を置く箇所で、失敗の単位（同期全体・プロファイル・組・段）、読み直せば直る見込み、正常な不在との区別を決め、コメントに理由を書いたか。基準と例外の型ごとの分類は [architecture.md の「失敗の分け方」](docs/design/architecture.md#失敗の分け方)にあります
- 捕まえた例外の型をどこかに残しているか（件数だけでは報告の手がかりになりません）。型で決められない例外を、黙って「不完全」や空に丸めていないか
- 変化の検出の鍵（指紋・更新時刻）が作れないことを、読み取りの失敗にしていないか
- 読み取りが失敗したときも、変化の検出に使う一覧の観測（鍵・入口の集合・アーカイブ済みか）を今回の値で保存しているか。保存しないと、次の同期でも「変わった」と判定されて読み直しが止まりません
- 一覧にある項目を、詳細が読めないという理由で索引から落としていないか（検索に出なくなるうえ、「一覧にあって索引にない」とみなされて毎回読み直されます）

### 権限・通信・依存

- `INTERNET` と `QUERY_ALL_PACKAGES` の権限は持たせません（`check` が検査します）。処理を端末の中で完結させ、入っているアプリの一覧を外に出さないためです
- 新しい権限・通信・依存ライブラリは、設計（[docs/design/](docs/design/)）にないなら先に issue で相談してください
- 依存ライブラリを足すときは、ライセンスと大きさ（APK がどれだけ増えるか）を pull request に書いてください。使えるライセンスは、Licensee の許可一覧（`app/build.gradle.kts` の Apache-2.0・MIT・BSD-2-Clause・BSD-3-Clause）にあるものだけです
- Google Play 開発者サービスに依存しません。GrapheneOS など、Google のサービスを持たない端末でもそのまま動かすためです
- 広告・解析・クラッシュ報告の部品は入れません

### 文書

- 技術的な決定や方針は、理由とともに [docs/decisions.md](docs/decisions.md) に追記します
- 利用者に見える変更は、[CHANGELOG.md](CHANGELOG.md) の `[Unreleased]` に追記します。版を出すときは、その版の短い説明を `fastlane/metadata/android/{ja-JP,en-US}/changelogs/<versionCode>.txt` にも書きます（F-Droid の上限により 500 字以内。手順は [docs/release.md](docs/release.md)）
- 調査の記録（[docs/research/](docs/research/)）には、確認した日付と出典を付けます。一次資料（Android Developers の文書・AOSP のソース・ライブラリの公式文書とソース）に当たり、確かめられなかったことは「未確認」と明記します

## コミットの書き方

- 1 行目に変更の要約を日本語で書きます。マイルストーンのタスクなら `M0-02:` のように番号を頭に付けます
- 2 行目を空け、3 行目以降になぜそうしたかを書きます（何をしたかは差分でわかります）
- 1 つのコミットには 1 つの目的だけを入れます。書式だけの変更は別のコミットに分けます
- AI と書いたコミットには `Co-Authored-By:` の行を付けます
- 秘密の情報（署名鍵・パスワード・トークン）をコミットしないでください

## ライセンス

送っていただいた変更は、このリポジトリと同じ Apache License 2.0 で配布されます（Apache License 2.0 第 5 条）。
