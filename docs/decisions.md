# 決定と未決

2026-10-27 から、開発の多くを AI（Claude）が行っている。方針はメンテナが決める。「決定」のうち「（AI）」と書いたものは、技術的な判断として AI が決めた。メンテナが覆してよい。

「M1-06b」「M6」などは、開発の段階（マイルストーン）とタスクの番号。M6 は実機の試験、M7 は公開の準備。

## 決定

| 日付 | 決定 | 理由 | 詳細 |
|---|---|---|---|
| 2026-11-14 | その検索語で選んだか救済した学習のあるアプリは、段階をまたいで上に並べる。上げる段数は「回数 − 1 ＋ 救済なら 1 − 30 日使わなければ 1」で、元の段階の差に届けば逆転する。効くのは記録の語と同じ語と、英字で最後の 1 音を打ちかけた形だけ。印（R4）が先に立つ | メンテナの判断（学習した語なら段階をまたいでよい。無条件に最上段ではなく、段階の差と学習の強さの兼ね合いで）。段数の式と効く範囲は AI が決めた。「めーる」でメールのアプリを 3 回選べばメルカリと入れ替わる。評価セットの学習なしの順位は全行変わらない（#14） | [learning.md](design/learning.md#規則)・[matching.md](design/matching.md#照合の段階と順位) |
| 2026-11-14 | 読みの補いを漢字の検索語側にも使い、辞書の読みを先に残して 8 通りまで組み合わせる（AI） | かなだけの名前による追加評価 7 行で 3 位以内が 2/7 → 7/7。既存評価の順位を保った（T5、#19） | [testing.md](design/testing.md#照合の細部の回帰テストt5) |
| 2026-11-14 | ブラウザの問い合わせに `MATCH_ALL`、カメラにパッケージ指定を使う（AI） | 既定以外のブラウザと後から入れたカメラをジャンルから落とさない（T7、#12） | [matching.md](design/matching.md#intent-filter-とカテゴリによるジャンル) |
| 2026-11-14 | AOSP が合成する `android.app.AppDetailsActivity` の入口を索引に入れない（AI） | アプリを起動する入口ではなく設定へ転送するため。クラス名の完全一致で除く（T7、#24） | [architecture.md](design/architecture.md#何を読み直すか) |
| 2026-11-14 | 索引の版変更でも入口とキーを残し、状態と APK 指紋を無効にして読めるものから作り直す（AI） | 一時停止中の仕事用を消さず、再開後の作り直しを再起動をまたいで保証する（T7） | [architecture.md](design/architecture.md#読めなかったとき) |
| 2026-11-14 | 学習と起動の記録は、読み込みの確認・変更の計算・保存・写しの反映・知らせと取り消し材料の更新を 1 つの仕事にし、成功・失敗を返す（AI） | 保存失敗で表示だけが進むのを防ぐ。保存処理は当面 1 本の Executor を使う（R1） | [architecture.md](design/architecture.md#保存の順序と書き手) |
| 2026-11-14 | 読み込み状態を `NotLoaded`・`Loading`・`Ready`・`Failed` で持ち、読み直しにも失敗したら書き込まない。最後に読めた写しは保つ（AI） | 空の学習で保存済みの記録を上書きしない（R1） | [architecture.md](design/architecture.md#保存の順序と書き手) |
| 2026-11-14 | 索引・学習・起動の記録は共通の `DatabaseWriter` に並べ、プロファイルの削除は 1 つのトランザクションで行う。重い索引の計算は書き手の外に置く（AI） | 削除と遅い保存の順序をそろえ、起動前の記録を待たせない（R3） | [architecture.md](design/architecture.md#同期の実行と保存) |
| 2026-11-14 | プロファイルの削除は UserManager で存在しないと確認できたときだけ。起動時にも索引・学習・起動の記録の番号の和集合と照合する（AI） | 一時停止や問い合わせ失敗で消さず、プロセス停止中の削除も拾う（R3） | [architecture.md](design/architecture.md#プロファイル) |
| 2026-11-14 | 個人用の変更番号・仕事用の base/split APK 指紋・Callback・毎回のジャンル判定を併用する。OS・言語・索引の版の変更で全件を作り直す（AI） | 変更番号や APK 指紋だけでは拾えない変化がある（R4） | [architecture.md](design/architecture.md#何を読み直すか) |
| 2026-11-14 | 同期の通知は成功した対象だけを消費し、失敗した材料は指紋なしで再試行する。同期は 1 つずつ実行し、追加要求をまとめる（AI） | 読み取り失敗を正常な空にせず、新しい通知も失わない（R4） | [architecture.md](design/architecture.md#読めなかったとき) |
| 2026-11-14 | Callback・プロファイルの放送・忘却のきっかけは Application の部品が持つ。背景のパッケージ通知は溜め、アイコンも失効させる（AI） | 画面を閉じている間の変更も次の検索へ反映する（R4） | [architecture.md](design/architecture.md#更新のきっかけ) |
| 2026-11-14 | 検索の状態とセッションは ViewModel、現在の検索語は SavedStateHandle に置く。救済の知らせと取り消し材料はプロセス内、学習一覧の結果の表示は専用の ViewModel が持つ（AI） | 回転で前の試行と操作結果を失わず、画面終了後の完了も受け取る（R5） | [architecture.md](design/architecture.md#状態の持ち主) |
| 2026-11-14 | アプリを開く前に選択の学習と起動の記録を保存し、両方の成功と OS の受付後にだけ救済を Toast で知らせる。起動失敗でも保存済みの記録は残す（AI） | 開いた直後に保存が落ちるのを防ぎ、後の記録や消去を巻き戻さない（R2） | [learning.md](design/learning.md#アプリを開くときの保存r22026-11-14) |
| 2026-11-14 | 一時停止中の仕事用アプリへの要求は、起動回数と学習（選択・救済・使用）に記録しない（AI） | OS の再開確認を取り消したか判別できない（R2、#26） | [learning.md](design/learning.md#アプリを開くときの保存r22026-11-14) |
| 2026-11-14 | 起動中と救済の知らせの保留は別の印にする。旧画面が破棄済みでも完了時に起動中の印を解除し、セッションの再開は表示中か復帰時に限る（AI） | 回転後の再操作と次回の取り消しを保ち、非表示中に入力を記録しない（R5） | [ui.md](design/ui.md#回転とセッション) |
| 2026-11-14 | 派生の索引は材料が変わったときだけ裏で作り、起動記録だけの変更では順位の信号を替える。漢字の読みは解析前にも古さを確かめる（AI） | 画面の仕事と、古い要求による無駄な解析を減らす（R5） | [architecture.md](design/architecture.md#画面の状態と派生の索引r5) |
| 2026-11-14 | 読み込み・保存・同期の失敗を学習一覧や検索画面で知らせ、ログには操作と例外の型だけを残す。起動前の保存失敗は現在ログのみ（AI） | 失敗を把握できるようにし、アプリの識別子や URI をログへ出さない（R1・R4、#33） | [architecture.md](design/architecture.md#保存の順序と書き手) |
| 2026-11-14 | 解析器の差し替えの境界（#45）は、今の結合を許す（AI） | interface と Kuromoji の同居、組み立てと辞書の準備の具体型への依存を、現状の境界として明記する | [architecture.md](design/architecture.md#モジュール) |
| 2026-11-14 | 端末間の転送の整理で残す記録を、主ユーザー（番号 0）から、このアプリが動いているユーザーの番号に改める。仕事用の一時停止中に言語・ビルドが変わっても、前の値を残さない（AI） | 公開前の見直し。別のユーザーに入れたとき自分の学習を消さないため。読めなかった入口は指紋なしで保存され再開後に作り直されるので、前の値を残すと読めたプロファイルを毎回作り直すだけになる | [architecture.md](design/architecture.md#データの保存) |
| 2026-11-14 | 同じ組への学習の保存が成功したら、古い救済の取り消し材料と知らせを無効にする。保存失敗や別の組の変更では保つ（AI） | 古い取り消しで新しい学習を消さない（R1 のレビュー修正） | [learning.md](design/learning.md#規則) |
| 2026-11-14 | 旧版の長すぎる学習は初回の読み込みで DB からも削除する。全消去はメモリの差分ではなく、学習のテーブル全行を削除する | メンテナの方針（T3 のレビュー修正）。メモリから除外した行が、一覧にも現れず消去もできないまま DB に残ることを防ぐ | [learning.md](design/learning.md#細則m4-012026-10-29) |
| 2026-11-13 | 学習の語は正規化後 64 コードポイントまでとし、長すぎる語は記録・読み込みしない。読み込み先が一意に決まらない仕事用の記録も捨て、受け入れた件数と捨てた件数を知らせる | メンテナの判断（T3）。長い語による画面の停止と、別の端末のプロファイル番号の流用による誤った結び付けを防ぐ | [learning.md](design/learning.md#保存と持ち出し) |
| 2026-11-13 | 学習の JSON は解析前に反復で入れ子を検査し、8 段を超えたら壊れたファイルとして何も変えずに拒む。書き出しにないコメント・単一引用符も拒む（AI） | T3。通常の書き出しの 3 段に余裕を持たせつつ、再帰する解析器のスタックが尽きる前に止める。字句の解釈の差で検査をすり抜けさせない | [SECURITY.md](../SECURITY.md#学習のファイルと記録の上限) |
| 2026-11-13 | 学習のキーで当たったアプリを開いたら、前方一致やローマ字でも元の記録の時刻を進める。元の語と入力が違う場合、回数は入力した語の選択だけに足す。回数は `Int.MAX_VALUE` で飽和させる（AI） | T3。R1 の選択と R5 の使用を分け、毎日使う救済語が 180 日で消えないようにする。同じ組を二重に数えず、読み込める回数の上限でもあふれないようにする | [learning.md](design/learning.md#規則) |
| 2026-11-13 | インストールの印が空・破損・読み取り不能なら作り直す。保存できない場合も新しい乱数で索引と仕事用の記録を整理して DB を開く | 印の初回作成の中断で、再起動しても検索を使えなくなることを防ぐ。印が信頼できない間は、移行の可能性を捨てずに毎回整理する | [architecture.md](design/architecture.md#データの保存) |
| 2026-11-13 | Android 8（API 26・27）のサポートを終了し、最小 SDK を 28（Android 9）にする | メンテナの判断。利用者が少なく、バックアップの規則など古い版のための分岐が誤りのもとになった | [architecture.md](design/architecture.md#対応する端末) |
| 2026-11-13 | 端末間の転送は DB と設定を対象とし、主ユーザー（シリアル番号 0）の学習・起動の記録を残す。索引は必ず作り直し、仕事用などの記録は捨てる（AI） | メンテナの方針では移行の維持は必須ではないが、Room のオープン時に一度整理すれば少ない変更で整合性を保てる。転送されないインストールの乱数と DB の印を比較し、整理と印の更新を一括して確定する。旧版に印がない場合も同じ整理を行い、通常の更新であっても仕事用の記録を捨てる | [architecture.md](design/architecture.md#データの保存) |
| 2026-11-13 | 索引・学習・起動の記録・設定のすべてをクラウドへのバックアップから外す。起動の記録の保存内容・用途・削除方法を明記し、設定に削除の操作を置く | クラウドに送らない方針を維持する（メンテナの判断）。起動の記録も利用者が把握し、消せるようにする | [PRIVACY.md](../PRIVACY.md) |
| 2026-10-27 | ホーム画面を置き換えない検索アプリにする | 検索のためにホームアプリを替えずに済むようにする。Pixel などでは、標準でないホームアプリを使うとジェスチャーと履歴画面の不都合が出る | [requirements.md](requirements.md) U5 |
| 2026-10-27 | 実行時に Play ストアの情報を取得しない | 壊れやすく規約上グレーで、アプリの一覧が外に出る | [matching.md](design/matching.md#5-play-ストアの情報見送りの記録) |
| 2026-10-27（暫定） | 照合は「形態素解析による読み ＋ OS の情報（intent filter など）＋ 暗黙の学習 ＋ 愛称の辞書」で始める | 手作りの辞書に頼らず、利用者の負担を下げる | [matching.md](design/matching.md) |
| 2026-10-27 | 一般に配布する前提で、品質を上げて作る | メンテナの意向 | |
| 2026-10-27 | YomiLauncher を基準にし、広告なし・ブランド名の読み・下の検索窓・ジャンル検索・学習（検索語とアプリの結びつき）を足す | メンテナが試して「ほぼ不満がない」と評価。残った不満が T1〜T4 | [requirements.md](requirements.md#yomilauncher-を試した結果2026-10-27) |
| 2026-10-27 | ジャンル検索は後回しにしない | 一度後回しにしたが、メンテナが「まだ早い」と撤回 | |
| 2026-10-27 | 検索語の言い換えの学習は当面入れない（候補） | チューニングが難しそう | [learning.md](design/learning.md#検索語の言い換えの学習候補当面は実装しない) |
| 2026-10-27 | YomiLauncher の作者には相談しない | メンテナの判断 | |
| 2026-10-27 | 開発は AI に任せる。管理は Opus 5.5（エフォート超高）、実装は Opus 5.5（エフォート中）。メンテナは十分にできあがった段階で実機でテストする | メンテナの指示 | |
| 2026-10-27 | 最終的に OSS として公開する。公開の前に履歴を作り直す。OSS として通用する設計・品質（自動テストを含む）にする | メンテナの指示 | M7 |
| 2026-10-27 | 土台はゼロから作る（AI） | Material Search は漢字の読みと学習を持たず、作り直す部分が大半。YomiLauncher はライセンスがなく流用できない | |
| 2026-10-27 | 形態素解析器は Kuromoji 0.9.0 ＋ IPADIC（AI） | 純 Java で Android での実績がある。13 MB。手元の試しで読みの質が十分。照合は interface を使う。モジュールと辞書の準備の結合は 2026-11-14 の決定を参照 | [reading-sources.md](research/reading-sources.md) |
| 2026-10-27 | 英字のブランド名は、自前の読みの表と頭字語の規則で読む（AI） | Mozc の辞書にも英字のブランド名の読みはない | [matching.md](design/matching.md#英字のブランド名の読み) |
| 2026-10-27 | ~~最小 SDK 26~~（2026-11-13 に 28 に変更）、target SDK 37（AI） | カテゴリと差分の検出の API が 26 から。AndroidX が 23 未満を切っている | [architecture.md](design/architecture.md#対応する端末) |
| 2026-10-27 | `INTERNET` と `QUERY_ALL_PACKAGES` を持たない（AI。後者は M2 で確認） | A1。`<queries>` で足りる見込み | [architecture.md](design/architecture.md#権限とマニフェスト) |
| 2026-10-27 | v1 の呼び出しは、アイコン・デジタルアシスタント・クイック設定タイル。ウィジェットは後（AI） | アシスタントの条件は `ACTION_ASSIST` の Activity だけで満たせる | [android-platform.md](research/android-platform.md) |
| 2026-10-27 | 仕事用プロファイルのアプリも対象にする。プライベートスペースは対象外（AI） | 前者は `LauncherApps` で許される。後者は既定のホームアプリにしか許されない | [android-platform.md](research/android-platform.md#仕事用プロファイルとプライベートスペース) |
| 2026-10-27 | アプリの静的ショートカットは v1 で使わない（訂正）（AI） | `LauncherApps.getShortcuts` は既定のホームアプリにしか許されない。以前の設計は誤り | [android-platform.md](research/android-platform.md#静的ショートカットは取れない) |
| 2026-10-27 | 照合と学習は Android に依存しない Kotlin のモジュールに置く（AI） | エミュレータなしで速くテストできる | [architecture.md](design/architecture.md#モジュール) |
| 2026-10-27 | 照合の質は評価セット（検索語と正解のアプリの組）で測り、変更のたびに下がっていないことを確かめる（AI） | AI が実装するときの客観的な物差し | [testing.md](design/testing.md#評価セット) |
| 2026-10-27 | 学習は Google ドライブへの自動バックアップから外し、手動の書き出しを用意する（AI） | 利用者の情報を Google に集めない（メンテナの方針） | [architecture.md](design/architecture.md#データの保存) |
| 2026-10-27 | ~~ライセンスは GPL-3.0-or-later~~（2026-11-07 に Apache-2.0 に改めた） | メンテナの判断。広告つきの閉じた改変版を防げる | |
| 2026-10-27 | AI の管理役は、レビューを通したタスクを main にコミットし、push してよい。実装役は自分の作業ブランチにだけコミットする | メンテナの許可 | |
| 2026-10-27 | 「すべてのアプリ」のボタンを検索窓の横に置き、名前の順（英字は A〜Z、かな・漢字は読みの五十音順）の全アプリの一覧を出す。検索語を残したまま開いたアプリを学習する | メンテナの提案。学習の対応付けには全アプリから選べる必要がある | [ui.md](design/ui.md#すべてのアプリ) |
| 2026-10-27 | 仕事用プロファイルは「個人用｜仕事用」で切り替えて検索する。既定は個人用 | メンテナの希望 | [ui.md](design/ui.md#仕事用プロファイル) |
| 2026-10-27 | 略語の 1 字ずつの読み（NHK → えぬえいちけー）は、評価セットで効果を見て残すか決める（AI） | メンテナの指摘（大文字は打たない）。大文字はアプリ名の側の話で、入力は大文字と小文字を区別しない。かなで略語を打つ人のための規則で、効果は小さい見込み | [matching.md](design/matching.md#英字のブランド名の読み) |
| 2026-10-27 | JDK は Gradle の toolchain で JDK 21 を取得して使う。出力するバイトコードは 17。JVM のモジュールのテストは JUnit 6（AI） | PC に JDK がなくてもビルドでき、開発者と CI で同じ版になる。JUnit 6 は Jupiter の API のまま最新の系列 | [CONTRIBUTING.md](../CONTRIBUTING.md#開発環境を作る)・[testing.md](design/testing.md#テストの道具) |
| 2026-10-29 | 名前は「Piu Search」。applicationId と Kotlin のパッケージ名は `jp.piu.search`（メンテナが piu.jp を持っている）。画面の名前は後から変えられるが、applicationId は配布の後は変えられない | メンテナの判断。打ちやすさのため、イタリア語の「più」ではなく「Piu」 | 2026-11-02 に M5-04 で差し替えた |
| 2026-10-29 | アプリ内のライセンスの画面は、Licensee の一覧を APK に同梱して描く（AboutLibraries・Google の oss-licenses は使わない）（AI） | 依存を足さず、検査に使った一覧そのものを出せる。Google のものは Play Services に依存する | [testing.md](design/testing.md#静的な検査) |
| 2026-10-29 | ソースの URL は仮（`app/build.gradle.kts` の `SOURCE_URL`）。M7 で差し替える | 公開先が未定 | 2026-11-07 に M7-01 で `https://github.com/unils/piu-search` に差し替えた |
| 2026-11-07 | ライセンスは Apache-2.0 | メンテナの判断（緩くしたい）。APK に入る依存 105 個はすべて Apache-2.0、IPADIC は無保証の条項を添えれば自由に再配布できるので、妨げはない。閉じた改変版は止められなくなる | M7-01 |
| 2026-11-07 | 公開は新しいリポジトリ `unils/piu-search` に、履歴を 1 から作って行う。開発の記録（作業の記録・タスクの計画・AI の設定）と個人の記録は入れない。説明は日本語だけ | メンテナの判断 | M7 |
| 2026-11-07 | コードの書き方の決まりを、公開する CONTRIBUTING に整理して置く（AI） | 書くのが AI でも、決まりが文書にあることで出力がそろい、外の人がレビューと提案をできる | [CONTRIBUTING.md](../CONTRIBUTING.md#コードの書き方) |
| 2026-11-07 | Gradle のモジュールの名前を、Gradle の勧め（小文字とハイフン）に合わせる: `releasetest` → `release-test`、`testapps` → `test-apps`（`nolauncher` → `no-launcher`、`twolaunchers` → `two-launchers`）。テスト用のアプリのパッケージ名はハイフンを除いて元のまま。`benchmark`・`tools/dict`・`config/detekt` は変えない（AI） | Gradle の文書（Multi-Project Builds の命名の勧め）。`benchmark` は Android Studio の Macrobenchmark のテンプレートの既定の名前で、公開のアプリでもいちばん多い。`config/detekt/detekt.yml` は detekt の Gradle プラグインが既定で読む場所。`tools/` は Now in Android などでも開発の道具の置き場 | [CONTRIBUTING.md](../CONTRIBUTING.md#書式) |
| 2026-11-07 | 公開のリポジトリに、CHANGELOG（Keep a Changelog）・ストアの説明（`fastlane/metadata/android/ja-JP/`）・issue と pull request の様式・AGENTS.md・行動規範（Contributor Covenant 3.0 の公式の日本語訳）・SECURITY.md を置く。CODEOWNERS は置かない（AI） | F-Droid と IzzyOnDroid は fastlane の形から説明を読む。CODEOWNERS はメンテナが 1 人だと働きがなく、コードオーナーの承認を必須にすると、メンテナが自分の pull request を承認できず merge が止まる | [CHANGELOG.md](../CHANGELOG.md)・[SECURITY.md](../SECURITY.md) |
| 2026-11-08 | 起動用のプロファイル（startup profile）を生成し、R8 の DEX の並べ替え（`dexLayoutOptimization`）を明示して有効にする。起動用に集めるのは、呼び出してから検索窓に焦点が来るまでの流れだけにする。`startup-prof.txt` も `baseline-prof.txt` と同じくリポジトリに入れる（AI） | 公式の手順と Now in Android に合わせる（生成のたびに出ていた、起動用の規則がないという警告も消える）。並べ替えは AGP 9.4 の既定で有効だが、明示して既定の変更で APK が黙って変わらないようにする。起動の流れを `classes.dex` に収めるため、検索語を打った後の流れは入れない。エミュレータでは前後の差が見えなかった | [testing.md](design/testing.md#起動用のプロファイルと-dex-の並べ替え) |
| 2026-11-08 | Baseline Profile の生成と起動の計測は、公式の Baseline Profile Gradle プラグイン（`androidx.baselineprofile`）の手順に従う。プラグインが作る `benchmarkRelease`（R8 を通す）と `nonMinifiedRelease`（R8 を通さない）を使い、独自の build type と写しのタスクをやめる。生成は `:app:generateBaselineProfile`、出力は `app/src/release/generated/baselineProfiles/`。`automaticGenerationDuringBuild` は切る。`:release-test` の相手も `benchmarkRelease` にする（AI） | メンテナの方針（標準から外れていて、ほかの開発者や経緯を知らない AI が困るなら直す）。`benchmarkRelease` は release と同じく R8 を通し、違いは debug の鍵での署名と profileable なことだけで、独自の `minified` と同じ役に立つ | CONTRIBUTING の「起動の速さと Baseline Profile」・[testing.md](design/testing.md#起動の速さm5-03) |
| 2026-11-06 | 署名の証明書の組織名を、提供元のキーとして索引に入れる。提供元を表さない既知の DN は一覧で外し、数では外さない（AI） | iSPEED FX はパッケージ名に会社の名前がなく、証明書に Rakuten Securities がある。1 社で多くのアプリを持つ会社と、無関係な会社に共通する値は数で見分けられない | [matching.md](design/matching.md)・[research/package-names.md](research/package-names.md) |
| 2026-11-06 | 索引の 3 個以上のアプリが共通して持つパッケージ名の語（会社の名前）は、照合から外さず、名前で当たるアプリの後のいちばん下の段に並べる（2026-10-28 の M1-06b の規則を改める）。パッケージ名の語にもブランド表の読みを付ける | メンテナの判断。実機の試験（M6）で「楽天」「rakuten」で iSPEED が、「やふー」で Y!乗換案内が出なかった。提供元（会社の名前）で引きたい | M6-01 |
| 2026-11-04 | M6 の署名鍵は、リポジトリの外にパスワードのファイルと一緒に置き、メンテナが控える。ビルドの設定に鍵を入れず、組み立てた後に `apksigner` で署名する | メンテナの判断（AI の推奨）。鍵をなくすと上書きで更新できない | |
| 2026-11-04 | M6 の APK は、メンテナが自分で端末に入れる（`adb install`） | メンテナの判断 | |
| 2026-11-04 | M6 の実機の試験は、素の Android の端末から始める。GrapheneOS の項目は端末が決まるまで保留 | メンテナの判断 | |
| 2026-11-03 | R8 を通した版の確かめは、app の `minified`（R8 を通し debug の鍵で署名した build type。2026-11-08 に `benchmarkRelease` に替えた）を、別のモジュール `:release-test` がアプリの外から（UiAutomator と shell で）試す形にする（AI） | 既存の計器テストを R8 の側で回すと、テストのための keep の規則が要り、配布物と中身がずれる。名前が変わると壊れる部品を使う操作を外から試せば、M2-04 の誤りを拾える | [testing.md](design/testing.md#release-の-apk-の確認) |
| 2026-11-03 | 起動の速さは Macrobenchmark（`:benchmark`）で測り、指標は最初の描画と、検索窓に焦点が来るまで（`reportFullyDrawn`）の 2 つにする。`check` には入れない（AI） | コンパイルの状態と冷えた・温まった状態を正しく扱え、Baseline Profile の生成と同じモジュールで済む。値がエミュレータに頼り、遅い | [testing.md](design/testing.md#起動の速さm5-03) |
| 2026-11-03 | Baseline Profile は、生成したもの全体（Compose などの分を含む）を ~~`app/src/main/baselineProfiles/`~~（2026-11-08 に `app/src/release/generated/baselineProfiles/` に移した）に置いてリポジトリに入れ、ビルドのたびには生成しない（AI） | 生成はエミュレータに頼り、再現可能なビルドにならない。全体を入れるのは公式の既定に合わせるため（生成し直すたびに 2 MB 近い差分が出る） | CONTRIBUTING の「起動の速さと Baseline Profile」 |
| 2026-11-03 | 計器テストの前後に検査を掛ける（前: 応答なしの窓、後: 端末ごとの件数がソースの `@Test` の数に届くか）（AI） | 本体の APK が入らずに 0 件のまま成功になることがあった（2026-11-02）。応答なしの窓が残ると、原因のわかりにくい時間切れで落ちる | [testing.md](design/testing.md#テストの層) |
| 2026-11-03 | 日本語の端末での表示名は「Piu アプリ検索」。英語は「Piu Search」。クイック設定のタイルの名前は「アプリ検索」のまま | メンテナの判断 | `values-ja/strings.xml` の `app_name`。M5-03 のブランチで差し替える |
| 2026-11-02 | 型名・ログのタグ・スレッドの名前は「Piu」を頭に付ける（`PiuTheme`・`PiuRoomDatabase`・`PiuIndexSync` など）。データベースのファイルは `piu.db`、自前の Gradle プラグインの id は `piu.*`、学習の書き出しの `format` は `jp.piu.search.learning`（AI） | 中立な名前は紛れやすい（`LocalDatabase` がすでにある）。logcat を `Piu` で始まるタグで絞れる | M5-04 |
| 2026-11-02 | 手元では、Gradle とエミュレータをメモリの上限つきで別のプロセスの単位で動かし、エミュレータは `-gpu lavapipe` で起動する（AI） | メモリが尽きて開発の環境ごと強制終了されたため。`swiftshader_indirect` は計器テストを回すたびにエミュレータのメモリが約 1 GB 増えて戻らない | [CONTRIBUTING.md](../CONTRIBUTING.md#検査のコマンド) |
| 2026-10-28 | データベースは Room 2.x。学習の表は索引の表に外部キーを張らず、索引だけを作り直せるようにする（AI） | Room 3.0 は coroutine 必須・KMP 向けで要らない。索引の作り直しで学習を消さないため | [architecture.md](design/architecture.md#データの保存) |
| 2026-10-28 | Material Search の辞書は取り込まない（AI） | 評価セットで、送り仮名の全問正解の例が崩れ、学習を除く正解率も下がった | [matching.md](design/matching.md#方針) |
| 2026-10-28 | 複合語の区切りは Kuromoji の NORMAL のまま（AI） | SEARCH モードと固有名詞の分け直しは評価セットで差がなく、後者はメモリが倍 | [matching.md](design/matching.md) |
| 2026-10-28 | M1 の照合の質: 学習で埋める想定のものを除き、既知の弱点も外れに数えて 3 位以内 96.8%（目安 95%）。既知の弱点は 15 件（2.8%） | M1-06b の評価 | [testing.md](design/testing.md#評価セット) |
| 2026-10-27 | 検査の道具: KDoc は detekt 2（alpha）の 3 規則、依存のライセンスは Licensee、網羅率は Kover（モジュールごとに 80%）。CI のキャッシュは setup-gradle の basic（AI） | AI が候補を試して比べた。detekt 1.23.8 は JDK 25 の Gradle で落ち、Dokka は上書きした関数にも KDoc を求める。Enhanced Caching は商用の部品 | [testing.md](design/testing.md#静的な検査) |
| 2026-10-27 | Android CLI（`sdkmanager` を含む）の利用状況の送信を `~/.androidrc` の `--no-metrics` で止める。CONTRIBUTING にも書く（AI） | 既定で送る。利用者の情報を Google に集めない（メンテナの方針） | [CONTRIBUTING.md](../CONTRIBUTING.md#android-sdk) |
| 2026-10-27 | `main` への push は、マイルストーンの区切りなどにまとめる（AI） | push のたびにエミュレータの CI が回り、private の間は Actions の分数に上限がある | |

## 未決

| 事項 | 選択肢 | メモ |
|---|---|---|
| 配布経路 | GitHub Releases・IzzyOnDroid・F-Droid・Google Play | AI の推奨: GitHub Releases と IzzyOnDroid で始め、F-Droid の本体に申請する。Google Play は使わない |
| Google の開発者確認への対応 | 登録する（本人確認つきの通常の登録／20 台までの限定配布の登録）／登録しない | 2027 年に全世界へ広がり、Google Play で配らないアプリも対象になる。登録しないと、素の Android の利用者は 1 日待つ手順（Advanced Flow）を一度踏む必要がある。GrapheneOS は対象外の見込み。M7 で決める（[android-platform.md](research/android-platform.md#開発者確認の詳細)） |
| A3〜A5 の確認 | | [requirements.md](requirements.md) の「AI が補った要件」。設計はこれらを満たす前提で進めている |
