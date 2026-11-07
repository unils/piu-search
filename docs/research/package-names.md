# パッケージ名の語と署名の証明書（2026-11-06）

実機の試験（M6）で「楽天」「rakuten」で iSPEED が、「やふー」で Y!乗換案内が出なかったのを受けて調べた。

## パッケージ名でよく使われる語

出典: F-Droid の索引（`https://f-droid.org/repo/index-v2.json`、4,957 個）、IzzyOnDroid の索引（`https://apt.izzysoft.de/fdroid/repo/index-v2.json`、1,404 個）、評価セットのアプリ（`core/search/src/test/resources/eval/apps.tsv`、日本で多く使われるアプリを含む 187 個）。どちらの索引も自由ソフトに偏る（Play ストアのアプリの一覧は公開のものがない）ので、日本のアプリは評価セットで補った。パッケージ名を `.` と `_` で分け、最初の部分（`com`・`jp` など）を除いて、語を含むパッケージの数を数えた。

| 分類 | 語 | 数（F-Droid・Izzy・評価セット） |
|---|---|---|
| ドメインの第 2 レベル | co・ne・or・ac・go・gr・ed・lg（日本）、com・org・net・io・www | co は 11・2・48（`jp.co.〜`）、com は 21・8・0（`au.com.〜`・`br.com.〜`） |
| コードの置き場 | github・gitlab・codeberg・sourceforge・bitbucket・framagit | github は 376・111・0（`io.github.<人>.〜`） |
| 技術の語 | android・app・apps・application・mobile・client・example | android は 249・45・76、app は 224・50・12、example は 33・14・0（`com.example.〜` はひな形の名前のまま） |
| 版の種類 | free・lite・full・pro・premium・paid・beta・alpha・nightly・debug・dev・release・stable・preview・canary・foss・fdroid・libre・play・gms・test・demo・sample | foss は 28・9・0、fdroid は 62・3・1、free は 12・3・0 |

- 上位のほかの語は、開発者の名前（`sidhant`・`woheller69` など）、会社の名前（`google` は評価セットで 29）、分野の語（`music`・`keyboard`・`launcher`）。どれもアプリや提供元を表すので外さない
- ドメインの第 2 レベルの語は、2 番目の位置のときだけ外すのがよい（`jp.go.〜` の `go` と、名前の中の語を混ぜないため）

## 署名の証明書の組織名（O=）

1 つの会社が複数のアプリを出す実在の例として、楽天の系列 4 個と Yahoo の系列 6 個の、Google Play で配られている APK（`apksigner verify --print-certs`）:

| パッケージ | 表示名 | 証明書の DN |
|---|---|---|
| jp.co.mobileit.ispeed_fx | iSPEED FX | CN=www.rakuten-sec.co.jp, OU=Information System, O="Rakuten Securities, Inc.", L=Shinagawa-ku, ST=Tokyo, C=JP |
| jp.co.rakuten_sec.ispeed | iSPEED | （同上） |
| jp.co.rakuten.android | 楽天市場 | CN=Rakuten, OU=Develop01, O="Rakuten,Inc.", L=Shinagawa-ku, ST=Tokyo, C=JP |
| jp.co.rakuten_bank.rakutenbank | 楽天銀行 | CN=apps.rakuten-bank.co.jp, OU=APPS, O="Rakuten Bank, Ltd.", L=Shinagawa-ku, ST=Tokyo, C=JP |
| jp.co.yahoo.android.apps.map ほか Yahoo 系 6 個 | Y!マップ・Y!乗換案内・ebookjapan・Y!フリマ・Y!オークション・Y!ショッピング | O=LY Corporation, ST=Tokyo, C=JP |

- iSPEED FX は、パッケージ名に会社の名前がないが、証明書の O= に Rakuten がある。提供元の手がかりとして有用
- Yahoo 系の表示名は、どれも「Yahoo!」ではなく「Y!」で始まる。証明書は LY Corporation（LINEヤフー）で、Yahoo の字はない
- 分割された APK の一覧では、9 個が `split_config.arm64_v8a.apk`（ARM の部品）を持つ。楽天市場だけは分割がない
- 気をつけること（未確認）: Google Play が鍵を作って署名するアプリ（Play App Signing）は、DN が Google の決まった値になるとされる。F-Droid が署名するアプリは、F-Droid の DN を共有する。どちらも提供元を表さないので、多くのアプリに共通する組織名は、パッケージ名の語と同じくいちばん下の段に置き、既知のものは外す

## 追記（M6-01、2026-11-06）

- Yahoo 系は鍵を替えている（APK Signature Scheme v3.1 の lineage）。API 32 以下の端末向けの署名は `O=Yahoo Japan Corporation`、33 以上は `O=LY Corporation`。アプリは、API 28 以上では前の鍵（`signingCertificateHistory`）の組織名も読む
- 9 個の APK の Source Stamp（Google Play が付ける印）の証明書は `CN=Android, OU=Android, O=Google Inc., …`。F-Droid が署名する APK は `CN=FDroid, OU=FDroid, O=fdroid.org, L=ORG, ST=ORG, C=UK`（f-droid.org の Editor と DAVx⁵ で確かめた）。Play App Signing で Google が作る鍵の DN は確かめていない
- エミュレータ（x86_64、ARM の変換なし）に入ったのは、楽天市場（API 37。分割がなく x86_64 の部品も持つ）と Y!乗換案内（API 26。base.apk だけで入った）だけ。ほかは `INSTALL_FAILED_MISSING_SPLIT` か minSdk（28〜32）で入らない
