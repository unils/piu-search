# 開発への参加

不具合の報告・改善の提案・pull request を歓迎します。設計の文書は [docs/](docs/) にあり、すべて日本語で書いています。参加する方には [行動規範](CODE_OF_CONDUCT.md) を守っていただくようお願いしています。

このプロジェクトのコードの多くは AI（Claude）が書いています。書くのが人でも AI でも同じ決まりに従うことで、出力をそろえ、レビューや提案をしやすくしています。

## 報告と提案の出し方

- **不具合の報告・要望**: [Issues](https://github.com/unils/piu-search/issues) のテンプレートから作成してください。探したアプリが検索で出なかったり順位が低かったりする場合は、「検索で見つからない」のテンプレートを使えます
- **事前の相談**: 大きな変更や、設計（[docs/design/](docs/design/)）にない権限・通信・依存ライブラリの追加は、作業を始める前に issue で相談してください
- **脆弱性の報告**: 公開の issue には書かず、[SECURITY.md](SECURITY.md) の手順で非公開でお知らせください

## 守るべき方針

### 権限・通信・依存

- `INTERNET` と `QUERY_ALL_PACKAGES` の権限は持たせません（`check` が検査します）。処理を端末の中で完結させ、入っているアプリの一覧を外に出さないためです
- Google Play 開発者サービスには依存しません。GrapheneOS など、Google のサービスを持たない端末でもそのまま動かすためです
- 広告・解析・クラッシュ報告の部品は入れません
- 新しい権限・通信・依存ライブラリは、設計（[docs/design/](docs/design/)）にないなら先に issue で相談してください
- 依存ライブラリを足すときは、ライセンスと大きさ（APK がどれだけ増えるか）を pull request に書いてください。使えるライセンスは、Licensee の許可一覧（`app/build.gradle.kts` の Apache-2.0・MIT・BSD-2-Clause・BSD-3-Clause）にあるものだけです

### モジュールの境界

- `core:*` は Android に依存させません（`check` が検査します）。照合と学習を JVM だけで速くテストするためです（[docs/design/architecture.md](docs/design/architecture.md)）

## コードの書き方

### 書式と命名

- Kotlin の書式は ktlint に従います（Spotless から動かします）。`./gradlew spotlessApply` で直せます。TOML・XML などは行末の空白と末尾の改行だけをそろえます
- ソースファイル（`.kt`・`.gradle.kts`・XML など）の先頭に `// SPDX-License-Identifier: Apache-2.0`（XML は `<!-- … -->`、TOML・YAML・シェルスクリプトは `#`）を置きます。`.kt` と `.gradle.kts` は Spotless が検査します
- 識別子は英語、コメント・KDoc・文書は日本語で書きます
- Gradle のモジュール名は、Gradle の勧めに合わせて小文字とハイフンで書きます（`release-test`・`test-apps:two-launchers`）。Kotlin のパッケージ名にはハイフンを使えないため、パッケージ名ではハイフンを除きます
- 画面の文字列は、`values/strings.xml`（英語・既定）と `values-ja/strings.xml`（日本語）の両方に置きます。日本語以外の端末でも表示が壊れないようにするためです

### コメントと KDoc

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

## テスト・文書・コミット

### テストと基準の更新

- 新しい処理にはテストを書きます。不具合を直すときは、その不具合を再現するテストを先に足します
- 網羅率の数値を上げるためだけのテストは書きません
- 照合の規則を変えたら、評価セット（`core/search/src/test/resources/eval/`）の結果が下がっていないことを確かめます（[docs/design/testing.md](docs/design/testing.md#評価セット)）。`check` は、検索語ごとの順位が基準（`eval/baseline.tsv`）より 1 件でも下がると失敗します。意図して変えたとき（照合の調整で一部の語が下がるのを受け入れる場合や、評価セットに行を足す場合）は、次のコマンドで基準を書き直し、`baseline.tsv` の差分を pull request に載せて理由を説明します

  ```bash
  ./gradlew :core:search:updateEvalBaseline
  ```

- 画面を変えてスクリーンショットの正解画像（`app/src/test/screenshots/`）が変わったときは、`./gradlew :app:recordRoborazziDebug` で撮り直し、差分を pull request に示します

### 文書と変更履歴

- 技術的な決定や方針は、理由とともに [docs/decisions.md](docs/decisions.md) に追記します
- 利用者に見える変更は、[CHANGELOG.md](CHANGELOG.md) の `[Unreleased]` に追記します。版を出すときは、その版の短い説明を `fastlane/metadata/android/{ja-JP,en-US}/changelogs/<versionCode>.txt` にも書きます（F-Droid の上限により 500 字以内。手順は [docs/release.md](docs/release.md)）
- 調査の記録（[docs/research/](docs/research/)）には、確認した日付と出典を付けます。一次資料（Android Developers の文書・AOSP のソース・ライブラリの公式文書とソース）に当たり、確かめられなかったことは「未確認」と明記します

### コミットの書き方と pull request 前の検査

- 1 行目に変更の要約を日本語で書きます。マイルストーンのタスクなら `M0-02:` のように番号を頭に付けます
- 2 行目を空け、3 行目以降になぜそうしたかを書きます（何をしたかは差分でわかります）
- 1 つのコミットには 1 つの目的だけを入れます。書式だけの変更は別のコミットに分けます
- AI と書いたコミットには `Co-Authored-By:` の行を付けます
- 秘密の情報（署名鍵・パスワード・トークン）をコミットしないでください

pull request を出す前に、CI と同じ形の検査を手元で通してください。

```bash
./gradlew --console=plain --no-build-cache clean check :app:assembleDebug :app:assembleRelease assembleAndroidTest
```

開発環境の構築、`check` に含まれる検査の内訳、計器テスト（仕事用プロファイルの作成や `:release-test` を含む）、起動の速さの計測と Baseline Profile の生成手順は [docs/developer-guide.md](docs/developer-guide.md) にまとめています。

## ライセンス

送っていただいた変更は、このリポジトリと同じ Apache License 2.0 で配布されます（Apache License 2.0 第 5 条）。
