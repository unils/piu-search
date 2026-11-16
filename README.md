# Piu Search

[![CI](https://github.com/unils/piu-search/actions/workflows/ci.yml/badge.svg)](https://github.com/unils/piu-search/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Piu Search（日本語の端末での表示名は「Piu アプリ検索」）は、端末に入っているアプリを日本語の読み・表記ゆれ・ジャンル・提供元で探すための Android アプリです。

「のりかえ」「乗り換え」「norikae」のどれで打っても乗換案内のアプリが見つかるように、ひらがな・カタカナ・漢字・ローマ字の違いや送り仮名のゆれを吸収します。ホーム画面アプリ（ランチャー）ではないため、今使っているホーム画面のまま使えます。

## 特徴

- **読みで探せる** — 漢字の名前のアプリも、ひらがなやローマ字の読みで見つかります。ローマ字の入力途中（「のりk」など）でも候補が出ます
- **英語の名前も読みで探せる** — 「やふー」で Yahoo! のアプリが、「ぐーぐる」で Google のアプリが出ます
- **ジャンルで探せる** — 「ちず」と打つと、Google マップや Yahoo!マップなど地図のアプリがまとめて出ます
- **提供元で探せる** — 「楽天」と打つと、アプリの名前に「楽天」が入っていない楽天グループのアプリも出ます
- **使うほど賢くなる** — 検索で見つからずに別の言葉や「すべてのアプリ」の一覧から開いたアプリは、次から最初の言葉でも出るようになります。よく選ぶアプリは上に並びます。覚えた内容は設定画面で確認・削除・書き出し・読み込みができます
- **すぐ呼び出せる** — デジタルアシスタントに設定すれば、電源ボタンの長押しや画面の隅からのスワイプで開けます（操作は端末によって異なります）。クイック設定のタイルからも開けます
- **仕事用プロファイルに対応** — 個人用と仕事用のアプリを切り替えるか、混ぜて検索できます
- **通信しない** — `INTERNET` の権限を持たず、検索も学習もすべて端末の中で完結します

## 動作環境

- Android 9（API 28）以上
- Google Play 開発者サービスは不要です（GrapheneOS など、Google のサービスを入れていない端末でも動きます）

## インストール

[F-Droid](https://f-droid.org/) の公式リポジトリへの登録を申請する予定です。それまでは、[GitHub Releases](https://github.com/unils/piu-search/releases) から APK（`piu-search-<版>.apk`）を取得してインストールしてください。F-Droid と GitHub Releases ではメンテナの鍵で署名した同じ APK を配布するため、どちらから入れた場合も互いに上書きで更新できます。

インストールする前に、`apksigner verify --print-certs piu-search-<版>.apk` の出力にある `certificate SHA-256 digest` が次の指紋と一致することを確かめられます。

```
43b28f21f64eb56ccf2e690b8744ce1b13da23b440d7fc05eadd6c660736fb07
```

公開しているソースコードから同じ APK ができること（再現可能なビルド）の確かめ方は、[docs/release.md](docs/release.md) にあります。

## 使い方

初回起動時に呼び出し方の設定を案内します。あとから設定画面の「呼び出しの設定」でも開けます。

- **アシスタントとして使う**: 端末の設定で「デジタル アシスタント アプリ」に Piu アプリ検索を選びます
- **タイルから使う**: クイック設定の編集画面で、タイル「アプリ検索」を追加します（Android 13 以上では初回の案内から追加を依頼できます）

## プライバシー

Piu Search は外部に情報を一切送信しません。検索のための索引・学習・起動の記録・設定はすべて端末の中に保存し、クラウド（Google ドライブ）への自動バックアップから外しています。学習と起動の記録は設定画面からいつでも消去できます。

保存するデータの内訳や端末間の移行での扱い、権限の詳細は [PRIVACY.md](PRIVACY.md) をご覧ください。

## ビルド

手元でビルドするには、Java 17 以上の実行環境（JRE で構いません）と Android SDK が必要です。コンパイルとテストに使う JDK 21 は Gradle が自動で取得します。

```bash
git clone https://github.com/unils/piu-search.git
cd piu-search
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

組み立てた APK は `app/build/outputs/apk/debug/` に出力されます。Android SDK の導入手順やテスト・検査の実行方法は [CONTRIBUTING.md](CONTRIBUTING.md) にあります。

## ディレクトリの構成

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
| `docs/` | 要件・設計・調査・決定の記録と、版の出し方 |
| `fastlane/metadata/` | F-Droid 向けの説明文と更新履歴 |

`core/` の 3 モジュールは Android に依存しない純粋な Kotlin で書かれており、JVM の上だけでテストできます。`release-test/`・`benchmark/`・`test-apps/`・`tools/` はテストと検査のためのもので、配布する APK には含まれません。

## 開発に参加する

- 不具合の報告や要望は [Issues](https://github.com/unils/piu-search/issues) へお寄せください。探したアプリが見つからなかったときは、専用のテンプレートから報告できます
- 脆弱性は公開の issue には書かず、[SECURITY.md](SECURITY.md) の手順で非公開でお知らせください
- コードや文書の変更を送る前に [CONTRIBUTING.md](CONTRIBUTING.md) を読んでください。参加する方には [行動規範](CODE_OF_CONDUCT.md) を守っていただくようお願いしています
- 要件・設計・技術的な決定の記録は [docs/](docs/) にまとめています

このアプリのコードの多くは、メンテナのレビューのもとで AI（Claude）が書いています。

## ライセンス

[Apache License 2.0](LICENSE) で公開しています。

アプリには、形態素解析器 [Kuromoji](https://github.com/atilika/kuromoji) とその辞書（IPADIC）をはじめ、第三者のオープンソースソフトウェアが含まれます。それぞれのライセンスと注意書きは [NOTICE](NOTICE) およびアプリ内の「ライセンス」画面で確認できます。
