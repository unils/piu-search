# 既存アプリの調査

調査日: 2026-10-27。結論は「漢字の読みで引けるアプリは YomiLauncher がある（最初の調査では見落とした）。読み・ジャンル・学習・通信なしをすべて満たすものはない」。

## 候補の比較

ソースを clone して検索の処理を読んだ。コミットは調査時点の HEAD。

| アプリ | 形態 | ひらがな・カタカナ・ローマ字 | 漢字の読み | ジャンル | 学習 | 備考 |
|---|---|---|---|---|---|---|
| [YomiLauncher](https://github.com/go4325/YomiLauncher)（`49ba623`） | 検索アプリ。名前に反して HOME を宣言しない | 吸収する | **○ Kuromoji（IPADIC）で読みを付ける** | △ 32 アプリ分の別名（Google マップに「地図」「ちず」など） | ✕ 起動回数で並べるだけ | 下記 |
| [Material Search](https://github.com/nemy-new/Material-Search)（`a365703`） | 検索アプリ。HOME を宣言しない | 吸収する（かな↔ローマ字の変換表を自前で持つ） | ✕ | △ 手作りの辞書に一部 | ✕ 別名の手動登録のみ | 下記 |
| [Kvaesitso](https://github.com/MM2-0/Kvaesitso)（`c095ffb`） | ホームランチャー | 吸収する（ICU の Transliterator でラテン文字に変換） | ✕ 漢字は中国語のピンインになる | タグを手で付ける | 起動回数で順位付け | `data/i18n/.../IcuStringNormalizer.kt` |
| [KISS](https://github.com/Neamar/KISS)（`6601fa5`） | ホームランチャー | ✕ NFKC 相当の正規化のみ | ✕ | タグを手で付ける | △ 下記 | `searcher/QuerySearcher.java` |
| [TBLauncher](https://github.com/TBog/TBLauncher)（`f7fbfcc`、2024-05 で停止） | ホームランチャー（KISS の派生） | ✕ 全角半角の統一のみ | ✕ | タグ | KISS と同様 | |
| Lawnchair（`e53dcc5`）・Neo Launcher（`f03d864`）・Olauncher（`f8e8d64`）・μLauncher（`732a00f`）・EasyLauncher（`0a6a2ea`） | ホームランチャー | ✕ | ✕ | — | — | 変換・読みの処理なし（grep で確認） |
| Niagara・Smart Launcher・Nova（ソース非公開） | ホームランチャー | 公開情報では確認できず | — | Smart Launcher は自動でカテゴリ分け | — | 実機で未確認 |

### YomiLauncher の詳細

最初の調査では、GitHub の説明文が空だったため中身を見ずに外した。メンテナの指摘で 2026-10-27 に確認した。

- Google Play で公開されている（`com.yomilauncher`、開発者「菊井ノックス」、ダウンロード 10+、最終更新 2026-02-13、カテゴリは仕事効率化）。ストアの説明は「アプリ名を漢字ではなく『読みがな』で検索できるランチャーアプリ」。GitHub は 2 コミット、★0。
- マニフェスト: `LAUNCHER` とホーム画面のウィジェット。HOME・ASSIST・クイック設定タイルはない。権限は `QUERY_ALL_PACKAGES`。**AdMob の広告を載せている**ので、通信と広告の SDK が入る。
- 読み（`domain/KanaGenerator.kt`）: アプリ名を Kuromoji で形態素解析して読みを付ける。英単語の読みの辞書（`assets/dictionary.json` の readings、約 6.7 万語。`extract_readings.py` で日本語 WordNet の英語と日本語の対応から作ったとみられる）と、手書きの英単語表（「weather」→「てんき」など）も持つ。
- ジャンル: パッケージ名ごとの別名が 32 件（Google マップ→「地図」「ちず」「ナビ」など）。Yahoo!マップのような他社のアプリは対象外。アプリの説明文（`loadDescription`）も検索するが、説明を持つアプリは少ない。
- 照合（`data/AppIndexDao.kt`）: 読み・名前・説明への部分一致（SQL の `LIKE '%語%'`）で、起動回数の順に並べる。あいまい一致と学習はない。
- **ライセンスの表示がない**（README は「開発者にお問い合わせください」）。著作権法上、コードの流用や fork はできない。考え方の参考にとどめる。

### Material Search の詳細

- 説明は「日本語に特化したアプリ検索」。Apache-2.0、★27、2026-02-15 公開、最終 push は 2026-09-05。リリースは v1.1（2026-02）で、APK はリポジトリに直接置かれている。F-Droid 用のメタデータ（`com.nemynew.materialsearch.yml`）はあるが、F-Droid・IzzyOnDroid での配布は確認できなかった。
- マニフェスト: `LAUNCHER`（通常の起動）、`ASSIST`（デジタルアシスタント）、クイック設定タイル。**HOME はない**。権限は `QUERY_ALL_PACKAGES` と `READ_CONTACTS` で、`INTERNET` はない。
- 照合（`data/AppRepository.kt` の `searchApps`）: ユーザーの別名（完全一致 1000 点、前方一致 800 点）→ 名前の完全一致 → かな・ローマ字に変換しての一致 → 辞書 → 前方一致 → 部分一致、の順に点数を付ける。編集距離などのあいまい一致はない。
- 辞書（`data/ShortcutData.kt`、約 1,800 行）: 「らいん」「insta」「アマプラ」などの愛称と、「ちず」「map」→マップ、「のりかえ」→乗換案内のようなジャンル寄りの語。キーは入力語、値はアプリ名の候補の文字列で、パッケージ名ではない。
- 使い道: fork の土台、または辞書の流用。辞書のライセンスも Apache-2.0。

### KISS の学習

- 起動するたびに `history(query, record)` を保存する。検索時に、同じ語で始まる過去の検索で選んだアプリに最大 100 点を加える。
- ただし加点は、**名前がもともと一致した候補の中だけ**に効く。一致しないアプリを候補に出すことはない。つまり「ちず」と打って Yahoo!マップを開いた経験は、次に「ちず」で Yahoo!マップを出す役には立たない。

## 試した経路

| 経路 | 結果 |
|---|---|
| Web 検索（日本語・英語、extended を含む 8 回） | ランチャーのおすすめ記事と漢字辞典アプリばかり |
| Google Play の site 検索 | 一般的なアプリ検索アプリ（App Search: Find apps fast など）。日本語対応の記述なし |
| GitHub の API 検索（`launcher migemo`・`kuromoji`・`romaji`・`hiragana`・「アプリ検索」など 11 語） | Material Search を発見。`launcher yomi` で YomiLauncher も出ていたが、説明文が空で見落とした |
| OSS のランチャー 9 個を clone して grep（`Transliterat`・`romaji`・`hiragana`・`kuromoji`・`category`・`history` など） | 上の表 |

## 未確認

- ソース非公開のランチャー（Niagara・Smart Launcher・Nova・Microsoft Launcher）の日本語の扱い。実機で試せば確かめられる
- 日本の IME（Gboard・Simeji・ATOK）のアプリ検索機能の有無
