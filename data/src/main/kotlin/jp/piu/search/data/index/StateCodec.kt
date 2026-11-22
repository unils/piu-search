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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * 組 1 つの状態（[PackageState] から組を除いたもの）を、index.db の 1 行のバイト列にする。
 *
 * 表の列に分けないのは、入れ子（入口ごとのキーと語の区切り）が深く、列と表に分けると保存の部品が大きくなるため。
 * その代わり、形を変えたら [IndexRoomDatabase.VERSION] を上げる（上げれば Room が DB を捨てて作り直す）。
 * 上げ忘れは、決まった状態のバイト列を固定したテスト（`StateCodecTest`）で気づく。
 * 集まりは並べてから書き、同じ状態から同じバイト列を作る。
 */
internal object StateCodec {
    fun encode(state: PackageState): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.observation(state.observed)
            out.nullable(state.current) { current(it) }
            out.stringMap(state.substitutes) { string(it) }
            out.attempt(state.attempt)
            out.stringMap(state.genres) { genres -> list(genres) { string(it.value) } }
        }
        return bytes.toByteArray()
    }

    /** [bytes] を組 [key] の状態に戻す。形が合わなければ [IOException] か [IllegalArgumentException]（列挙の名前が知らない値）。 */
    fun decode(
        key: PackageKey,
        bytes: ByteArray,
    ): PackageState =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val state =
                PackageState(
                    key,
                    input.observation(),
                    input.nullable { current() },
                    input.stringMap { string() },
                    input.attempt(),
                    input.stringMap { list { GenreId(string()) } },
                )
            if (input.read() != -1) throw IOException("余分なバイトがある")
            state
        }

    private fun DataOutputStream.observation(o: PackageObservation) {
        strings(o.entries)
        writeBoolean(o.archived)
        nullable(o.apkKey) { string(it) }
        nullable(o.updatedAt) { writeLong(it) }
    }

    private fun DataInputStream.observation(): PackageObservation =
        PackageObservation(strings(), readBoolean(), nullable { string() }, nullable { readLong() })

    private fun DataOutputStream.materialKey(k: MaterialKey) {
        nullable(k.apkKey) { string(it) }
        nullable(k.updatedAt) { writeLong(it) }
        strings(k.entries)
        writeBoolean(k.archived)
        string(k.locale)
        string(k.osBuild)
        writeInt(k.readerVersion)
    }

    private fun DataInputStream.materialKey(): MaterialKey =
        MaterialKey(nullable { string() }, nullable { readLong() }, strings(), readBoolean(), string(), string(), readInt())

    private fun DataOutputStream.derivedKey(k: DerivedKey) {
        writeInt(k.generatorVersion)
        string(k.brandTableHash)
    }

    private fun DataInputStream.derivedKey(): DerivedKey = DerivedKey(readInt(), string())

    private fun DataOutputStream.current(c: Current) {
        materialKey(c.materialKey)
        derivedKey(c.derivedKey)
        stringMap(c.labels) { string(it) }
        stringMap(c.localized) { names -> list(names) { string(it) } }
        list(c.signerDns) { string(it) }
        stringMap(c.keysByEntry) { keys -> list(keys) { searchKey(it) } }
    }

    private fun DataInputStream.current(): Current =
        Current(
            materialKey(),
            derivedKey(),
            stringMap { string() },
            stringMap { list { string() } },
            list { string() },
            stringMap { list { searchKey() } },
        )

    private fun DataOutputStream.searchKey(k: SearchKey) {
        string(k.text.value)
        string(k.kind.name)
        list(k.wordStarts) { writeInt(it) }
        string(k.form.name)
    }

    private fun DataInputStream.searchKey(): SearchKey =
        SearchKey(NormalizedText(string()), SearchKeyKind.valueOf(string()), list { readInt() }, KeyForm.valueOf(string()))

    private fun DataOutputStream.attempt(a: Attempt) {
        materialKey(a.materialKey)
        derivedKey(a.derivedKey)
        nullable(a.bootCount) { writeInt(it) }
        nullable(a.issue) { issue(it) }
        writeBoolean(a.retry)
    }

    private fun DataInputStream.attempt(): Attempt =
        Attempt(
            materialKey(),
            derivedKey(),
            nullable {
                readInt()
            },
            nullable { issue() },
            readBoolean(),
        )

    private fun DataOutputStream.issue(i: Issue) {
        string(i.stage.name)
        string(i.failure.cause)
        string(i.failure.category.name)
        nullable(i.failure.frames) { string(it) }
        nullable(i.failure.inputSummary) { string(it) }
        writeInt(i.version)
        string(i.firstFailedOn)
        string(i.lastFailedOn)
    }

    private fun DataInputStream.issue(): Issue =
        Issue(
            Stage.valueOf(string()),
            Failure(string(), IssueCategory.valueOf(string()), nullable { string() }, nullable { string() }),
            readInt(),
            string(),
            string(),
        )

    // writeUTF は 64 KB までなので、長さを前に置いた UTF-8 にする
    private fun DataOutputStream.string(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.string(): String {
        val size = readInt()
        if (size < 0 || size > available()) throw IOException("文字列の長さが合わない: $size")
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.strings(values: Set<String>) = list(values.sorted()) { string(it) }

    private fun DataInputStream.strings(): Set<String> = list { string() }.toSet()

    private fun <T> DataOutputStream.list(
        values: List<T>,
        write: DataOutputStream.(T) -> Unit,
    ) {
        writeInt(values.size)
        values.forEach { write(it) }
    }

    private fun <T> DataInputStream.list(item: DataInputStream.() -> T): List<T> {
        val size = readInt()
        if (size < 0 || size > available()) throw IOException("要素の数が合わない: $size")
        return List(size) { item() }
    }

    private fun <T> DataOutputStream.stringMap(
        values: Map<String, T>,
        write: DataOutputStream.(T) -> Unit,
    ) = list(values.entries.sortedBy { it.key }) { (key, value) ->
        string(key)
        write(value)
    }

    private fun <T> DataInputStream.stringMap(item: DataInputStream.() -> T): Map<String, T> = list { string() to item() }.toMap()

    private fun <T : Any> DataOutputStream.nullable(
        value: T?,
        write: DataOutputStream.(T) -> Unit,
    ) {
        writeBoolean(value != null)
        if (value != null) write(value)
    }

    private fun <T : Any> DataInputStream.nullable(item: DataInputStream.() -> T): T? = if (readBoolean()) item() else null
}
