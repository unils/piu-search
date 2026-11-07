# Android の API の確認

調査日: 2026-10-27。設計の前提にする API の挙動を、AOSP（Android のオープンソース部分）の `main` ブランチのソースで確かめた。Pixel の標準 OS は AOSP に Google の独自部分を足したものなので、ここで確かめたのは AOSP の範囲。

## デジタルアシスタントになれる条件

`PermissionController` の `AssistantRoleBehavior.getQualifyingPackagesInternal`（[ソース](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/role-controller/java/com/android/role/controller/behavior/AssistantRoleBehavior.java)）は、次のどちらかを持つアプリを「デジタルアシスタントのアプリ」の候補にする。

1. `VoiceInteractionService`（音声操作の常駐サービス）
2. `Intent.ACTION_ASSIST` と `CATEGORY_DEFAULT` を受ける、`exported`（ほかのアプリから起動できる）な Activity

2 だけで候補になる。Material Search もこの方式（`ACTION_ASSIST` の Activity）。

未確認: Pixel の「電源ボタンを長押し」「画面の隅からスワイプ」が、2 だけのアプリを起動するか。AOSP の外の Pixel 独自部分なので、ソースでは確かめられない。実機テストの項目にする。

## 静的ショートカットは取れない

`LauncherApps.getShortcuts` は `ensureShortcutPermission` で `hasShortcutHostPermission` を確かめる（[LauncherAppsService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java)）。この権限を持つのは既定のホームアプリだけなので、このアプリでは使えない。`docs/design/matching.md` の表にあった「`LauncherApps.getShortcuts` で静的ショートカットを取る」は誤りだった。

代わりの手: アプリのマニフェストの `android.app.shortcuts` の meta-data が指す XML を、そのアプリの `Resources` から読めば、ショートカットの名前（`shortcutShortLabel`）は取れる見込み。検索語の手がかりには使えるが、ショートカット自体の起動はできない（起動先の多くは他のアプリから起動できない Activity）。未検証。

## 仕事用プロファイルとプライベートスペース

`LauncherAppsService.canAccessProfile`（同じソース）:

- 呼び出し元が主プロファイルにいれば、同じプロファイルグループの仕事用プロファイルのアプリを一覧・起動できる（`isProfileAccessible`）。既定のホームアプリである必要はない
- 「隠しプロファイル」（Android 15 のプライベートスペース）は、`ACCESS_HIDDEN_PROFILES` の権限と既定のホームアプリの役割の両方を要求する。このアプリでは見えない

GrapheneOS で仕事用プロファイルを使う人（Shelter・Insular など）にも対応できる。

## パッケージの変更の検出

`PackageManager.getChangedPackages(int sequenceNumber)`（API 26、[PackageManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/PackageManager.java) の Javadoc）: 指定した番号より後に追加・削除・更新されたパッケージ名を返す。変更がなければ `null`。**番号は 0 から始まり、再起動のたびにリセットされる**（`Settings.Global.BOOT_COUNT` を見よ、とある）。

- アプリのインストールの通知（`ACTION_PACKAGE_ADDED`）は、Android 8.0 以降はマニフェストで受け取れない（暗黙のブロードキャストの制限）。常駐しないアプリなので、検索画面を開くたびに `getChangedPackages` で差分を取る設計にする
- 再起動で番号が戻るので、`BOOT_COUNT` が変わっていたら全件を見直す

## クイック設定タイル

`TileService.startActivityAndCollapse(Intent)` は非推奨で、変更 ID `START_ACTIVITY_NEEDS_PENDING_INTENT`（241766793）が有効なアプリ（target SDK 34 以上）では `UnsupportedOperationException` を投げる（[TileService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/quicksettings/TileService.java)）。API 34 以上では `PendingIntent` 版を使う。Material Search もこの分岐を持つ。

## ジャンルの手がかりになる定数

| 種類 | 定数 | API |
|---|---|---|
| メイン Activity のカテゴリ（アプリが宣言する） | `CATEGORY_APP_BROWSER`・`CALCULATOR`・`CALENDAR`・`CONTACTS`・`EMAIL`・`GALLERY`・`MAPS`・`MARKET`・`MESSAGING`・`MUSIC` | 24 以前 |
| 同上 | `CATEGORY_APP_FILES` | 29 |
| 同上 | `CATEGORY_APP_WEATHER`・`CATEGORY_APP_FITNESS` | 33 |
| `ApplicationInfo.category`（開発者が宣言する） | `GAME`・`AUDIO`・`VIDEO`・`IMAGE`・`SOCIAL`・`NEWS`・`MAPS`・`PRODUCTIVITY` | 26 |
| 同上 | `ACCESSIBILITY` | 31 |
| Intent の動作 | `ACTION_SET_ALARM` | 24 以前 |
| 同上 | `ACTION_TRANSLATE` | 29 |

出典: [`core/api/current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/current.txt) と `prebuilts/sdk` の API レベルごとの `android.txt`。API の欄は、確かめた最古の版（24）にあれば「24 以前」とした。

実際のアプリ（日本のアプリを含む）がこれらを宣言しているかは未確認。

## Google の開発者確認（配布に関わる）

[Android Developers の説明](https://developer.android.com/developer-verification)（Google の自社の制度の説明）:

- 2026-09-30 から、ブラジル・インドネシア・シンガポール・タイで、認定済みの Android 端末（Android 7 以上）に入れるアプリに、Google で本人確認した開発者であることを求め始めた。2027 年に全世界に広げる
- 学生・教員・趣味の開発者向けに、20 台までに配れる「Limited Distribution Account」（身分証も登録料も要らない）がある
- 確認されていないアプリを入れたい上級者向けの手順（Advanced Flow）を用意するとしている
- 対象は「認定済みの端末」。GrapheneOS は Google の認定を受けていないので、対象外とみられる（ページに明記はない）

F-Droid はこの制度に反対している（[公開書簡、2026-02-24](https://f-droid.org/fr/2026/02/24/open-letter-opposing-developer-verification.html)。F-Droid は影響を受ける当事者）。

未確認: `adb install` での導入が対象になるか。

## ツールの版（2026-10-27 時点の最新の安定版）

Maven Central・Google Maven・Gradle の配布元のメタデータで確かめた。実装の開始時にもう一度確かめる。

| 名前 | 版 |
|---|---|
| Android の最新の API レベル | 37（Android 17） |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.8.2 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| Room | 2.8.5 |
| Robolectric | 4.17 |
| AndroidX Core | 1.19.1（`minSdk` は 23） |

AndroidX の `minSdk` が 23 なので、このアプリの最小 SDK も 23 未満にはできない。

## 追記（2026-10-27、2 回目の調査）

### 一時停止中の仕事用アプリの起動

`ActivityStartInterceptor.interceptQuietProfileIfNeeded`（[ソース](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/ActivityStartInterceptor.java)）: 一時停止中（quiet mode）のプロファイルの Activity を開こうとすると、OS が起動を横取りして `UnlaunchableAppActivity` の再開の確認を出す。再開すれば元の起動を続ける。このアプリから仕事用アプリを開くときも同じになる見込み（M2・M3 でエミュレータで確かめる）。

### プロファイルの識別子

`UserManager.getSerialNumberForUser` の Javadoc: 「ユーザーを削除して新しく作っても、新しいユーザーに同じシリアル番号は付かない」。データベースにはこの番号で保存する。

### 開発者確認の詳細

[FAQ](https://developer.android.com/developer-verification/guides/faq) と [オープンソースのアプリの登録](https://developer.android.com/developer-verification/guides/open-source-app-registration)（どちらも Google 自身の制度の説明）:

- 2026-09-30 からの最初の段階は、指定の 4 か国で「参加しているストア」から入れる場合だけ。それ以外のストアや直接の APK の導入には、まだ適用しない
- 2027 年の全世界への拡大では、認定済みの端末（Android 7 以上）のすべてのアプリが対象。**Google Play で配らないアプリも対象**。検査は Google Play 開発者サービスを通して届く
- `adb install` での導入は、確認なしで入れられる（開発とテストのため）
- 確認されていない開発者のアプリを入れたい利用者向けの手順（Advanced Flow）: 開発者向けオプションを有効にする → 誰かに指示されていないかの確認 → 再起動と再認証 → **1 日待つ（初回だけ）** → 生体認証か PIN で確かめる → 7 日間か無期限で許可する
- F-Droid のように配布元が署名し直す場合: 開発者が Android Developer Console で、パッケージ名と**配布元の署名鍵の指紋**を登録する（配布元が API で代行する仕組みもある）。配布元が開発者の署名のまま配る場合は、追加の手続きは要らない（F-Droid は、再現可能なビルドにすると開発者の署名のまま配る）
- 1 つのパッケージ名に複数の署名鍵を登録できる
- GrapheneOS（認定を受けていない）への適用は明記されていない。検査が Google Play 開発者サービスを通すので、対象外とみられる

## 追記（2026-10-29、M3-03 の調査）

- デジタルアシスタントの役割（`ASSISTANT`）は、PermissionController の [`roles.xml`](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml) で `requestable="false"`。アプリから `RoleManager.createRequestRoleIntent` で頼めない。役割の選択の画面を直接開く `MANAGE_DEFAULT_APP` は `MANAGE_ROLE_HOLDERS` の権限が要る（同じモジュールのマニフェスト）。使えるのは `Settings.ACTION_VOICE_INPUT_SETTINGS`（API 37 のエミュレータでは PermissionController のアシスタントの選択の画面に直接着いた。API 26 では Settings の `ManageAssistActivity`）
- SystemUI の [`AssistManager.startAssistActivity`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/assist/AssistManager.java) は、`ACTION_ASSIST` に `FLAG_ACTIVITY_NEW_TASK` だけを付けて開く
- `StatusBarManager.requestAddTileService`（API 33）は権限が要らない。何度か断ると、OS が以後の頼みを自動で断ることがある
- 足したばかりのタイルは、SystemUI がサービスにつなぎ終えるまで押しても捨てられる（テストでは押し直す）
- Pixel の Settings は Google が手を入れているので、上の intent がどの画面に着くかは M6 の実機で確かめる
