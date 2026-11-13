// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.Context
import android.content.pm.LauncherApps
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
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
    /** 表示中の行も更新を読み直すためのキャッシュの世代。 */
    val revision: Long get() = 0

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
    private val readIcon: ((AppId) -> ImageBitmap?)? = null,
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

    private val lock = Any()

    override var revision: Long by mutableLongStateOf(0)
        private set

    override fun cached(app: AppId): ImageBitmap? = cache.get(app)

    override suspend fun load(app: AppId): ImageBitmap? =
        withContext(dispatcher) {
            while (true) {
                val generation =
                    synchronized(lock) {
                        cached(app)?.let { return@withContext it }
                        revision
                    }
                val bitmap = readIcon?.invoke(app) ?: if (readIcon == null) read(app) else null
                synchronized(lock) {
                    // 読み込み中の更新で古いアイコンをキャッシュへ戻さない
                    if (revision == generation) {
                        if (bitmap != null) cache.put(app, bitmap)
                        return@withContext bitmap
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    /** 更新されたパッケージのすべての入口・プロファイルのアイコンを手放す。 */
    fun invalidate(packages: Set<String>) {
        synchronized(lock) {
            cache
                .snapshot()
                .keys
                .filter { it.packageName in packages }
                .forEach(cache::remove)
            revision++
        }
    }

    /** メモリにあるアイコンを手放す（`onTrimMemory`）。 */
    fun clear() {
        synchronized(lock) {
            cache.evictAll()
            revision++
        }
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
