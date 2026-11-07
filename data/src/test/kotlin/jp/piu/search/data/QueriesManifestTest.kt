// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// マニフェストの <queries> が起動できるアプリ（MAIN＋LAUNCHER）の 1 つだけであることを確かめる。
// 見える範囲を最小にする方針（architecture.md の「権限とマニフェスト」）で、ジャンルの判定の intent を書き足すと落ちる。
// 書かなくてもジャンルが取れることは、計器テスト（InstalledAppReaderInstrumentedTest）が端末で確かめる
class QueriesManifestTest {
    @Test
    fun onlyLauncherIntentIsDeclared() {
        val launcher = Query("android.intent.action.MAIN", setOf("android.intent.category.LAUNCHER"), null, null)
        assertEquals(listOf(launcher), declaredQueries())
    }

    @Test
    fun noPackageOrProviderIsDeclared() {
        assertEquals(emptyList<String>(), queriesChildren().map { it.tagName }.filter { it != "intent" })
    }

    private data class Query(
        val action: String,
        val categories: Set<String>,
        val scheme: String?,
        val mimeType: String?,
    )

    private fun queriesChildren(): List<Element> {
        // Gradle の単体テストは、モジュールのディレクトリで動く
        val manifest = File("src/main/AndroidManifest.xml")
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest)
        return document.getElementsByTagName("queries").elements().flatMap { it.childNodes.elements() }
    }

    private fun declaredQueries(): List<Query> =
        queriesChildren()
            .filter { it.tagName == "intent" }
            .map { intent ->
                val data = intent.children("data").singleOrNull()
                Query(
                    action = intent.children("action").single().androidAttribute("name")!!,
                    categories = intent.children("category").mapTo(HashSet()) { it.androidAttribute("name")!! },
                    scheme = data?.androidAttribute("scheme"),
                    mimeType = data?.androidAttribute("mimeType"),
                )
            }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) }.filterIsInstance<Element>()

    private fun Element.children(tag: String): List<Element> = childNodes.elements().filter { it.tagName == tag }

    private fun Element.androidAttribute(name: String): String? = getAttributeNS(ANDROID_NAMESPACE, name).takeIf { it.isNotEmpty() }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
