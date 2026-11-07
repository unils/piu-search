// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.content.pm.LauncherApps
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import jp.piu.search.core.search.AppId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import kotlin.math.roundToInt

/** 結果の行に出すアイコン。画面のテストでは偽物に差し替える。 */
interface AppIcons {
    /** メモリにある [app] のアイコン。なければ null（[load] で読む）。 */
    fun cached(app: AppId): ImageBitmap?

    /** [app] のアイコンを裏で読み、メモリに置く。読めなければ null。 */
    suspend fun load(app: AppId): ImageBitmap?
}

/**
 * `LauncherApps` から読むアイコン。
 *
 * アイコンはデータベースに入れず、使うときに読んでメモリ（LRU）に置く。アプリの更新でアイコンが変わっても、
 * 索引の作り直しを待たずに新しいものが出るため。`LauncherActivityInfo.getBadgedIcon` は、
 * 仕事用プロファイルのアプリに OS の標準の印（かばん）を重ねて返す。
 */
class LauncherAppIcons(
    context: Context,
    private val users: ProfileUsers,
    executor: ExecutorService,
) : AppIcons {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val densityDpi = context.resources.displayMetrics.densityDpi
    private val sizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    // 1 つ 40 dp 角（xxhdpi で 120 px 角、約 56 KB）。8 MB で 140 個ほどを置ける。画面に出るのは十数個
    private val cache =
        object : LruCache<AppId, ImageBitmap>(CACHE_BYTES) {
            override fun sizeOf(
                key: AppId,
                value: ImageBitmap,
            ): Int = value.asAndroidBitmap().allocationByteCount
        }

    override fun cached(app: AppId): ImageBitmap? = cache.get(app)

    override suspend fun load(app: AppId): ImageBitmap? =
        cached(app) ?: withContext(dispatcher) { cached(app) ?: read(app)?.also { cache.put(app, it) } }

    /** メモリにあるアイコンを手放す（`onTrimMemory`）。 */
    fun clear() {
        cache.evictAll()
    }

    private fun read(app: AppId): ImageBitmap? {
        val user = users.userFor(app.profileSerial) ?: return null
        val info =
            launcherApps
                .getActivityList(app.packageName, user)
                .firstOrNull { it.componentName.className == app.activityName } ?: return null
        return info.getBadgedIcon(densityDpi).toBitmap(sizePx, sizePx).asImageBitmap()
    }

    /** 定数。 */
    companion object {
        /** 結果の行のアイコンの大きさ（dp）。 */
        const val ICON_SIZE_DP = 40

        private const val CACHE_BYTES = 8 * 1024 * 1024
    }
}
