// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 依存のライセンスの 1 つ。
 *
 * @property identifier SPDX の識別子（`Apache-2.0` など）。Licensee が識別できなかったライセンスなら null
 * @property name ライセンスの名前
 * @property url ライセンスの文の置き場
 */
data class LicenseRef(
    val identifier: String?,
    val name: String,
    val url: String?,
)

/**
 * APK に入る依存の 1 つ（Licensee の artifacts.json の 1 項目）。
 *
 * @property groupId Maven の group
 * @property artifactId Maven の artifact
 * @property version 版
 * @property name POM に書かれた名前。ないこともある
 * @property licenses ライセンス
 */
data class OssLibrary(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val name: String?,
    val licenses: List<LicenseRef>,
) {
    /** `group:artifact:version` の形。 */
    val coordinates: String get() = "$groupId:$artifactId:$version"

    /** 見出しに出す名前。POM に名前がなければ artifact の名前。 */
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: artifactId
}

/**
 * ライセンスの画面に出すもの。どれも APK の assets/licenses/ から読む（app/build.gradle.kts が入れる）。
 *
 * @property libraries APK に入る依存。Licensee が検査に使った一覧そのもの
 * @property notice 根の NOTICE の全文（Kuromoji と IPADIC の注意書きを含む）
 * @property appLicense このアプリのライセンス（Apache-2.0）の全文
 * @property licenseTexts SPDX の識別子からライセンスの全文。APK に入れたものだけ
 */
data class OssLicenses(
    val libraries: List<OssLibrary>,
    val notice: String,
    val appLicense: String,
    val licenseTexts: Map<String, String>,
) {
    /** 読み込み。 */
    companion object {
        /** 依存の一覧の置き場（Licensee の androidAssetReportPath）。 */
        const val ARTIFACTS_ASSET = "licenses/artifacts.json"

        /** NOTICE の置き場。 */
        const val NOTICE_ASSET = "licenses/NOTICE"

        /** このアプリのライセンスの全文の置き場。 */
        const val APP_LICENSE_ASSET = "licenses/LICENSE"

        /**
         * 依存のライセンスの全文の置き場（SPDX の識別子ごと）。Apache-2.0 は 4 条で写しを渡すことを求めるので、URL だけでなく全文を入れる。
         * 依存に新しいライセンスが入ったら、ここに足す（テストが確かめる）。
         */
        val LICENSE_TEXT_ASSETS: Map<String, String> = mapOf("Apache-2.0" to "licenses/Apache-2.0.txt")

        /** [assets] から読む。画面のスレッドで呼ばない（合わせて 100 KB ほどを読む）。 */
        fun load(assets: AssetManager): OssLicenses {
            fun read(path: String) = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
            return OssLicenses(
                libraries = parseLibraries(read(ARTIFACTS_ASSET)),
                notice = read(NOTICE_ASSET),
                appLicense = read(APP_LICENSE_ASSET),
                licenseTexts = LICENSE_TEXT_ASSETS.mapValues { (_, path) -> read(path) },
            )
        }

        /** Licensee の artifacts.json を読む。識別できなかったライセンス（unknownLicenses）も名前と URL で出す。 */
        fun parseLibraries(json: String): List<OssLibrary> {
            val array = JSONArray(json)
            return List(array.length()) { i ->
                val item = array.getJSONObject(i)
                OssLibrary(
                    groupId = item.getString("groupId"),
                    artifactId = item.getString("artifactId"),
                    version = item.getString("version"),
                    name = item.optStringOrNull("name"),
                    licenses =
                        item
                            .objects(
                                "spdxLicenses",
                            ).map { LicenseRef(it.getString("identifier"), it.getString("name"), it.optStringOrNull("url")) } +
                            item.objects("unknownLicenses").map {
                                LicenseRef(null, it.optStringOrNull("name") ?: it.optString("url"), it.optStringOrNull("url"))
                            },
                )
            }
        }

        private fun JSONObject.objects(key: String): List<JSONObject> {
            val array = optJSONArray(key) ?: return emptyList()
            return List(array.length()) { array.getJSONObject(it) }
        }

        // optString は値がないとき "" を、null のとき "null" を返すので、どちらも null にする
        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
