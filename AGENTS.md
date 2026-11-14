# AGENTS.md

AI のコーディングエージェント向けの要点。人向けの決まりと詳しい手順は [CONTRIBUTING.md](CONTRIBUTING.md) にあり、こちらと食い違ったら CONTRIBUTING.md に従う。

## このリポジトリ

Android の端末に入っているアプリを、日本語の読み・表記ゆれ・ジャンルで探す検索アプリ。Kotlin と Jetpack Compose で書いている。設計は [docs/design/](docs/design/)、決めたことは [docs/decisions.md](docs/decisions.md)。

- `core/{text,reading,search}`: 正規化・読み・照合と学習。Android に依存しない Kotlin（JVM だけでテストする）
- `data`: 端末のアプリの索引とデータベース（Android ライブラリ）
- `app`: 画面と呼び出しの入口（Android アプリ）
- `release-test`・`benchmark`・`test-apps`: 計器テストと計測。配布物に入らない
- `build-logic`: 自前の Gradle プラグイン（検査）

## コマンド

```bash
./gradlew spotlessApply   # 書式を直す
./gradlew check           # 書式・Lint・単体テスト・網羅率・KDoc・権限などの検査
./gradlew --console=plain --no-build-cache clean check :app:assembleDebug :app:assembleRelease assembleAndroidTest   # CI と同じ形
./gradlew :core:search:test --tests "jp.piu.search.core.search.BrandTableTest"   # 1 つのテストだけ
./gradlew :core:search:updateEvalBaseline   # 評価セットの基準を書き直す（照合を意図して変えたときだけ。差分を pull request に載せる）
```

計器テスト（`connectedDebugAndroidTest` など）は端末かエミュレータが要る。手順は CONTRIBUTING.md の「検査のコマンド」。

Baseline Profile は公式の Gradle プラグイン（`androidx.baselineprofile`）で作る。生成は `./gradlew :app:generateBaselineProfile`（API 33 以上の端末かエミュレータが要る）で、出力の `app/src/release/generated/baselineProfiles/`（`baseline-prof.txt` と起動用の `startup-prof.txt`）をコミットする。ビルドのたびには生成しない。R8 を通した版の試験と起動の計測は、プラグインが作る `benchmarkRelease` を相手にする（`:release-test:connectedBenchmarkReleaseAndroidTest`・`:benchmark:connectedBenchmarkReleaseAndroidTest`）。そのための build type を自前で足さない。

## 決まり

- コメント・KDoc・文書・コミットのメッセージは日本語。識別子は英語。コメントは常体で、なぜそうしたかを書く
- 公開 API に KDoc を書く。新しい処理にはテストを書き、不具合を直すときは再現するテストを先に足す
- `core:*` に Android のライブラリを入れない
- `INTERNET` と `QUERY_ALL_PACKAGES` の権限を足さない。広告・解析・クラッシュの報告の部品と、Google Play 開発者サービスに依存しない
- 新しい権限・通信・依存は、設計にないなら先に issue で相談する
- 画面の文字列は `values/strings.xml`（英語）と `values-ja/strings.xml`（日本語）の両方に置く
- 秘密の情報（署名鍵・パスワード・トークン）をコミットしない
- AI が書いたコミットには `Co-Authored-By:` の行を付ける
