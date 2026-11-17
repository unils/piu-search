# Piu Search

[![CI](https://github.com/unils/piu-search/actions/workflows/ci.yml/badge.svg)](https://github.com/unils/piu-search/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Piu Search（日本語の端末での表示名は「Piu アプリ検索」）は、端末に入っているアプリを日本語の読み・表記ゆれ・ジャンル・提供元で引き出す Android 向けの検索アプリです。

ホーム画面を置き換えるランチャーではなく、今使っているホーム画面のまま呼び出して使えます。「のりかえ」「乗り換え」「norikae」のどれで入力しても乗換案内のアプリが当たるように、ひらがな・カタカナ・漢字・ローマ字の違いや送り仮名のゆれを吸収します。

## 何ができるか

### 読み・ジャンル・提供元で探す

- **読みとローマ字** — 漢字の名前のアプリも、ひらがなやローマ字の読みで探せます。ローマ字を打ちかけている途中（「のりk」など）でも候補が出ます
- **英語のブランド名の読み** — 「やふー」で Yahoo! のアプリ、「ぐーぐる」で Google のアプリのように、英語名のアプリも日本語の読みで引けます
- **ジャンル** — 「ちず」と打つと、Google マップや Yahoo!マップなど地図のアプリがまとめて並びます
- **提供元** — 「楽天」と打つと、アプリの名前に「楽天」が入っていない楽天グループのアプリも探せます

### 使い方に合わせて覚える

- 検索で見つからずに別の言葉で探して開いたアプリや、「すべてのアプリ」の一覧から選んで開いたアプリは、次から最初の言葉でも出るようになります（救済）
- よく選ぶアプリほど上の位置に並びます
- 覚えた対応関係は、設定画面から一覧で確認し、1 件ずつの削除・全消去・ファイルへの書き出し・読み込みができます

### すぐ呼び出して片手で使う

初回起動時に呼び出し方の設定を案内します。あとから設定画面の「呼び出しの設定」でもいつでも開けます。

- **デジタルアシスタントから開く** — 端末の設定で「デジタル アシスタント アプリ」に Piu アプリ検索を選ぶと、電源ボタンの長押しや画面の隅からのスワイプでどの画面からでも呼び出せます（操作方法は端末によって異なります）
- **クイック設定のタイルから開く** — クイック設定の編集画面でタイル「アプリ検索」を追加すると、通知パネルから開けます（Android 13 以上では、初回の案内画面からタイルの追加を直接依頼できます）
- **仕事用プロファイル** — 個人用と仕事用のアプリをタブで切り替えるか、1 つの一覧に混ぜて検索できます

## プライバシーと権限

Piu Search は `INTERNET` の権限を持たず、外部と一切通信しません。検索のための索引・学習・起動の記録・設定はすべて端末の中にだけ保存し、クラウド（Google ドライブ）への自動バックアップの対象から外しています。学習と起動の記録は設定画面からいつでも消去できます。

保存するデータの内訳、端末を移行するときの扱い、マニフェストに宣言している権限の詳細は [PRIVACY.md](PRIVACY.md) をご覧ください。

## 導入と検証

### 動作環境

- Android 9（API 28）以上
- Google Play 開発者サービスは不要です（GrapheneOS など、Google のサービスを入れていない端末でもそのまま動きます）

### インストールと署名の確認

[F-Droid](https://f-droid.org/) の公式リポジトリへの登録を申請する予定です。それまでは、[GitHub Releases](https://github.com/unils/piu-search/releases) から APK（`piu-search-<版>.apk`）を取得してインストールしてください。F-Droid と GitHub Releases の両方でメンテナの鍵で署名した同じ APK を配布するため、どちらから入れた場合もそのまま上書きして更新できます。

インストールする前に、`apksigner verify --print-certs piu-search-<版>.apk` を実行し、出力される `certificate SHA-256 digest` が次の指紋と一致することを確かめられます。

```
43b28f21f64eb56ccf2e690b8744ce1b13da23b440d7fc05eadd6c660736fb07
```

公開しているソースコードから配布物と同じ APK が組み立てられること（再現可能なビルド）の検証手順は、[docs/release.md](docs/release.md) にまとめています。

## 報告と開発への参加

- **不具合の報告・要望**: [Issues](https://github.com/unils/piu-search/issues) からお寄せください。探したアプリが検索で出なかった場合は、専用のテンプレートから報告できます
- **脆弱性の報告**: 公開の issue には書かず、[SECURITY.md](SECURITY.md) の手順に沿って非公開でお知らせください
- **コードや文書の提案**: 変更を送る前に [CONTRIBUTING.md](CONTRIBUTING.md) を読んでください。参加する方には [行動規範](CODE_OF_CONDUCT.md) を守っていただくようお願いしています
- **設計の記録**: 要件・設計・調査・技術的な決定の記録は [docs/](docs/) にあります

手元でデバッグ版の APK を組み立てるには、Java 17 以上の実行環境（JRE で構いません。コンパイルとテストに使う JDK 21 は Gradle が自動で取得します）と Android SDK を用意して次のコマンドを実行します。

```bash
git clone https://github.com/unils/piu-search.git
cd piu-search
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

APK は `app/build/outputs/apk/debug/` に出力されます。Android SDK の導入手順、検査やテストの実行方法、ディレクトリとモジュールの構成は [docs/developer-guide.md](docs/developer-guide.md) を参照してください。

このアプリのコードの多くは、メンテナのレビューのもとで AI（Claude）が書いています。

## ライセンス

[Apache License 2.0](LICENSE) で公開しています。

アプリには、形態素解析器 [Kuromoji](https://github.com/atilika/kuromoji) とその辞書（IPADIC）をはじめ、第三者のオープンソースソフトウェアが含まれます。それぞれのライセンスと注意書きは [NOTICE](NOTICE) およびアプリ内の「ライセンス」画面で確認できます。
