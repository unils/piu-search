# 版の出し方

配布の仕組み、ソースコードから同じ APK が組み立てられること（再現可能なビルド）の検証方法、およびメンテナが新しい版を出す手順をまとめる。

## 配布の仕組みと署名鍵

配布先は、F-Droid の公式リポジトリと GitHub Releases の 2 か所だけである。どちらもメンテナの鍵で署名した同じ APK を配る（[decisions.md](decisions.md) の 2026-11-14）。

F-Droid は、タグのソースコードから署名なしの APK を自分で組み立て、GitHub Releases に置いたメンテナの APK から署名を写して貼り、検証が通るか（ZIP としてバイト単位で一致するか）を確かめる。通ればメンテナの APK をそのまま配り、通らなければその版は F-Droid に出ない。このため、メンテナの手元の組み立ても F-Droid の組み立ても、同じソースから同じ APK にならなければならない。

署名鍵とパスワードはメンテナだけが持ち、リポジトリと CI の外に控えを置いて守る。メンテナの署名鍵を失うと、同じアプリとして更新を出せなくなる（Android は署名の異なる APK による上書きを拒む）。

## 再現可能なビルドと検証

組み立てる環境によって変わる情報（git のリモートの URL とコミット、Google Play 向けの暗号化された依存情報）は APK に入れていない（`app/build.gradle.kts` の `vcsInfo` と `dependenciesInfo`）。Baseline Profile もリポジトリにコミットしたものを使い、ビルドのたびには生成しない。

特定のタグ（例: `v0.1.0`）から署名なしの APK を組み立てるには、変更のない作業ツリーでビルドキャッシュを使わずに `clean` から実行する。

```bash
git switch --detach v0.1.0
git status --short   # 何も出ないこと
./gradlew --no-daemon --no-build-cache clean :app:assembleRelease
```

出力は `app/build/outputs/apk/release/app-release-unsigned.apk` である。

同じタグを 2 つの別のディレクトリに展開して順に組み立て、署名なしの APK がバイト単位で一致するかは次のスクリプトで確かめられる。

```bash
tools/reproducible-build/check.sh v0.1.0
```

一致すれば終了コード 0 で終わる。一致しなければ、異なるファイルの一覧を出し、比べた APK を `build/reproducible-build/` に残す。2 回の組み立てで並列の度合いを変えて試すときは、`WORKERS_A=4 WORKERS_B=2` のように環境変数を渡す。

この検査は同じ PC 上での確認であり、F-Droid のビルド環境（Debian の OpenJDK 21）との一致までは保証しない。F-Droid の環境での確認は、fdroidserver の `fdroid build` で行う。

## リリースの手順

### 1. 版を上げて実機で確かめる

pull request で、次の 4 か所を一度に更新する。

- `app/build.gradle.kts` の `versionCode`（1 ずつ増やす）と `versionName`（[Semantic Versioning](https://semver.org/lang/ja/)）
- `CHANGELOG.md`: `[Unreleased]` の中身を `## [<版>] - <タグの日付>` の節に移し、空の `[Unreleased]` を残す。末尾の比較リンクも足す
- `fastlane/metadata/android/ja-JP/changelogs/<versionCode>.txt` と `en-US/changelogs/<versionCode>.txt`: F-Droid のクライアントに出る、その版の短い説明（500 字以内）

タグを付ける前に、版を上げた `main` を手順 2 と同じ方法で組み立てて署名し、実機に入れて検索画面を 3 回開く（開くたびに閉じる）。

```bash
adb logcat -s PiuIndexSync:I
```

logcat（タグ `PiuIndexSync`）に出る `SyncReport` が、2 回目以降の同期で次の条件を満たすことを確かめる（0.1.x では `rebuiltApps=0`・`failedPackages=0`・`failedProfiles=0`）。

- `rebuiltApps=0`・`readPackages=0`（同じ状態で作り直しや読み直しが起きないこと）
- `issueCounts` の `retryPending=0`・`suspectedBug=0`（再試行待ちと不具合の疑いの欠けが 0 であること）
- `genreFailure=null`、かつ `profileResults` に `FAILED` がないこと
- 設定の「索引の状態」に予期しない欠けがないこと

仕事用プロファイルを作れる端末では、仕事用プロファイルがある状態で確かめる。エミュレータの計器テストは自作のテスト用アプリとシステムイメージのアプリしか見ないため、実機にだけある APK（APEX の中のアプリなど）で同期が収束しない不具合はここで見つける。

### 2. タグを付けて組み立て・署名する

merge した `main` のコミットに、`v<版>`（例: `v0.1.0`）のタグを付けて push する。F-Droid は `v` と数字と点だけのタグを新しい版として拾うため、他の形のタグは付けない。

```bash
git switch main && git pull
git tag v0.1.0
git push origin v0.1.0
```

上の「再現可能なビルドと検証」の手順でタグから `app-release-unsigned.apk` を組み立て、`tools/reproducible-build/check.sh v0.1.0` が通ることを確かめる。

続けて、署名なしの APK の揃え（zipalign）が済んでいることを確かめる。

```bash
zipalign -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk
```

次に、Android SDK の build-tools（36.0.0）の `apksigner` でメンテナの鍵を使って署名する。

```bash
apksigner sign --alignment-preserved --ks <メンテナの鍵> \
    --out piu-search-0.1.0.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs piu-search-0.1.0.apk
```

`--alignment-preserved` は必ず付ける。build-tools 35 以降の `apksigner` は、この指定がないと署名時に ZIP の詰め物（揃えのための余白）を作り直す。その結果、署名なしの APK とバイトの並びが変わり、F-Droid が署名を写して貼っても検証が通らなくなる。揃え（非圧縮の資源や `.so` を 4 バイトや 16 KB の境界に置き、端末がファイルを展開せずにそのまま読めるようにすること）は、AGP が組み立ての時点で済ませている。`--alignment-preserved` を付けても揃えの効果は変わらず、詰め物の作り方が変わらないだけになる。署名の後に `zipalign` を掛けてはならない。

`apksigner verify` の出力の `Signer #1 certificate SHA-256 digest` が、[README.md](../README.md#インストールと署名の確認) に載せた指紋と同じであることを確かめる。

### 3. GitHub Releases に公開する

タグ `v<版>` の Release を作り、次の 2 つのファイルを添付する。説明には `CHANGELOG.md` のその版の節を写す。

- `piu-search-<版>.apk`: 署名済みの APK。F-Droid は `https://github.com/unils/piu-search/releases/download/v<版>/piu-search-<版>.apk` から APK を取得するため、ファイル名を変えない
- `piu-search-<版>-mapping.txt`: ビルド時に出力された R8 の名前の対応表（`app/build/outputs/mapping/release/mapping.txt`）。報告用の文の不具合の疑いの行には、R8 で名前が変わった例外のフレーム（行番号は残し、ファイル名は `SourceFile` に置換）と、記録した版（`ver=`）が入るため、メンテナはその版の対応表で元のクラス名とメソッド名に戻す（[architecture.md](design/architecture.md#失敗の分け方)）。対応表に秘密の情報は含まれず、再現可能なビルドなのでタグから作り直せるが、報告を読むたびにビルドせずに済むよう Release に置いておく

```bash
gh release create v0.1.0 piu-search-0.1.0.apk piu-search-0.1.0-mapping.txt --title "0.1.0" --notes-file <CHANGELOG の節>
```

F-Droid のビルドが走るより先に Release を公開する。APK が置かれていないと、F-Droid は署名を比較できない。

## F-Droid のビルド定義

F-Droid のビルドレシピは、F-Droid のリポジトリ（fdroiddata）の `metadata/jp.piu.search.yml` に置き、このリポジトリには置かない。レシピの要点は次のとおりである。

- `subdir: app`、`gradle: [yes]` で `assembleRelease` を回す
- `Binaries:` に上の GitHub Releases の URL（版は `%v`）、`AllowedAPKSigningKeys:` にメンテナの証明書の SHA-256 指紋（コロンなしの小文字）を指定し、メンテナの署名のまま配信する形にする
- `UpdateCheckMode: Tags ^v[0-9.]+$` と `AutoUpdateMode: Version` で、新しいタグから次の版を自動で追加する
- `prebuild` で、`settings.gradle.kts` と `build-logic/settings.gradle.kts` から foojay のプラグインの行を消す（`sed -i -e '/foojay/d' ../settings.gradle.kts ../build-logic/settings.gradle.kts`）

foojay のプラグインは、開発者の手元に JDK 21 がないとき Gradle に自動取得させるためのものであり、リポジトリには残す（[developer-guide.md](developer-guide.md#java)）。一方、F-Droid のビルド環境では JDK の自動取得を止めてサーバー上の JDK 21 を使うため、ビルド前に取り除く。

初回の申請は fdroiddata への merge request で行う。申請の前に、手元で `fdroid lint` と `fdroid build -v -l jp.piu.search` を通し、できた署名なしの APK が手順 2 のものと一致することを確かめておく。2 回目以降は、タグを push して GitHub Releases に APK を置けば、F-Droid が自動で検出してビルドする。
