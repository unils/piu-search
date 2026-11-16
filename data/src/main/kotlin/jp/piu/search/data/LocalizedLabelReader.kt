// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * 入口の別の言語での表示名を読む（matching.md の「OS が持つアプリの情報」）。
 *
 * アプリの資源を日本語・英語・端末の言語の `Configuration` で開き、表示名の資源を引く。
 * 「Google マップ」を「Maps」でも引けるようにするため。
 */
public class LocalizedLabelReader internal constructor(
    private val context: Context,
    // 言語を指定したアプリの資源。テストで、言語ごとの資源の有無を偽装するために差し替える
    // （Robolectric の資源は、本物と同じ条件で Resources.NotFoundException を投げないため。testing.md の「分類」）
    private val resourcesFor: (LaunchableActivity, Configuration) -> Resources,
) {
    /** 端末の部品で作る。 */
    public constructor(
        context: Context,
    ) : this(context, { activity, configuration -> applicationResources(context, activity, configuration) })

    private val packageManager = context.packageManager

    /**
     * [activity] の日本語・英語・端末の言語での表示名。表示名（[LaunchableActivity.label]）と同じもの、空のもの、重なりは除く。
     * 名前が資源でない（マニフェストに直に書いた）アプリは空で、失敗ではない。
     *
     * 言語ごとに読み、読めなかった言語があれば最初の例外を [LabelRead.failure] に元の型のまま返す
     * （`values-ja/` にだけある名前は、英語で `Resources.NotFoundException` になっても日本語では読める）。
     */
    public fun read(activity: LaunchableActivity): LabelRead {
        val labelRes = labelResource(activity)
        if (labelRes == 0) return LabelRead(emptyList(), null)
        val names = LinkedHashSet<String>()
        var failure: Exception? = null
        for (locale in locales()) {
            // 失敗の単位は言語 1 つ。型の分類は段の境界（MaterialCauses）で行う
            val name =
                try {
                    loadText(activity, labelRes, locale)
                } catch (e: PackageManager.NameNotFoundException) {
                    failure = failure ?: e
                    continue
                } catch (e: RuntimeException) {
                    failure = failure ?: e
                    continue
                }
            if (name.isNotBlank() && name != activity.label) names += name
        }
        return LabelRead(names.toList(), failure)
    }

    /** 引く資源の id。0 は引かない。 */
    private fun labelResource(activity: LaunchableActivity): Int {
        val appLabelRes = activity.applicationInfo.labelRes
        return when (val own = activity.activityLabelRes) {
            0 -> {
                appLabelRes
            }

            null -> {
                // Activity の名前が分からない。アプリの名前と表示名が同じとき（入口がアプリの名前を受け継いでいる）だけ、
                // アプリの名前の資源を使う。違うとき（入口が自分の名前を持つ）に使うと、別の入口の名前を付けてしまう
                val appLabel = activity.applicationInfo.loadLabel(packageManager).toString()
                if (appLabel == activity.label) appLabelRes else 0
            }

            else -> {
                own
            }
        }
    }

    private fun locales(): List<Locale> {
        val device = Resources.getSystem().configuration.locales[0]
        return listOfNotNull(Locale.JAPANESE, Locale.ENGLISH, device).distinct()
    }

    // Lint は自分の画面の言語の切り替えとみなすが、変えるのは他のアプリの資源を開く Configuration だけで、
    // 自分の資源（App Bundle の言語の分割）には関わらない
    @SuppressLint("AppBundleLocaleChanges")
    private fun loadText(
        activity: LaunchableActivity,
        labelRes: Int,
        locale: Locale,
    ): String {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return resourcesFor(activity, configuration).getText(labelRes).toString().trim()
    }
}

/**
 * 言語を指定したアプリの資源。
 *
 * API 31 以上は `ApplicationInfo` から作るので、仕事用プロファイルのアプリにも使える。
 * API 30 以前は自分のプロファイルのパッケージの `Context` から作る（`Resources` の古いコンストラクタは
 * `AssetManager` を共有して言語を書き換え、同じアプリの表示名の読み込みを乱すので使わない）。
 * そのため API 30 以前の仕事用プロファイルでは、同じパッケージが主プロファイルにあるときだけ読める。
 */
private fun applicationResources(
    context: Context,
    activity: LaunchableActivity,
    configuration: Configuration,
): Resources =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.packageManager.getResourcesForApplication(activity.applicationInfo, configuration)
    } else {
        context
            .createPackageContext(activity.id.packageName, 0)
            .createConfigurationContext(configuration)
            .resources
    }

/**
 * 別の言語の名前の読み取り結果。
 *
 * @property names 読めた名前
 * @property failure 読めなかった言語の最初の例外（元の型のまま）。なければ null
 */
public class LabelRead(
    public val names: List<String>,
    public val failure: Exception?,
)
