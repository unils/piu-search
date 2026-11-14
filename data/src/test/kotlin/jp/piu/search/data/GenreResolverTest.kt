// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.GenreVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager

// 判定の表（GENRE_RULES・APP_CATEGORY_GENRES）と、手がかりを入口に結び付ける規則を、Robolectric の PackageManager で確かめる。
// intent filter の照合は Android の IntentFilter.match（Robolectric が持つ本物のコード）が行う
@RunWith(AndroidJUnit4::class)
class GenreResolverTest {
    private val packageManager: PackageManager = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    @Test
    fun genreIdsMatchTheVocabulary() {
        assertEquals(GenreVocabulary.DEFAULT.genres.toList(), Genres.ORDER)
        val used = GENRE_RULES.map { it.genre } + APP_CATEGORY_GENRES.values
        assertEquals(Genres.ORDER.toSet(), used.toSet())
    }

    @Test
    fun everyRuleFindsAnAppDeclaringItsIntent() {
        val apps =
            GENRE_RULES.mapIndexed { index, rule ->
                // 実際のアプリと同じく、受け口は起動の入口とは別の Activity に置く（パッケージのすべての入口に付くこと）
                install("test.rule$index", handlers = mapOf("Handler" to listOf(filterFor(rule.intent))))
            }
        val genres = resolver(sdkInt = 37).resolve(apps)
        GENRE_RULES.forEachIndexed { index, rule ->
            assertEquals("${rule.genre.value}: ${rule.intent}", listOf(rule.genre), genres[apps[index].id])
        }
    }

    @Test
    fun rulesForNewerConstantsAreSkippedOnOlderDevices() {
        // CATEGORY_APP_FILES と ACTION_TRANSLATE は API 29、CATEGORY_APP_WEATHER は API 33
        val files = install("test.files", launcherCategories = setOf(Intent.CATEGORY_APP_FILES))
        val weather = install("test.weather", launcherCategories = setOf(Intent.CATEGORY_APP_WEATHER))
        val translate = install("test.translate", handlers = mapOf("Handler" to listOf(IntentFilter(Intent.ACTION_TRANSLATE))))
        val apps = listOf(files, weather, translate)

        val api28 = resolver(sdkInt = 28).resolve(apps)
        assertEquals(emptyMap<AppId, List<GenreId>>(), api28)

        val api32 = resolver(sdkInt = 32).resolve(apps)
        assertEquals(mapOf(files.id to listOf(Genres.FILES), translate.id to listOf(Genres.TRANSLATE)), api32)

        val api33 = resolver(sdkInt = 33).resolve(apps)
        assertEquals(listOf(Genres.WEATHER), api33[weather.id])
    }

    @Test
    fun mimeTypeOnlyAndConcreteTypeFiltersAreFound() {
        // 型だけの filter（scheme なし）と、具体的な型（image/jpeg）の filter。どちらも「写真」の問い合わせに当たる
        val typeOnly = install("test.typeonly", handlers = mapOf("Viewer" to listOf(viewType("image/*", scheme = null))))
        val jpeg = install("test.jpeg", handlers = mapOf("Viewer" to listOf(viewType("image/jpeg", scheme = "content"))))
        val fileOnly = install("test.fileonly", handlers = mapOf("Viewer" to listOf(viewType("image/*", scheme = "file"))))

        val genres = resolver().resolve(listOf(typeOnly, jpeg, fileOnly))
        assertEquals(listOf(Genres.PHOTOS), genres[typeOnly.id])
        assertEquals(listOf(Genres.PHOTOS), genres[jpeg.id])
        // file: だけを受けるアプリは、content: の問い合わせに当たらない（いまの Android では content: で渡すのがふつう）
        assertEquals(null, genres[fileOnly.id])
    }

    @Test
    fun handlerThatIsAnEntryTagsOnlyThatEntry() {
        // 1 つのパッケージに「電話」と「連絡先」の入口がある機種の形
        val (phone, contacts) =
            installEntries(
                "test.dialer",
                entries =
                    mapOf(
                        "Phone" to listOf(launcher(), IntentFilter(Intent.ACTION_DIAL).apply { addDataScheme("tel") }),
                        "Contacts" to listOf(launcher(Intent.CATEGORY_APP_CONTACTS)),
                    ),
            )
        val genres = resolver().resolve(listOf(phone, contacts))
        assertEquals(listOf(Genres.PHONE), genres[phone.id])
        assertEquals(listOf(Genres.CONTACTS), genres[contacts.id])
    }

    @Test
    fun handlerThatIsNotAnEntryTagsEveryEntryOfThePackage() {
        val (main, settings) =
            installEntries(
                "test.mail",
                entries = mapOf("Inbox" to listOf(launcher()), "Settings" to listOf(launcher())),
                handlers = mapOf("Compose" to listOf(IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("mailto") })),
            )
        val genres = resolver().resolve(listOf(main, settings))
        assertEquals(listOf(Genres.EMAIL), genres[main.id])
        assertEquals(listOf(Genres.EMAIL), genres[settings.id])
    }

    @Test
    fun appCategoryGivesGenres() {
        val expected =
            mapOf(
                ApplicationInfo.CATEGORY_GAME to listOf(Genres.GAME),
                ApplicationInfo.CATEGORY_AUDIO to listOf(Genres.MUSIC),
                ApplicationInfo.CATEGORY_VIDEO to listOf(Genres.VIDEO),
                ApplicationInfo.CATEGORY_IMAGE to listOf(Genres.PHOTOS),
                ApplicationInfo.CATEGORY_SOCIAL to listOf(Genres.SOCIAL),
                ApplicationInfo.CATEGORY_NEWS to listOf(Genres.NEWS),
                ApplicationInfo.CATEGORY_MAPS to listOf(Genres.MAPS),
                ApplicationInfo.CATEGORY_PRODUCTIVITY to null,
                ApplicationInfo.CATEGORY_UNDEFINED to null,
            )
        val apps = expected.keys.associateWith { install("test.category$it", category = it) }
        val genres = resolver().resolve(apps.values.toList())
        for ((category, genre) in expected) assertEquals("category $category", genre, genres[apps.getValue(category).id])
    }

    @Test
    fun cluesAreMergedInTableOrderWithoutDuplicates() {
        // 地図: geo:・CATEGORY_APP_MAPS・category=MAPS の 3 つが重なる。ゲームの宣言は表の後ろなので後に並ぶ
        val app =
            install(
                "test.mapgame",
                category = ApplicationInfo.CATEGORY_MAPS,
                launcherCategories = setOf(Intent.CATEGORY_APP_MAPS),
                handlers =
                    mapOf(
                        "Viewer" to listOf(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("geo") }),
                        "Mail" to listOf(IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("mailto") }),
                    ),
            )
        assertEquals(listOf(Genres.MAPS, Genres.EMAIL), resolver().resolve(listOf(app))[app.id])
    }

    @Test
    fun workProfileEntryUsesTheSamePackageInThePersonalProfile() {
        val personal = install("test.maps", launcherCategories = setOf(Intent.CATEGORY_APP_MAPS))
        val work = personal.copyTo(serial = 10)
        // 主プロファイルにないパッケージは、PackageManager の問い合わせに出ない。category だけで決まる
        val workOnly =
            LaunchableActivity(
                AppId("test.workonly", "test.workonly.Main", 10),
                "Work only",
                Process.myUserHandle(),
                ApplicationInfo().apply {
                    packageName = "test.workonly"
                    category = ApplicationInfo.CATEGORY_NEWS
                },
                activityLabelRes = 0,
            )
        val genres = resolver().resolve(listOf(personal, work, workOnly))
        assertEquals(listOf(Genres.MAPS), genres[personal.id])
        assertEquals(listOf(Genres.MAPS), genres[work.id])
        assertEquals(listOf(Genres.NEWS), genres[workOnly.id])
    }

    @Test
    fun appWithoutCluesIsLeftOut() {
        val plain = install("test.plain")
        assertTrue(resolver().resolve(listOf(plain)).isEmpty())
    }

    @Suppress("DEPRECATION")
    @Test
    fun handlerChangeIsQueriedAgainWithoutAnApkUpdate() {
        val first = install("test.browser.first")
        val second = install("test.browser.second")
        val intent = GENRE_RULES.first { it.genre == Genres.BROWSER }.intent.toIntent()
        val shadow = shadowOf(packageManager)

        fun handler(activity: LaunchableActivity) =
            android.content.pm.ResolveInfo().apply {
                activityInfo =
                    android.content.pm.ActivityInfo().apply {
                        packageName = activity.id.packageName
                        name = activity.id.activityName
                    }
            }
        shadow.setResolveInfosForIntent(intent, listOf(handler(first)))
        val resolver = resolver()
        val stamps = listOf(first, second).map { ActivityStamp(it.id, 1, "same-apk") }
        assertEquals(listOf(Genres.BROWSER), resolver.resolveStamps(stamps)[first.id])
        shadow.setResolveInfosForIntent(intent, listOf(handler(second)))
        val updated = resolver.resolveStamps(stamps)
        assertEquals(null, updated[first.id])
        assertEquals(listOf(Genres.BROWSER), updated[second.id])
    }

    @Test
    @Config(sdk = [28, 37], shadows = [RestrictedPackageManager::class])
    fun browserGenreIncludesNonDefaultBrowsersWithoutAMainCategory() {
        val spec = GENRE_RULES.first { it.genre == Genres.BROWSER }.intent
        val first = install("test.browser.first", handlers = mapOf("Viewer" to listOf(filterFor(spec))))
        val second = install("test.browser.second", handlers = mapOf("Viewer" to listOf(filterFor(spec))))
        val shadow = shadowOf(packageManager) as RestrictedPackageManager
        for (default in listOf(first, second)) {
            shadow.defaultBrowser = default.id.packageName
            val genres = resolver().resolve(listOf(first, second))
            assertEquals(listOf(Genres.BROWSER), genres[first.id])
            assertEquals(listOf(Genres.BROWSER), genres[second.id])
        }
    }

    @Test
    @Config(sdk = [28, 37], shadows = [RestrictedPackageManager::class])
    fun cameraGenreIncludesInstalledCamerasAndQueriesEachPackageOnce() {
        val spec = GENRE_RULES.first { it.genre == Genres.CAMERA }.intent
        val (first, settings) =
            installEntries(
                "test.camera.first",
                entries = mapOf("Main" to listOf(launcher()), "Settings" to listOf(launcher())),
                handlers = mapOf("Capture" to listOf(filterFor(spec))),
            )
        val second = install("test.camera.second", handlers = mapOf("Capture" to listOf(filterFor(spec))))
        val genres = resolver().resolve(listOf(first, settings, first.copyTo(10), second))
        for (entry in listOf(first, settings, first.copyTo(10), second)) {
            assertEquals(listOf(Genres.CAMERA), genres[entry.id])
        }
        assertEquals(
            listOf(first.id.packageName, second.id.packageName),
            (shadowOf(packageManager) as RestrictedPackageManager).cameraPackages,
        )
    }

    /** Robolectric にない、AOSP の既定ブラウザと暗黙のカメラ問い合わせの制限を再現する。 */
    @Implements(className = "android.app.ApplicationPackageManager")
    class RestrictedPackageManager : ShadowApplicationPackageManager() {
        var defaultBrowser: String? = null
        val cameraPackages = ArrayList<String?>()

        @Implementation
        override fun queryIntentActivities(
            intent: Intent,
            flags: Int,
        ): List<android.content.pm.ResolveInfo> {
            val candidates = super.queryIntentActivities(intent, flags)
            if (intent.action == MediaStore.ACTION_IMAGE_CAPTURE) {
                cameraPackages += intent.`package`
                return if (intent.`package` == null) {
                    candidates.filter { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                } else {
                    candidates
                }
            }
            if (intent.action == Intent.ACTION_VIEW && intent.scheme == "https" && flags and PackageManager.MATCH_ALL == 0) {
                return candidates.filter { it.activityInfo.packageName == defaultBrowser }
            }
            return candidates
        }
    }

    @Test
    fun measureGenreResolutionForFiveHundredEntries() {
        val stamps =
            (0 until 500).map { i ->
                ActivityStamp(AppId("io.example.app$i", "Main", 0), 1, category = ApplicationInfo.CATEGORY_MAPS)
            }
        val resolver = resolver()
        repeat(3) { resolver.resolveStamps(stamps) }
        val nanos =
            (0 until 10)
                .map {
                    val started = System.nanoTime()
                    assertEquals(500, resolver.resolveStamps(stamps).size)
                    System.nanoTime() - started
                }.sorted()
        val medianMillis = (nanos[4] + nanos[5]) / 2_000_000.0
        println(
            "ジャンル 500 入口・10 回: 中央値 $medianMillis ms、最小 ${nanos.first() / 1_000_000.0} ms、最大 ${nanos.last() / 1_000_000.0} ms（Robolectric）",
        )
    }

    private fun resolver(sdkInt: Int = 37) = GenreResolver(packageManager, sdkInt)

    /** 起動の入口を 1 つ（`Main`）持つアプリを入れる。 */
    private fun install(
        packageName: String,
        category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
        launcherCategories: Set<String> = emptySet(),
        handlers: Map<String, List<IntentFilter>> = emptyMap(),
    ): LaunchableActivity =
        installEntries(
            packageName,
            category,
            entries = mapOf("Main" to listOf(launcher(*launcherCategories.toTypedArray()))),
            handlers = handlers,
        ).single()

    /** [entries] を起動の入口、[handlers] を入口でない Activity として入れ、入口を返す。 */
    private fun installEntries(
        packageName: String,
        category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
        entries: Map<String, List<IntentFilter>>,
        handlers: Map<String, List<IntentFilter>> = emptyMap(),
    ): List<LaunchableActivity> {
        val applicationInfo =
            ApplicationInfo().apply {
                this.packageName = packageName
                this.category = category
            }
        val activities = entries + handlers
        val packageInfo =
            PackageInfo().apply {
                this.packageName = packageName
                this.applicationInfo = applicationInfo
            }
        val shadow = shadowOf(packageManager)
        shadow.installPackage(packageInfo)
        // Activity は PackageInfo に入れず、ここで足す。PackageInfo に入れた Activity には intent filter を足せない
        for ((name, filters) in activities) {
            val component = ComponentName(packageName, "$packageName.$name")
            shadow.addActivityIfNotPresent(component)
            filters.forEach { shadow.addIntentFilterForActivity(component, it) }
        }
        return entries.keys.map { name ->
            LaunchableActivity(AppId(packageName, "$packageName.$name", 0), name, Process.myUserHandle(), applicationInfo, 0)
        }
    }

    private fun LaunchableActivity.copyTo(serial: Long) =
        LaunchableActivity(id.copy(profileSerial = serial), label, user, applicationInfo, activityLabelRes)

    private fun launcher(vararg categories: String) =
        IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            categories.forEach(::addCategory)
        }

    private fun viewType(
        mimeType: String,
        scheme: String?,
    ) = IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        scheme?.let(::addDataScheme)
        addDataType(mimeType)
    }

    /** 表の 1 行の intent を受ける、ふつうのアプリの書き方の filter。 */
    private fun filterFor(spec: IntentSpec): IntentFilter =
        IntentFilter(spec.action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            spec.categories.forEach(::addCategory)
            spec.scheme?.let(::addDataScheme)
            spec.mimeType?.let(::addDataType)
        }
}
