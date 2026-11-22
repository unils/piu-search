// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import jp.piu.search.data.index.IndexActor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// root からは database と sharedpref をたどらないため、転送する領域を明示する。クラウドはどの領域も許さない。
class BackupRulesTest {
    @Test
    fun obsoleteResourceFolderDoesNotRemainEvenWhenEmpty() {
        // Lint は空のディレクトリも検査するため、XML を移すだけでは足りない。
        assertFalse(File("src/main/res/xml-v28").exists())
    }

    @Test
    fun android9To11TransferIncludesDatabasesAndPreferencesButCloudIncludesNothing() {
        val files = File("src/main/res").walkTopDown().filter { it.name == "backup_rules.xml" }.toList()
        assertEquals(1, files.size)
        val rules = parse(files.single())
        val includes = rules.children("include")
        assertEquals(setOf("database", "sharedpref"), includes.map { it.getAttribute("domain") }.toSet())
        assertTrue(includes.all { it.getAttribute("path") == "." && it.getAttribute("requireFlags") == "deviceToDeviceTransfer" })
        assertIndexExcluded(rules.children("exclude"))
    }

    @Test
    fun android12AndLaterUseTheSameTransferDomainsAndExcludeEveryCloudDomain() {
        val rules = parse(File("src/main/res/xml/data_extraction_rules.xml"))
        val cloud = rules.children("cloud-backup").single()
        assertTrue(cloud.children("include").isEmpty())
        val excluded = cloud.children("exclude")
        assertEquals(
            setOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            ),
            excluded.map { it.getAttribute("domain") }.toSet(),
        )
        assertTrue(excluded.all { it.getAttribute("path") == "." })
        val transfer = rules.children("device-transfer").single().children("include")
        assertEquals(setOf("database", "sharedpref"), transfer.map { it.getAttribute("domain") }.toSet())
        assertTrue(transfer.all { it.getAttribute("path") == "." })
        assertIndexExcluded(rules.children("device-transfer").single().children("exclude"))
    }

    // 索引の DB は受け取った側で作り直す。SQLite の付属のファイルも外さないと、本体のない WAL だけが移る
    private fun assertIndexExcluded(excludes: List<Element>) {
        val name = IndexActor.FILE_NAME
        assertEquals(
            setOf(name, "$name-wal", "$name-shm", "$name-journal"),
            excludes.filter { it.getAttribute("domain") == "database" }.map { it.getAttribute("path") }.toSet(),
        )
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }
}
