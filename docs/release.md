# 版の出し方

配布するのは、F-Droid の公式リポジトリと GitHub Releases の 2 か所だけ。どちらも同じ APK（メンテナの鍵で署名したもの）を配る（[decisions.md](decisions.md) の 2026-11-14）。

F-Droid は、タグのソースから自分で署名なしの APK を組み立て、GitHub Releases に置いたメンテナの APK から署名を写して貼り、検証が通るか（ZIP としてバイト単位で一致するか）を確かめる。通れば、メンテナの APK をそのまま配る。通らなければ、その版は F-Droid に出ない。このため、メンテナの手元の組み立ても、F-Droid の組み立ても、同じソースから同じ APK にならなければならない（再現可能なビルド）。

## 1. 版を上げる

pull request で、次の 4 か所を一度に直す。

- `app/build.gradle.kts` の `versionCode`（1 ずつ増やす）と `versionName`（[Semantic Versioning](https://semver.org/lang/ja/)）
- `CHANGELOG.md`: `[Unreleased]` の中身を `## [<版>] - <タグの日付>` の節に移し、空の `[Unreleased]` を残す。末尾の比較のリンクも足す
- `fastlane/metadata/android/ja-JP/changelogs/<versionCode>.txt` と `en-US/changelogs/<versionCode>.txt`: F-Droid のアプリに出る、その版の短い説明（500 字まで）

## 2. 実機で確かめる

タグの前に、版を上げた main を 4〜6 と同じ手順で組み立てて署名し、その APK を実機に入れて画面を 3 回開く（開くたびに閉じる）。logcat（タグ `PiuIndexSync`）の `SyncReport` が、2 回目以降 `rebuiltApps=0`・`failedPackages=0`・`failedProfiles=0` であることを確かめる（0.2.0 からは `failedPackages` の代わりに、`RETRY` と `ERROR` の組が 0 であることと、設定の「索引の状態」に予期しない欠けがないことを見る）。仕事用プロファイルを作れる端末なら、ある状態で確かめる。エミュレータの計器テストは自作のテスト用のアプリと system image のアプリしか見ないので、利用者の端末にだけある APK（APEX の中のアプリなど）で同期が収束しない不具合はここで見つける。

```bash
adb logcat -s PiuIndexSync:I
```

## 3. タグを付ける

merge した main のコミットに、`v<版>`（例: `v0.1.0`）のタグを付けて push する。F-Droid は `v` と数字と点だけのタグを新しい版として拾うので、ほかの形のタグを付けない。

```bash
git switch main && git pull
git tag v0.1.0
git push origin v0.1.0
```

## 4. 署名なしの APK を組み立てる

タグを取り出した、変更のない作業ツリーで組み立てる。ビルドキャッシュを使わず、`clean` から始める。

```bash
git switch --detach v0.1.0
git status --short   # 何も出ないこと
./gradlew --no-daemon --no-build-cache clean :app:assembleRelease
```

出力は `app/build/outputs/apk/release/app-release-unsigned.apk`。

APK に、組み立てる場所で変わるもの（git のリモートの URL とコミット、Google Play 向けの依存の情報）は入れていない（`app/build.gradle.kts` の `vcsInfo` と `dependenciesInfo`）。Baseline Profile はリポジトリに入れたものを使い、組み立てのたびには生成しない。

## 5. 再現できることを確かめる

同じタグを 2 つの別のディレクトリに置いて順に組み立て、署名なしの APK が一致するかを比べる。

```bash
tools/reproducible-build/check.sh v0.1.0
```

一致すれば終了コード 0。一致しなければ、違うファイルの一覧を出し、比べた APK を `build/reproducible-build/` に残す。2 回の組み立ての並列の度合いを変えるなら、`WORKERS_A=4 WORKERS_B=2` のように渡す。

これは同じ PC での確かめで、F-Droid の組み立て（Debian の OpenJDK 21）との一致までは保証しない。F-Droid の環境での確かめは、fdroidserver の `fdroid build` で行う（下の 8）。

## 6. メンテナの鍵で署名する

Android SDK の build-tools（36.0.0）の `apksigner` で署名する。鍵はリポジトリと CI の外にあり、メンテナだけが持つ。

```bash
apksigner sign --alignment-preserved --ks <メンテナの鍵> \
    --out piu-search-0.1.0.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs piu-search-0.1.0.apk
```

`--alignment-preserved` は必ず付ける。build-tools 35 以降の `apksigner` は、付けないと署名のときに ZIP の詰め物（揃えのための余白）を作り直す。すると署名なしの APK とバイトの並びが変わり、F-Droid が署名を貼っても検証が通らない。揃え（圧縮しない資源や `.so` を 4 バイトや 16 KB の境目に置き、端末がファイルを展開せずにそのまま読めるようにすること）は、AGP が組み立てのときに済ませている。付けても揃えの効果は変わらず、詰め物の作り方が変わらないだけになる。署名の後に `zipalign` も掛けない。その代わり、署名の前に揃っていることを確かめる。

```bash
zipalign -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk
```

`verify` の出力の `Signer #1 certificate SHA-256 digest` が、README の「インストール」に載せた指紋と同じであることを確かめる。

## 7. GitHub Releases に置く

タグ `v<版>` の Release を作り、署名した APK を `piu-search-<版>.apk` の名前で置く。F-Droid はこの URL（`https://github.com/unils/piu-search/releases/download/v<版>/piu-search-<版>.apk`）から APK を取るので、名前を変えない。説明には CHANGELOG のその版の節を写す。

```bash
gh release create v0.1.0 piu-search-0.1.0.apk --title "0.1.0" --notes-file <CHANGELOG の節>
```

F-Droid の組み立てより先に置く。APK がないと、F-Droid は比べられない。

## 8. F-Droid

F-Droid の組み立てのレシピは、F-Droid のリポジトリ（fdroiddata）の `metadata/jp.piu.search.yml` に置く。このリポジトリには置かない。要点は次のとおり。

- `subdir: app`、`gradle: [yes]` で `assembleRelease` を回す
- `Binaries:` に上の Releases の URL（版は `%v`）、`AllowedAPKSigningKeys:` にメンテナの証明書の SHA-256（コロンなしの小文字）を書く。これで、メンテナの署名のまま配る形になる
- `UpdateCheckMode: Tags ^v[0-9.]+$` と `AutoUpdateMode: Version` で、新しいタグから次の版を足す
- `prebuild` で、`settings.gradle.kts` と `build-logic/settings.gradle.kts` から foojay のプラグインの行を消す（`sed -i -e '/foojay/d' ../settings.gradle.kts ../build-logic/settings.gradle.kts`）

foojay のプラグインは、PC に JDK 21 がないとき Gradle に取得させるためのもので、開発者の手元では残す（[CONTRIBUTING.md](../CONTRIBUTING.md#java)）。F-Droid の組み立てでは JDK の自動の取得を止めていて、サーバーにある JDK 21 を使うので、プラグインは要らない。

最初の申請は、fdroiddata への merge request で行う。merge の前に、手元で `fdroid lint` と `fdroid build -v -l jp.piu.search` を通し、できた署名なしの APK が上の 4 のものと一致することを確かめておく。2 回目以降は、タグを push して Releases に APK を置けば、F-Droid が自動で拾う。

## 鍵について

メンテナの鍵をなくすと、同じアプリとして更新を出せなくなる（Android は、署名の違う APK での上書きを拒む）。鍵とパスワードは、リポジトリと CI の外に控えを置いて守る。
