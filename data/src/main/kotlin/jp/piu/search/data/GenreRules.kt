// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import jp.piu.search.core.search.GenreId

/**
 * 「この依頼を受けられるアプリはこのジャンル」の 1 行（matching.md の「intent filter とカテゴリによるジャンル」）。
 *
 * [intent] を受けられる Activity を持つアプリを [genre] に入れる。
 * intent は `<queries>` に書かない。問い合わせに出るのは入口（`MAIN`＋`LAUNCHER`）が見えるアプリだけで、索引に入れるのもそれだけのため
 * （パッケージの可視性はパッケージの単位。architecture.md の「権限とマニフェスト」）。
 */
internal class GenreRule(
    val genre: GenreId,
    val intent: IntentSpec,
    /** この版より前の端末では問い合わせない。定数が OS にないので、宣言するアプリもない。 */
    val minSdk: Int = 0,
)

/**
 * 問い合わせる intent の中身。`Intent` を直接持たず、中身を JVM のテストで比べられる形にする。
 */
internal data class IntentSpec(
    val action: String,
    val categories: Set<String> = emptySet(),
    /** data の URI。型だけで問い合わせるときも `content:` を付ける（下の [contentOf] の説明）。 */
    val data: String? = null,
    val mimeType: String? = null,
) {
    val scheme: String? get() = data?.substringBefore(':')

    fun toIntent(): Intent {
        val intent = Intent(action)
        categories.forEach(intent::addCategory)
        val uri = data?.let(Uri::parse)
        when {
            uri != null && mimeType != null -> intent.setDataAndType(uri, mimeType)
            uri != null -> intent.data = uri
            mimeType != null -> intent.type = mimeType
        }
        return intent
    }
}

/** 起動の入口に `CATEGORY_APP_*` を付けた Activity の問い合わせ（`Intent.makeMainSelectorActivity` と同じ組）。 */
private fun mainWith(category: String) = IntentSpec(Intent.ACTION_MAIN, categories = setOf(category))

private fun view(data: String) = IntentSpec(Intent.ACTION_VIEW, data = data)

/**
 * 型で開ける Activity の問い合わせ。型だけの intent は、`scheme="content"` を書いた filter（写真や PDF の閲覧で多い）に
 * 当たらない（`IntentFilter.matchData`）。`content:` の URI を添えると、型だけの filter にも `content` を書いた filter にも当たる
 */
private fun contentOf(mimeType: String) = IntentSpec(Intent.ACTION_VIEW, data = "content://$CONTENT_AUTHORITY/item", mimeType = mimeType)

/** [contentOf] の URI の持ち主。どの提供者にも問い合わせないので、実在しない名前でよい。 */
private const val CONTENT_AUTHORITY = "jp.piu.search.genre"

/**
 * ジャンルの判定の表。並びは matching.md の表と同じ。
 *
 * 版で分ける定数（`CATEGORY_APP_FILES` は 29、`CATEGORY_APP_WEATHER` は 33、`ACTION_TRANSLATE` は 29）は、文字列が
 * コンパイル時に埋め込まれるので古い端末でも参照できる。問い合わせるかは [GenreRule.minSdk] で分ける。
 */
@SuppressLint("InlinedApi")
internal val GENRE_RULES: List<GenreRule> =
    listOf(
        GenreRule(Genres.MAPS, view("geo:0,0?q=")),
        GenreRule(Genres.MAPS, mainWith(Intent.CATEGORY_APP_MAPS)),
        GenreRule(Genres.EMAIL, IntentSpec(Intent.ACTION_SENDTO, data = "mailto:")),
        GenreRule(Genres.EMAIL, mainWith(Intent.CATEGORY_APP_EMAIL)),
        GenreRule(Genres.PHONE, IntentSpec(Intent.ACTION_DIAL, data = "tel:")),
        GenreRule(Genres.MESSAGING, IntentSpec(Intent.ACTION_SENDTO, data = "smsto:")),
        GenreRule(Genres.MESSAGING, mainWith(Intent.CATEGORY_APP_MESSAGING)),
        GenreRule(Genres.CAMERA, IntentSpec(MediaStore.ACTION_IMAGE_CAPTURE)),
        GenreRule(Genres.PHOTOS, contentOf("image/*")),
        GenreRule(Genres.PHOTOS, mainWith(Intent.CATEGORY_APP_GALLERY)),
        GenreRule(Genres.VIDEO, contentOf("video/*")),
        GenreRule(Genres.MUSIC, contentOf("audio/*")),
        GenreRule(Genres.MUSIC, mainWith(Intent.CATEGORY_APP_MUSIC)),
        // BROWSABLE: リンクを開ける Activity に絞る。ホストを持たない https の filter はほぼブラウザだけが持つ
        GenreRule(
            Genres.BROWSER,
            IntentSpec(Intent.ACTION_VIEW, categories = setOf(Intent.CATEGORY_BROWSABLE), data = "https://example.org/"),
        ),
        GenreRule(Genres.BROWSER, mainWith(Intent.CATEGORY_APP_BROWSER)),
        GenreRule(Genres.CALENDAR, mainWith(Intent.CATEGORY_APP_CALENDAR)),
        // CalendarContract.Events.CONTENT_URI と、カレンダーのアプリが受ける型。型を明示し、提供者への問い合わせを避ける
        GenreRule(
            Genres.CALENDAR,
            IntentSpec(Intent.ACTION_INSERT, data = "content://com.android.calendar/events", mimeType = "vnd.android.cursor.dir/event"),
        ),
        GenreRule(Genres.CONTACTS, mainWith(Intent.CATEGORY_APP_CONTACTS)),
        GenreRule(Genres.CALCULATOR, mainWith(Intent.CATEGORY_APP_CALCULATOR)),
        GenreRule(Genres.CLOCK, IntentSpec(AlarmClock.ACTION_SET_ALARM)),
        GenreRule(Genres.FILES, mainWith(Intent.CATEGORY_APP_FILES), minSdk = 29),
        GenreRule(Genres.WEATHER, mainWith(Intent.CATEGORY_APP_WEATHER), minSdk = 33),
        GenreRule(Genres.TRANSLATE, IntentSpec(Intent.ACTION_TRANSLATE), minSdk = 29),
        GenreRule(Genres.STORE, mainWith(Intent.CATEGORY_APP_MARKET)),
        GenreRule(Genres.STORE, view("market://details?id=$CONTENT_AUTHORITY")),
        GenreRule(Genres.PDF, contentOf("application/pdf")),
    )

/**
 * `ApplicationInfo.category`（開発者が宣言する。API 26）→ ジャンル。
 * `PRODUCTIVITY` と `ACCESSIBILITY` は、対応するジャンルの語がないので使わない。
 */
internal val APP_CATEGORY_GENRES: Map<Int, GenreId> =
    mapOf(
        ApplicationInfo.CATEGORY_MAPS to Genres.MAPS,
        ApplicationInfo.CATEGORY_IMAGE to Genres.PHOTOS,
        ApplicationInfo.CATEGORY_VIDEO to Genres.VIDEO,
        ApplicationInfo.CATEGORY_AUDIO to Genres.MUSIC,
        ApplicationInfo.CATEGORY_GAME to Genres.GAME,
        ApplicationInfo.CATEGORY_NEWS to Genres.NEWS,
        ApplicationInfo.CATEGORY_SOCIAL to Genres.SOCIAL,
    )

/** ジャンルの id（matching.md の「ジャンルの id」。`GenreVocabulary.DEFAULT` の鍵と同じ文字列）。 */
internal object Genres {
    val MAPS = GenreId("maps")
    val EMAIL = GenreId("email")
    val PHONE = GenreId("phone")
    val MESSAGING = GenreId("messaging")
    val CAMERA = GenreId("camera")
    val PHOTOS = GenreId("photos")
    val VIDEO = GenreId("video")
    val MUSIC = GenreId("music")
    val BROWSER = GenreId("browser")
    val CALENDAR = GenreId("calendar")
    val CONTACTS = GenreId("contacts")
    val CALCULATOR = GenreId("calculator")
    val CLOCK = GenreId("clock")
    val FILES = GenreId("files")
    val WEATHER = GenreId("weather")
    val TRANSLATE = GenreId("translate")
    val STORE = GenreId("store")
    val PDF = GenreId("pdf")
    val GAME = GenreId("game")
    val NEWS = GenreId("news")
    val SOCIAL = GenreId("social")

    /** 表の順（matching.md）。判定の結果をこの順に並べ、毎回同じ順にする。 */
    val ORDER: List<GenreId> =
        listOf(
            MAPS,
            EMAIL,
            PHONE,
            MESSAGING,
            CAMERA,
            PHOTOS,
            VIDEO,
            MUSIC,
            BROWSER,
            CALENDAR,
            CONTACTS,
            CALCULATOR,
            CLOCK,
            FILES,
            WEATHER,
            TRANSLATE,
            STORE,
            PDF,
            GAME,
            NEWS,
            SOCIAL,
        )
}
