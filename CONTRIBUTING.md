# 開発への参加

不具合の報告・提案・pull request を歓迎する。設計の文書は [docs/](docs/) にあり、日本語で書いている。大きな変更は、先に issue で相談してほしい。

参加する人は [行動規範](CODE_OF_CONDUCT.md) に従う。脆弱性は issue ではなく、[SECURITY.md](SECURITY.md) の方法で知らせてほしい。

## 開発環境を作る

Linux（Ubuntu 26.04）で確かめた手順。

### Java

Java の実行環境（JRE 17 以上。Gradle 9 を動かすため）があればよい。**JDK は入れなくてよい**。コンパイルとテストに使う JDK 21 は、Gradle の toolchain（指定した版の JDK を探し、なければ取得する機能。foojay のプラグイン）が `~/.gradle/jdks/` に取得する。

```bash
java -version   # 17 以上なら足りる
```

### Android SDK

SDK の部品は `android sdk`（Android CLI。Android SDK Command-line Tools に入っている）で入れる。旧来の `sdkmanager` も動くが、非推奨の警告が出る。`android sdk` の説明は公式の文書 <https://d.android.com/tools/agents/android-cli> にある。

1. Command-line Tools を取得し、`~/Android/Sdk/cmdline-tools/latest` に置く。ファイル名と SHA-256 は <https://developer.android.com/studio#command-line-tools-only> の値（下は 2026-10-27 の値）

   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
   curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
   echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  commandlinetools-linux-15859902_latest.zip" | sha256sum -c
   unzip -q commandlinetools-linux-15859902_latest.zip && mv cmdline-tools latest
   rm commandlinetools-linux-15859902_latest.zip
   ```

2. ビルドに要る部品を入れる。部品の名前は `/` で区切る（`sdkmanager` の `;` ではない）

   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/android --sdk="$HOME/Android/Sdk" sdk install \
       platform-tools platforms/android-37.0 build-tools/36.0.0
   ```

   **`android sdk install` は、部品のライセンスへの同意を尋ねずに記録する**（`~/Android/Sdk/licenses/` に書く。2026-10-27 に CLI 1.1.16717390 で確かめた）。入れる前に、Android SDK のライセンス（<https://developer.android.com/studio/terms>）を読んでおく。

3. 計器テスト（エミュレータの上で動くテスト）を手元で回すなら、エミュレータとシステムイメージも入れ、仮想端末（AVD）を作る。Linux ではエミュレータの高速化に KVM が要る（ユーザーを `kvm` グループに入れ、ログインし直す）

   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/android --sdk="$HOME/Android/Sdk" sdk install \
       emulator system-images/android-37.0/default/x86_64 system-images/android-26/default/x86_64
   ~/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd -n api37 \
       -k "system-images;android-37.0;default;x86_64" -d pixel_8
   ```

   CI（GitHub Actions）の計器テスト（API 26 と 37）は、無料の runner ではエミュレータの起動が時間切れになることがあるので、手動で起動したとき（`workflow_dispatch`）だけ回す。check のジョブ（書式・Lint・単体テストなど）は push のたびに回る。

入れた部品は `android --sdk="$HOME/Android/Sdk" sdk list` で確かめられる。

**Android CLI は、実行したコマンドの名前などの利用状況を既定で Google に送る**（[公式の文書](https://developer.android.com/tools/agents/android-cli)の「データの収集」。`sdkmanager` も中で Android CLI を使う）。送らないようにするには、すべての実行に効く設定ファイルに書いておく。

```bash
echo '--no-metrics' >> ~/.androidrc
```

### SDK の場所を Gradle に教える

リポジトリの根に `local.properties` を作る（git の管理外。PC ごとに違うため）。

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

環境変数 `ANDROID_HOME` を設定してもよい。

## 検査のコマンド

pull request の前に、CI と同じ形の検査を 1 回で通す。

```bash
./gradlew --console=plain --no-build-cache clean check :app:assembleDebug :app:assembleRelease assembleAndroidTest
```

`:release-test`・`:benchmark` を変えたら、続けて次も回す（CI の check はこれらを組み立てない。R8 がもう 1 回走って時間がかかり、壊れるのはテストのコードのコンパイルくらいのため）。上と 1 回にまとめると、Gradle のメモリが 9 GB を超えることがある（2026-11-03 に、9 GB の制限の下で止まった）。

```bash
./gradlew --console=plain --no-build-cache :release-test:assembleMinified :benchmark:assembleMinified :benchmark:assembleNonMinified
```

`check` に入っているもの:

| 検査 | 道具 | 失敗したとき |
|---|---|---|
| 書式・ライセンスの見出し | Spotless ＋ ktlint | `./gradlew spotlessApply` で直す |
| Android 特有の誤り | Android Lint（警告も失敗にする） | 直す。抑えるなら理由を書いて `lint.xml` に |
| 単体テスト | JUnit 6（`core:*`・build-logic）、JUnit 4 ＋ Robolectric（`data`・`app`） | 報告は `*/build/reports/tests/` |
| 網羅率 | Kover。`core:*` の行の網羅率が 80% 未満で失敗 | テストを足す。数値を上げるためだけのテストは書かない |
| 公開 API の KDoc | detekt（`config/detekt/detekt.yml`） | KDoc を書く |
| `core:*` の依存 | build-logic の `checkCoreDependencies` | `core:*` に Android のライブラリを入れない |
| ブランド表の書式 | tools:dict の `checkBrandTable` | 出力の行の番号の所を直す |
| データベースのスキーマ | Room（`data/schemas/` の JSON） | 表を変えたら、ビルドで書き直された JSON をコミットする（CI が確かめる） |
| マニフェストの権限 | build-logic の `check<Variant>ManifestPermissions` | `INTERNET`・`QUERY_ALL_PACKAGES` を持たせない |
| 依存のライセンス | Licensee。一覧は `app/build/reports/licensee/` | 許す一覧（`app/build.gradle.kts`）にないライセンスの依存は入れない |

計器テストは `./gradlew connectedDebugAndroidTest`（エミュレータか実機をつないで）。先にテスト用のアプリ（`test-apps/`）を、つないだすべての端末に入れる。入れるだけなら `./gradlew :test-apps:installTestApps`、消すなら `./gradlew :test-apps:uninstallTestApps`。端末を選ぶなら環境変数 `ANDROID_SERIAL`。

`installTestApps` は、更新の試験に使う版（`transit` の versionCode 2）を端末の `/data/local/tmp/` にも置く。

計器テストのタスクには、前後の検査が付いている（build-logic の `piu.connected-test-checks`）。

- 前（`checkNoAnrDialog`）: 端末に「応答なし」（ANR）の窓が出ていれば、テストを始めずに落とす。エミュレータの起動中に重いビルドを同時に回すと、SystemUI が起動を終えられずにこの窓を出すことがある。窓が残ったままだと、呼び出しの入口のテストが落ちる（テストの HOME キーで窓が閉じるときに SystemUI が終了し（logcat の `user request after error`）、起動し直す間のキーが失われる）。起動が済んでからビルドする。窓は `adb shell dumpsys window windows | grep 'Application Not Responding'` で確かめられる
- 後（`verify<Variant>ConnectedTestCount`）: 結果の XML（`*/build/outputs/androidTest-results/connected/<variant>/`）で、端末ごとのテストの数がソースの `@Test` の数に届かなければ落とす。`-Pandroid.testInstrumentationRunnerArguments.class=…` などで絞って回したときは、1 件以上を求める

APK を手で入れて試したら、計器テストの前に `adb uninstall jp.piu.search` で消す。API 26 では、本体が入ったままだとテストの APK が入らず（`INSTALL_FAILED_ALREADY_EXISTS`）、テストが 0 件になる（2026-11-02 に確かめた）。上の件数の検査が落とす。

計器テストを続けて何度も回すなら、エミュレータを `-gpu lavapipe` で起動する。`-gpu swiftshader_indirect`（android-emulator-runner の既定）では、全体を 1 回回すたびにエミュレータのプロセスのメモリが約 1 GB 増えて戻らない（エミュレータ 37.2.12.0・API 37 で、2026-11-02 に測った。`lavapipe` は 1 回 0.2 GB ほど）。

detekt が `Could not initialize class ...PluginEnabler` で落ちたら、Gradle の daemon を `./gradlew --stop` で止めてから回し直す（KSP を足したときに一度起きた。原因は未確認）。

仕事用プロファイルを試すなら、エミュレータで次のようにする（終わったら `adb shell pm remove-user 10` で消す。AVD に残るため）。

`pm create-user` は、成功しても終了コードが 1 になることがある（API 26）。計器テストは、端末の既定のアシスタントとクイック設定のタイルを一時的に変え、終わったら元に戻す。

API 26 では `installTestApps` がテスト用のアプリを仕事用プロファイルに入れないので、`cmd package install-existing --user 10` で入れる。

```bash
adb shell pm create-user --profileOf 0 --managed work   # 作られたユーザーの番号（例: 10）を控える
adb shell am start-user 10
adb shell cmd package install-existing --user 10 <パッケージ名>
```

### R8 を通した版の計器テスト

release でだけ起きる誤り（R8 がクラスの名前を変えて、同梱の資源が見つからなくなるなど）は、debug の計器テストでは見つからない。app の `minified`（release と同じく R8 を通し、debug の鍵で署名した版）を端末に入れ、`:release-test` がアプリの外から（UiAutomator と shell で）試す。

```bash
./gradlew :test-apps:installTestApps :release-test:connectedMinifiedAndroidTest
```

確かめること: ローマ字・ブランド表の読み（「やふー」）・漢字の検索語（Kuromoji）・ジャンルで結果が出る、止めて開き直すと保存した索引から差分で同期し、起動の記録が残る、アシスタントとタイルから同じ画面が開く。テストのコードは R8 にかけない。

### 起動の速さと Baseline Profile

起動の速さは `:benchmark`（Macrobenchmark）で測る。エミュレータの値に頼り、1 回に数分かかるので、`check` には入れない。相手は app の `minified`。

```bash
./gradlew :benchmark:connectedMinifiedAndroidTest
```

結果は `benchmark/build/outputs/connected_android_test_additional_output/minified/connected/<端末>/jp.piu.search.benchmark-benchmarkData.json`。起動の状態（`COLD`: プロセスなし、`WARM`: プロセスあり・画面なし）とコンパイルの状態（`None`: 先にコンパイルしない、`BaselineProfile`: APK の Baseline Profile でコンパイルする）の組ごとに 15 回測り、`timeToInitialDisplayMs`（最初の描画まで）と `timeToFullDisplayMs`（検索窓に焦点が来て打てるまで。アプリの `reportFullyDrawn`）の中央値・最小・最大と各回の値が入る。エミュレータの値はぶれるので、比べるのは同じ端末の前後だけにする。

Baseline Profile（起動に使うコードを先にコンパイルしておく規則）は、生成したものを `app/src/main/baselineProfiles/baseline-prof.txt` に置いてリポジトリに入れる。ビルドのたびには生成しない（生成はエミュレータに頼り、同じソースから同じ APK を作る再現可能なビルドにならないため）。画面や起動の流れを大きく変えたら、API 33 以上のエミュレータで生成し直す。

```bash
./gradlew :test-apps:installTestApps :benchmark:connectedNonMinifiedAndroidTest :benchmark:copyBaselineProfile
```

生成は app の `nonMinified`（R8 を通さない版）で回す。R8 で名前が変わる前の名前で規則を書き、release のビルドで R8 が名前を合わせる。

## コードの書き方

このプロジェクトのコードの多くは AI（Claude）が書いている。書くのが人でも AI でも、同じ決まりに従う。決まりが文書にあると、出力がそろい、レビューと提案がしやすい。

### 書式

- Kotlin の書式は ktlint に従う（Spotless から動かす）。`./gradlew spotlessApply` で直る。TOML・XML などは行末の空白と最後の改行だけを揃える
- ソースファイル（`.kt`・`.gradle.kts`・XML など）の先頭に `// SPDX-License-Identifier: Apache-2.0`（XML は `<!-- … -->`、TOML・YAML・シェルは `#`）を置く。`.kt` と `.gradle.kts` は Spotless が確かめる
- 識別子は英語。コメント・KDoc・文書は日本語
- Gradle のモジュールの名前は、小文字とハイフンで書く（`release-test`・`test-apps:two-launchers`。Gradle の勧め）。パッケージ名にはハイフンを使えないので、パッケージ名ではハイフンを除く
- 画面の文字列は、`values/strings.xml`（英語。既定）と `values-ja/strings.xml`（日本語）の両方に置く。日本語以外の端末でも壊れないようにするため

### コメント

1. 常体で書く。敬体（です・ます）を使わない
2. 簡潔に書く。「何であるか」は体言止め、「何をするか」は動詞で終える。コードの逐語訳は書かず、**なぜそうしたか**（ほかの書き方を選ばなかった理由・OS やライブラリの癖・確かめた日付と版）を残す
3. 公開 API には KDoc を書く（detekt が確かめる）

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

- 新しい処理にはテストを書く。不具合を直すときは、その不具合を再現するテストを先に足す
- 照合を変えたら、評価セット（`core/search/src/test/resources/eval/`）の結果が下がっていないことを確かめる（[docs/design/testing.md](docs/design/testing.md#評価セット)）
- 網羅率の数値を上げるためだけのテストは書かない
- `core:*` は Android に依存させない（照合と学習を JVM だけで速くテストするため。[docs/design/architecture.md](docs/design/architecture.md)）
- 画面を変えてスクリーンショットの正解（`app/src/test/screenshots/`）が変わったら、`./gradlew :app:recordRoborazziDebug` で撮り直し、差分を pull request に書く

### 権限・通信・依存

- `INTERNET` と `QUERY_ALL_PACKAGES` を持たせない（check が確かめる）。端末の中で完結させ、入っているアプリの一覧を外に出さない
- 新しい権限・通信・依存は、設計（[docs/design/](docs/design/)）にないなら、先に issue で相談する
- 依存を足すときは、ライセンスと大きさ（APK がどれだけ増えるか）を pull request に書く。ライセンスは、Licensee の許す一覧（`app/build.gradle.kts`。Apache-2.0・MIT・BSD）にあるものだけ
- Google のサービス（Google Play 開発者サービス）に依存しない。GrapheneOS など、それを持たない端末でも動かすため
- 広告・解析・クラッシュの報告の部品を入れない

### 文書

- 決めたことは [docs/decisions.md](docs/decisions.md) に、理由と一緒に足す
- 利用者に見える変更は、[CHANGELOG.md](CHANGELOG.md) の `[Unreleased]` に足す。版を出すときは、その版の説明を `fastlane/metadata/android/ja-JP/changelogs/<versionCode>.txt` にも書く（500 バイトまで。IzzyOnDroid の上限）
- 調べたこと（[docs/research/](docs/research/)）には、日付と出典を付ける。一次資料（Android Developers の文書・AOSP のソース・ライブラリの公式の文書とソース）に当たり、確かめられなかったことは「未確認」と書く

## コミットの書き方

- 1 行目に要約を書く。日本語で書く。マイルストーンのタスクなら `M0-02:` のように番号を前に付ける
- 2 行目を空け、3 行目から、なぜそうしたかを書く（何をしたかは差分でわかる）
- 1 つのコミットに 1 つの目的。書式だけの変更は分ける
- AI と書いたなら `Co-Authored-By:` の行で示す
- 秘密情報（署名鍵・パスワード・トークン）をコミットしない

## ライセンス

送ってもらった変更は、このリポジトリと同じ Apache License 2.0 で配布する（Apache License 2.0 の第 5 条）。
