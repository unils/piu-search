// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android のマニフェストから、要求する権限の名前を読み出す。
 */
object ManifestPermissions {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    // uses-permission-sdk-23 も、該当する版の端末では同じ権限を与える
    private val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

    /** アプリに持たせない権限。A1（通信しない）と、パッケージの可視性の方針による。 */
    val FORBIDDEN: Set<String> =
        setOf(
            "android.permission.INTERNET",
            "android.permission.QUERY_ALL_PACKAGES",
        )

    /**
     * [manifest] が要求する権限の名前を、出てきた順に重複なく返す。
     */
    fun read(manifest: InputStream): Set<String> {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // マニフェストに DTD は要らない。外部の実体の読み込みを防ぐ
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            }
        val document = factory.newDocumentBuilder().parse(manifest)
        val root = document.documentElement
        return PERMISSION_TAGS
            .flatMap { tag ->
                val nodes = root.getElementsByTagName(tag)
                (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
            }.filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    /**
     * [manifest] が要求する権限のうち、[forbidden] に含まれるものを返す。
     */
    fun findForbidden(
        manifest: InputStream,
        forbidden: Set<String> = FORBIDDEN,
    ): Set<String> = findForbidden(read(manifest), forbidden)

    /**
     * [permissions] のうち、[forbidden] に含まれるものを返す。
     *
     * 検査のタスクは、権限の一覧を報告にも書くので、読み出した一覧から選ぶ。
     */
    fun findForbidden(
        permissions: Set<String>,
        forbidden: Set<String> = FORBIDDEN,
    ): Set<String> = permissions.filterTo(LinkedHashSet()) { it in forbidden }
}
