# Piu Search

[![CI](https://github.com/unils/piu-search/actions/workflows/ci.yml/badge.svg)](https://github.com/unils/piu-search/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Piu Search（日本語の端末では「Piu アプリ検索」）は、端末に入っているアプリを日本語で探すための Android アプリです。

「のりかえ」「乗り換え」「norikae」のどれで打っても乗換案内のアプリが見つかるように、ひらがな・カタカナ・漢字・ローマ字の違いや送り仮名のゆれを吸収します。ホーム画面アプリ（ランチャー）ではないので、今使っているホーム画面はそのままで使えます。

## 特徴

- **読みで探せる** — 漢字の名前のアプリも、読みのひらがなやローマ字で見つかります。入力の途中（「のりk」）でも候補が出ます
- **英語の名前もカタカナの読みで** — 「やふー」で Yahoo! のアプリが、「ぐーぐる」で Google のアプリが出ます
- **ジャンルで探せる** — 「ちず」と打つと、Google マップと Yahoo!マップの両方が出ます
- **提供元で探せる** — 「楽天」と打つと、名前に「楽天」が入っていない楽天グループのアプリも出ます
- **使うほど賢くなる** — 別の言葉で探して開いたアプリは、次からその言葉でも出るようになります。覚えた内容は設定画面で確認・削除できます
- **すぐ呼び出せる** — アシスタントとして登録すれば、電源ボタンの長押しや画面の隅からのスワイプで開けます（端末によって操作は異なります）。クイック設定のタイルからも開けます
- **仕事用プロファイルに対応**
- **通信しません** — インターネットの権限を持たず、検索も学習もすべて端末の中で行います

## 動作環境

- Android 8.0 以上
- Google Play 開発者サービスは不要です（GrapheneOS など、Google のサービスを入れていない端末でも動きます）

## インストール

現在、配布の準備中です。準備ができしだい、ここに入手方法を載せます。

それまでは、下の「ビルド」の手順で APK を作って入れてください。

## 使い方

初めて開くと、呼び出し方の設定を案内します。あとから設定画面でも変えられます。

- **アシスタントとして使う**: 端末の設定で「デジタル アシスタント アプリ」に Piu アプリ検索を選びます
- **タイルから使う**: クイック設定の編集画面で、タイル「アプリ検索」を追加します

## プライバシー

Piu Search は情報を集めず、どこにも送りません。検索のための索引と学習の内容は端末の中にだけ保存し、Google ドライブへのバックアップの対象からも外しています。詳しくは [PRIVACY.md](PRIVACY.md) をご覧ください。

## ビルド

Java 17 以上（JRE で構いません）と Android SDK が必要です。コンパイルに使う JDK 21 は、Gradle が自動で取得します。

```bash
git clone https://github.com/unils/piu-search.git
cd piu-search
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

APK は `app/build/outputs/apk/debug/` にできます。開発環境の詳しい作り方とテストの実行方法は [CONTRIBUTING.md](CONTRIBUTING.md) にあります。

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
| `config/detekt/` | 静的解析（detekt）の設定 |
| `docs/` | 要件・設計・調査・決定の記録 |
| `fastlane/metadata/` | ストア（F-Droid・IzzyOnDroid）向けの説明文 |

`core/` の 3 つは Android に依存しない Kotlin のモジュールで、JVM の上だけでテストできます。`release-test/`・`benchmark/`・`test-apps/`・`tools/` の中身は、配布するアプリには含まれません。

## 開発に参加する

不具合の報告や要望は [Issues](https://github.com/unils/piu-search/issues) へどうぞ。探したアプリが見つからなかったときは、専用の報告のテンプレートがあります。コードの変更を送る前に [CONTRIBUTING.md](CONTRIBUTING.md) を読んでください。参加する方には[行動規範](CODE_OF_CONDUCT.md)を守っていただくようお願いしています。脆弱性の報告は [SECURITY.md](SECURITY.md) の手順でお願いします。設計の考え方は [docs/](docs/) にまとめています。

このアプリのコードの多くは、メンテナのレビューのもとで AI（Claude）が書いています。

## ライセンス

[Apache License 2.0](LICENSE) で公開しています。

アプリには、形態素解析器 [Kuromoji](https://github.com/atilika/kuromoji) とその辞書（IPADIC）をはじめ、第三者のソフトウェアが含まれます。それぞれのライセンスは [NOTICE](NOTICE) とアプリ内の「ライセンス」画面で確認できます。
