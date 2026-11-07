// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.text.TextNormalizer
import java.text.Normalizer

/**
 * 「すべてのアプリ」の一覧の 1 行（[AppNameOrder.sort] の結果）。
 *
 * @property app アプリ
 * @property section 頭文字の索引の見出し（`A`〜`Z`・`あ` `か` … `わ`・[AppNameOrder.OTHER_SECTION]）
 */
public data class NameOrderedApp(
    val app: IndexedApp,
    val section: String,
)

/**
 * 「すべてのアプリ」の並べ方（ui.md の「すべてのアプリ」）。
 *
 * 1. 英字で始まる名前を A〜Z の順で先に（大文字と小文字・全角と半角・アクセントを区別しない）
 * 2. かな・漢字で始まる名前を、読みの五十音順に。見出しは行（あ・か・さ…わ）
 * 3. 数字・記号・ほかの文字で始まる名前と、読みのわからない漢字の名前を最後に（見出しは [OTHER_SECTION]）
 *
 * 名前の頭の空白と記号は飛ばして見る（`+メッセージ` は「め」）。漢字の名前の読みは、索引の読みのキー（[SearchKeyKind.READING]）から取る。
 * 五十音順は、濁点・半濁点と小さい仮名を除き、長音「ー」を前の字の母音に置き換えた形で比べ、同じなら元の形で比べる
 * （「かめら」「がいど」は「かいど」と「かめら」として比べ、がいど が先）。`Collator` は端末の JDK で順が変わりうるので使わない。
 */
public object AppNameOrder {
    /** 数字・記号・ほかの文字で始まる名前の見出し。 */
    public const val OTHER_SECTION: String = "#"

    /** [apps] を並べ、見出しを付ける。同じ並びの鍵なら表示名・識別子の順にして、毎回同じ順にする。 */
    public fun sort(apps: List<IndexedApp>): List<NameOrderedApp> =
        apps
            .map { SortEntry(it, classify(it)) }
            .sortedWith(ENTRY_ORDER)
            .map { NameOrderedApp(it.app, it.key.section) }

    private fun classify(app: IndexedApp): SortKey {
        val strict = TextNormalizer.strict(app.label).value
        val first = strict.firstOrNull() ?: return SortKey(Group.OTHER, OTHER_SECTION, app.label, app.label)
        latinBase(first)?.let { return SortKey(Group.LATIN, it.uppercaseChar().toString(), withoutAccents(strict), strict) }
        if (!isHiragana(first) && !Character.isIdeographic(first.code)) return SortKey(Group.OTHER, OTHER_SECTION, strict, strict)
        val reading = if (strict.all { isHiragana(it) || it == PROLONGED_SOUND_MARK }) strict else readingOf(app) ?: strict
        val head = reading.firstOrNull()
        if (head == null || !isHiragana(head)) return SortKey(Group.OTHER, OTHER_SECTION, reading, reading)
        val primary = gojuonKey(reading)
        return SortKey(Group.KANA, rowHead(primary.first()), primary, reading)
    }

    /** 表示名の読み。表示名のキーが先に作られるので、最初の読みのキーが表示名のもの（[SearchKeyGenerator.generate]）。 */
    private fun readingOf(app: IndexedApp): String? =
        app.keys
            .firstOrNull { it.kind == SearchKeyKind.READING && it.form == KeyForm.STRICT }
            ?.text
            ?.value
            ?.takeUnless { it.isEmpty() }

    /** [ch] がアクセントを除いて a〜z なら、その字。厳密形は小文字なので大文字は来ない。 */
    private fun latinBase(ch: Char): Char? {
        val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).first()
        return base.takeIf { it in 'a'..'z' }
    }

    /** アクセントなどの結合文字を外した形（`éclair` → `eclair`）。英字の名前をアクセントの有無で離さないため。 */
    private fun withoutAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    private fun isHiragana(ch: Char): Boolean = ch in HIRAGANA_FIRST..HIRAGANA_LAST

    /** 五十音順で比べる形: 濁点・半濁点と小さい仮名を除き、長音を前の字の母音にする。 */
    internal fun gojuonKey(reading: String): String {
        val out = StringBuilder(reading.length)
        for (ch in reading) {
            val plain = seion(ch)
            if (plain == PROLONGED_SOUND_MARK) {
                val vowel = out.lastOrNull()?.let(VOWEL_OF::get)
                out.append(vowel ?: plain)
            } else {
                out.append(plain)
            }
        }
        return out.toString()
    }

    private fun seion(ch: Char): Char {
        SMALL_TO_NORMAL[ch]?.let { return it }
        if (!isHiragana(ch)) return ch
        // NFD で濁点・半濁点（結合文字）を外し、基底の字だけを残す（「ゔ」も「う」になる）
        return Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).first()
    }

    /** 清音の字 [ch] の行の見出し。ひらがなの符号位置は五十音の順なので、[ch] 以下で最後の行の頭。 */
    private fun rowHead(ch: Char): String = ROW_HEADS.last { it <= ch }.toString()

    private enum class Group { LATIN, KANA, OTHER }

    private class SortKey(
        val group: Group,
        val section: String,
        val primary: String,
        val secondary: String,
    )

    private class SortEntry(
        val app: IndexedApp,
        val key: SortKey,
    )

    private const val HIRAGANA_FIRST = 'ぁ'
    private const val HIRAGANA_LAST = 'ゖ'
    private const val PROLONGED_SOUND_MARK = 'ー'
    private const val ROW_HEADS = "あかさたなはまやらわ"

    private val SMALL_TO_NORMAL: Map<Char, Char> =
        "ぁぃぅぇぉっゃゅょゎゕゖ".zip("あいうえおつやゆよわかけ").toMap()

    private val VOWEL_OF: Map<Char, Char> =
        buildMap {
            listOf(
                'あ' to "あかさたなはまやらわ",
                'い' to "いきしちにひみりゐ",
                'う' to "うくすつぬふむゆる",
                'え' to "えけせてねへめれゑ",
                'お' to "おこそとのほもよろを",
            ).forEach { (vowel, chars) -> chars.forEach { put(it, vowel) } }
        }

    private val ENTRY_ORDER: Comparator<SortEntry> =
        compareBy<SortEntry> { it.key.group }
            .thenBy { it.key.primary }
            .thenBy { it.key.secondary }
            .thenBy { it.app.label }
            .thenBy { it.app.id.packageName }
            .thenBy { it.app.id.activityName }
            .thenBy { it.app.id.profileSerial }
}
