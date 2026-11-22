// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.KeyForm
import jp.piu.search.core.search.SearchKey
import jp.piu.search.core.search.SearchKeyKind
import jp.piu.search.core.state.Attempt
import jp.piu.search.core.state.Current
import jp.piu.search.core.state.DerivedKey
import jp.piu.search.core.state.Failure
import jp.piu.search.core.state.Issue
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.MaterialKey
import jp.piu.search.core.state.PackageKey
import jp.piu.search.core.state.PackageObservation
import jp.piu.search.core.state.PackageState
import jp.piu.search.core.state.Stage
import jp.piu.search.core.text.NormalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

// 組の状態のバイト列。JVM だけで確かめる（Android の型を使わない）
class StateCodecTest {
    @Test
    fun everyFieldSurvivesARoundTrip() {
        assertEquals(STATE, StateCodec.decode(KEY, StateCodec.encode(STATE)))
        val bare = STATE.copy(current = null, substitutes = mapOf("Main" to "地図"), genres = emptyMap())
        val nulls =
            bare.copy(
                observed = bare.observed.copy(apkKey = null, updatedAt = null),
                attempt = bare.attempt.copy(bootCount = null, issue = null),
            )
        assertEquals(nulls, StateCodec.decode(KEY, StateCodec.encode(nulls)))
    }

    @Test
    fun theEncodingIsPinnedSoThatAChangeRequiresANewDatabaseVersion() {
        // この値が変わったら、バイト列の形が変わった。IndexRoomDatabase.VERSION を上げ、ここの値と版を書き直す
        assertEquals(1, IndexRoomDatabase.VERSION)
        assertEquals(PINNED, sha256(StateCodec.encode(STATE)))
    }

    @Test
    fun brokenBytesAreRefused() {
        val bytes = StateCodec.encode(STATE)
        assertThrows(IOException::class.java) { StateCodec.decode(KEY, bytes.copyOf(bytes.size - 3)) }
        assertThrows(IOException::class.java) { StateCodec.decode(KEY, bytes + byteArrayOf(0)) }
        // 知らない列挙の名前（版を上げずに列挙を変えた）
        val renamed = String(bytes, Charsets.ISO_8859_1).replace("LOOSE", "LOOSF").toByteArray(Charsets.ISO_8859_1)
        assertThrows(IllegalArgumentException::class.java) { StateCodec.decode(KEY, renamed) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val KEY = PackageKey(11, "io.example.maps")
        val MATERIAL = MaterialKey("apk:1", 0, setOf("Main", "Nav"), false, "ja-JP", "build-1", 1)
        val DERIVED = DerivedKey(7, "brand-1")
        val STATE =
            PackageState(
                KEY,
                PackageObservation(setOf("Main", "Nav"), false, "apk:1", 0),
                Current(
                    MATERIAL,
                    DERIVED,
                    mapOf("Main" to "地図", "Nav" to "ナビ"),
                    mapOf("Main" to listOf("Maps", "マップ")),
                    listOf("CN=Maps,O=Example"),
                    mapOf(
                        "Main" to
                            listOf(
                                SearchKey(NormalizedText("ちずあぷり"), SearchKeyKind.READING, listOf(2), KeyForm.LOOSE),
                                SearchKey(NormalizedText("地図"), SearchKeyKind.NAME),
                            ),
                        "Nav" to emptyList(),
                    ),
                ),
                emptyMap(),
                Attempt(
                    MATERIAL,
                    DERIVED,
                    5,
                    Issue(
                        Stage.KEYS,
                        Failure("java.lang.IllegalStateException", IssueCategory.SUSPECTED_BUG, "a.b(C.kt:1)", "label:len2:kanji"),
                        3,
                        "2026-11-01",
                        "2026-11-22",
                    ),
                    true,
                ),
                mapOf("Nav" to listOf(GenreId("map"), GenreId("navigation"))),
            )
        const val PINNED = "5e006a90e0db185cbac3e41eda8ee4f705b5e5808a4a10e53ca24a39f7efc169"
    }
}
