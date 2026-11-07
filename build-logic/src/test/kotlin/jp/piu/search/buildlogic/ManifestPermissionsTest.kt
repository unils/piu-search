// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ManifestPermissionsTest {
    private fun manifest(body: String) =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x">
        $body
        </manifest>
        """.trimIndent().byteInputStream()

    @Test
    fun `権限がなければ空`() {
        assertEquals(emptySet<String>(), ManifestPermissions.findForbidden(manifest("<application/>")))
    }

    @Test
    fun `許す権限は見逃す`() {
        val xml = manifest("""<uses-permission android:name="android.permission.VIBRATE"/>""")
        assertEquals(emptySet<String>(), ManifestPermissions.findForbidden(xml))
    }

    @Test
    fun `INTERNET と QUERY_ALL_PACKAGES を見つける`() {
        val xml =
            manifest(
                """
                <uses-permission android:name="android.permission.INTERNET"/>
                <uses-permission android:name="android.permission.VIBRATE"/>
                <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>
                """,
            )
        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.QUERY_ALL_PACKAGES"),
            ManifestPermissions.findForbidden(xml),
        )
    }

    @Test
    fun `uses-permission-sdk-23 も見る`() {
        val xml = manifest("""<uses-permission-sdk-23 android:name="android.permission.INTERNET"/>""")
        assertEquals(setOf("android.permission.INTERNET"), ManifestPermissions.findForbidden(xml))
    }

    @Test
    fun `DTD を含むマニフェストは読まない`() {
        val xml =
            """
            <?xml version="1.0"?>
            <!DOCTYPE manifest [<!ENTITY e SYSTEM "file:///etc/passwd">]>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">&e;</manifest>
            """.trimIndent().byteInputStream()
        assertThrows<Exception> { ManifestPermissions.read(xml) }
    }
}
