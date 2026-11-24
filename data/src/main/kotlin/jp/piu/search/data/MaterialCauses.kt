// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.RemoteException
import jp.piu.search.core.search.AppEntry
import jp.piu.search.core.search.KeyGenerationException
import jp.piu.search.core.search.KeyInput
import java.io.IOException
import java.security.cert.CertificateException

/**
 * 組の読み取りの段（architecture.md の「材料の欠け」）。区分の引き方（[MaterialCauses.categoryOf]）が段で変わるので、読み手の側にも持つ。
 */
internal enum class MaterialStage {
    /** 組全体の詳細（一覧の表示名・入口の変換・一覧との突き合わせ）。 */
    ENTRY,

    /** 別の言語の名前。 */
    LABEL,

    /** 署名の DN。 */
    SIGNER,

    /** 検索キーの生成（このアプリの処理）。 */
    KEYS,
}

/** 欠けの理由の区分。保存せず、使うときに理由から [MaterialCauses.categoryOf] で引く。 */
internal enum class IssueCategory {
    /** 読み直せば直る見込みがある。通常の条件に加えて 1 回だけ読み直す。 */
    TRANSIENT,

    /** 材料（APK・OS・言語）が替わるまで同じ結果になる。 */
    DETERMINISTIC,

    /** このアプリ側の例外か、型で決められない例外。 */
    SUSPECTED_BUG,
}

/**
 * 段の読み取りの失敗 1 つ。境界（段の catch）まで元の例外か固定の符号を運び、分類は [MaterialCauses] が行う。
 * 例外のメッセージは保存しない（パスや識別子を含みうる）。
 */
internal class ReadFailure private constructor(
    val throwable: Throwable?,
    private val code: String?,
) {
    /** 保存する理由。 */
    val cause: String get() = code ?: MaterialCauses.causeOf(checkNotNull(throwable))

    /** 不具合の疑いのときに残すフレーム。 */
    fun frames(stage: MaterialStage): String? =
        if (throwable != null && MaterialCauses.categoryOf(stage, cause) == IssueCategory.SUSPECTED_BUG) {
            MaterialCauses.framesOf(throwable)
        } else {
            null
        }

    override fun toString(): String = "ReadFailure($cause)"

    companion object {
        fun of(throwable: Throwable): ReadFailure =
            if (throwable is MaterialReadException) ReadFailure(null, throwable.code) else ReadFailure(throwable, null)

        fun code(code: String): ReadFailure = ReadFailure(null, code)
    }
}

/**
 * 例外でない読み取りの結果を、固定の符号で知らせる例外。読み手の中から段の境界まで運ぶためだけに使い、型の名前は保存しない。
 *
 * @property code [MaterialCauses] の符号
 */
internal class MaterialReadException(
    val code: String,
) : RuntimeException()

/**
 * 欠けの理由の作り方と区分の引き方（architecture.md の「失敗の分け方」）。書くときも読み戻したときも同じ関数で区分を引く。
 */
internal object MaterialCauses {
    /** 一覧にあった入口が詳細にない。 */
    public const val VANISHED: String = "VANISHED"

    /** 読み取りの前後か、一覧と詳細で鍵が違う。 */
    public const val CHANGED_DURING_READ: String = "CHANGED_DURING_READ"

    /** `getPackageArchiveInfo` が null を返した。 */
    public const val ARCHIVE_UNREADABLE: String = "ARCHIVE_UNREADABLE"

    /** 署名が空だった。 */
    public const val NO_SIGNERS: String = "NO_SIGNERS"

    private val CODES =
        mapOf(
            VANISHED to IssueCategory.TRANSIENT,
            CHANGED_DURING_READ to IssueCategory.TRANSIENT,
            ARCHIVE_UNREADABLE to IssueCategory.DETERMINISTIC,
            NO_SIGNERS to IssueCategory.DETERMINISTIC,
        )

    // 親をたどって最初に当たる型で決める。NameNotFoundException は Exception の子、Resources.NotFoundException は
    // RuntimeException の子、CertificateException は GeneralSecurityException の子で、互いに親子でないので順は問わない
    private val TYPES =
        listOf(
            PackageManager.NameNotFoundException::class.java to IssueCategory.TRANSIENT,
            RemoteException::class.java to IssueCategory.TRANSIENT,
            IOException::class.java to IssueCategory.TRANSIENT,
            Resources.NotFoundException::class.java to IssueCategory.DETERMINISTIC,
            CertificateException::class.java to IssueCategory.DETERMINISTIC,
            SecurityException::class.java to IssueCategory.DETERMINISTIC,
        )

    private const val FRAME_COUNT = 5

    /**
     * [throwable] の理由。原因の連鎖に `RemoteException` があればそれを、なければ [throwable] 自身を分類に使い、
     * そのクラスから親をたどって、名前が `java.`・`android.` で始まる最寄りのクラスの名前を返す。
     * R8 で名前が変わらないのは、APK に入らない JDK と Android のフレームワークのクラスだけのため。
     */
    public fun causeOf(throwable: Throwable): String = stableName(classified(throwable).javaClass)

    /** 段 [stage] の理由 [cause] の区分。`KEYS` はこのアプリの処理なので、理由によらず不具合の疑い。 */
    public fun categoryOf(
        stage: MaterialStage,
        cause: String,
    ): IssueCategory = if (stage == MaterialStage.KEYS) IssueCategory.SUSPECTED_BUG else categoryOf(cause)

    /**
     * 理由 [cause] の区分。固定の符号は表から、クラス名は読み込んで親をたどって引く。読み込めない名前は不具合の疑い。
     */
    public fun categoryOf(cause: String): IssueCategory {
        CODES[cause]?.let { return it }
        val type =
            try {
                Class.forName(cause, false, MaterialCauses::class.java.classLoader)
            } catch (_: ClassNotFoundException) {
                return IssueCategory.SUSPECTED_BUG
            } catch (_: LinkageError) {
                return IssueCategory.SUSPECTED_BUG
            }
        return TYPES.firstOrNull { (known, _) -> known.isAssignableFrom(type) }?.second ?: IssueCategory.SUSPECTED_BUG
    }

    /** 分類に使う例外の先頭 5 フレーム。クラス・メソッド・ファイル・行の番号だけで、メッセージは入れない。 */
    public fun framesOf(throwable: Throwable): String =
        classified(throwable)
            .stackTrace
            .take(FRAME_COUNT)
            .joinToString("\n") { "${it.className}.${it.methodName}(${it.fileName ?: "Unknown"}:${it.lineNumber})" }

    // PackageManager は RemoteException を RuntimeException に包んで投げる。外側の名前では読み戻したときに一時的と分からない。
    // キーの生成の例外は、包む前の例外が理由になる
    private fun classified(throwable: Throwable): Throwable {
        val seen = HashSet<Throwable>()
        var current: Throwable? = throwable
        while (current != null && seen.add(current)) {
            if (current is RemoteException) return current
            current = current.cause
        }
        return if (throwable is KeyGenerationException) throwable.cause ?: throwable else throwable
    }

    private fun stableName(type: Class<*>): String {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name.startsWith("java.") || current.name.startsWith("android.")) return current.name
            current = current.superclass
        }
        return Throwable::class.java.name
    }
}

/**
 * `KEYS` の欠けの入力の要約（architecture.md の「失敗の分け方」）。入力の種類・長さ（符号位置の数）・文字種で、文字列そのものは入れない。
 * 例: `label:len42:kanji,kana`。
 */
internal object InputSummary {
    /** [entry] のキーの生成で起きた [failure] の要約。どの入力か分からなければ `unknown`。 */
    fun of(
        entry: AppEntry,
        failure: Throwable,
    ): String {
        val attributed = failure as? KeyGenerationException ?: return "unknown"
        val text =
            when (attributed.input) {
                KeyInput.LABEL -> entry.label
                KeyInput.LOCALIZED_LABEL -> entry.localizedLabels.getOrNull(attributed.index)
                KeyInput.SIGNER -> entry.signerNames.getOrNull(attributed.index)
                KeyInput.PACKAGE_NAME -> entry.id.packageName
            } ?: return "unknown"
        val kind =
            when (attributed.input) {
                KeyInput.LABEL -> "label"
                KeyInput.LOCALIZED_LABEL -> "localized"
                KeyInput.SIGNER -> "signer"
                KeyInput.PACKAGE_NAME -> "package"
            }
        return "$kind:len${text.codePointCount(0, text.length)}:${classes(text).joinToString(",").ifEmpty { "empty" }}"
    }

    private fun classes(text: String): List<String> {
        val found = HashSet<String>()
        text.codePoints().forEach { found += classOf(it) }
        return ORDER.filter { it in found }
    }

    private val ORDER = listOf("kanji", "kana", "alnum", "emoji", "other")

    private fun classOf(cp: Int): String =
        when {
            Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN -> "kanji"

            Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA ||
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA ||
                cp == 'ー'.code -> "kana"

            Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN || Character.isDigit(cp) -> "alnum"

            isEmoji(cp) -> "emoji"

            else -> "other"
        }

    // 絵文字の主な区画。異体字セレクタと ZWJ も絵文字の一部とみなす
    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0xFE00..0xFE0F || cp == 0x200D || cp in 0x1F1E6..0x1F1FF
}
