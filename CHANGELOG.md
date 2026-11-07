# 変更履歴

このプロジェクトの目立つ変更を記録する。形は [Keep a Changelog 1.1.0](https://keepachangelog.com/ja/1.1.0/) に従い、版の番号は [Semantic Versioning 2.0.0](https://semver.org/lang/ja/) に従う。変更の種類の見出し（Added・Changed・Fixed など）は、道具で読めるよう Keep a Changelog の英語のままにする。

配布の経路（F-Droid・IzzyOnDroid）に出す版ごとの説明は、別に `fastlane/metadata/android/ja-JP/changelogs/<versionCode>.txt` に置く。

## [Unreleased]

### Added

- 入っているアプリを、読み（ひらがな・カタカナ・漢字・ローマ字）と送り仮名の違いを吸収して探す
- 英字のブランド名を読みで探す（「やふー」で Yahoo! のアプリ）
- ジャンル（地図・メールなど）と提供元で探す
- 検索した語と開いたアプリの組を覚え、次からその語でも出す。覚えた語の一覧・消去・ファイルへの書き出しと読み込み
- 「すべてのアプリ」の一覧
- 検索窓の位置（下か上）の設定
- 仕事用プロファイルのアプリの検索
- 呼び出しの入口: ホームのアイコン・デジタルアシスタント・クイック設定タイル

[Unreleased]: https://github.com/unils/piu-search/commits/main
