// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

/**
 * ひらがな → 標準形のローマ字の表。
 *
 * 標準形は matching.md の規則（訓令式に近い）に従う。外来語の音（ふぁ・てぃ など）は、
 * 英字の綴りで打たれやすい形（`fa`）か、IME で打つ形（`thi`）にする。
 * 「っ」「ー」は前後の字で決まるので、[RomajiConverter] の側で扱う。
 */
internal object KanaRomajiTable {
    private val singles: Map<Char, String> =
        buildMap {
            putRow("あいうえお", "a", "i", "u", "e", "o")
            putRow("かきくけこ", "ka", "ki", "ku", "ke", "ko")
            putRow("がぎぐげご", "ga", "gi", "gu", "ge", "go")
            putRow("さしすせそ", "sa", "si", "su", "se", "so")
            putRow("ざじずぜぞ", "za", "zi", "zu", "ze", "zo")
            putRow("たちつてと", "ta", "ti", "tu", "te", "to")
            // ぢ・づ は じ・ず と同じ音なので、同じ標準形にする
            putRow("だぢづでど", "da", "zi", "zu", "de", "do")
            putRow("なにぬねの", "na", "ni", "nu", "ne", "no")
            putRow("はひふへほ", "ha", "hi", "hu", "he", "ho")
            putRow("ばびぶべぼ", "ba", "bi", "bu", "be", "bo")
            putRow("ぱぴぷぺぽ", "pa", "pi", "pu", "pe", "po")
            putRow("まみむめも", "ma", "mi", "mu", "me", "mo")
            putRow("やゆよ", "ya", "yu", "yo")
            putRow("らりるれろ", "ra", "ri", "ru", "re", "ro")
            // ゐ・ゑ は今の発音（い・え）で読む。を は IME で打つ形
            putRow("わゐゑをん", "wa", "i", "e", "wo", "n")
            put('ゔ', "vu")
            // 単独の小さい仮名（拗音の組にならないもの）は、普通の仮名と同じにする
            putRow("ぁぃぅぇぉ", "a", "i", "u", "e", "o")
            putRow("ゃゅょゎ", "ya", "yu", "yo", "wa")
            putRow("ゕゖ", "ka", "ke")
        }

    // 2 字の組。キーは 1 字目と 2 字目のコードを 1 つの Int にまとめたもの
    private val digraphs: Map<Int, String> =
        buildMap {
            // い段 ＋ ゃゅょ（拗音）
            val youon =
                listOf(
                    'き' to "ky",
                    'ぎ' to "gy",
                    'し' to "sy",
                    'じ' to "zy",
                    'ち' to "ty",
                    'ぢ' to "zy",
                    'に' to "ny",
                    'ひ' to "hy",
                    'び' to "by",
                    'ぴ' to "py",
                    'み' to "my",
                    'り' to "ry",
                )
            for ((kana, consonant) in youon) {
                putPair(kana, 'ゃ', consonant + "a")
                putPair(kana, 'ゅ', consonant + "u")
                putPair(kana, 'ょ', consonant + "o")
            }
            // しぇ・じぇ・ちぇ は しゃ と同じ流儀で、y を挟む
            putPair('し', 'ぇ', "sye")
            putPair('じ', 'ぇ', "zye")
            putPair('ち', 'ぇ', "tye")
            putPair('い', 'ぇ', "ye")
            putVowels('ふ', "f")
            putPair('ふ', 'ゅ', "fyu")
            putVowels('ゔ', "v")
            putPair('ゔ', 'ゅ', "vyu")
            putVowels('つ', "ts")
            putPair('う', 'ぃ', "wi")
            putPair('う', 'ぇ', "we")
            putPair('う', 'ぉ', "wo")
            putPair('く', 'ぁ', "kwa")
            putPair('ぐ', 'ぁ', "gwa")
            // てぃ・でぃ は IME で打つ形。`ti`・`di` にすると ち・ぢ の標準形と重なる
            putPair('て', 'ぃ', "thi")
            putPair('て', 'ゅ', "thu")
            putPair('で', 'ぃ', "dhi")
            putPair('で', 'ゅ', "dhu")
            putPair('と', 'ぅ', "twu")
            putPair('ど', 'ぅ', "dwu")
        }

    /** 1 字の標準形。表にない字なら null。 */
    fun single(kana: Char): String? = singles[kana]

    /** 2 字の組（拗音・外来語の音）の標準形。組にならないなら null。 */
    fun digraph(
        first: Char,
        second: Char,
    ): String? = digraphs[key(first, second)]

    private fun key(
        first: Char,
        second: Char,
    ): Int = (first.code shl Char.SIZE_BITS) or second.code

    private fun MutableMap<Char, String>.putRow(
        kana: String,
        vararg romaji: String,
    ) {
        check(kana.length == romaji.size) { "表の行の長さが合わない: $kana" }
        kana.forEachIndexed { index, ch -> put(ch, romaji[index]) }
    }

    private fun MutableMap<Int, String>.putPair(
        first: Char,
        second: Char,
        romaji: String,
    ) {
        put(key(first, second), romaji)
    }

    /** ふぁ・ふぃ・ふぇ・ふぉ のように、子音 ＋ 小さい ぁ・ぃ・ぇ・ぉ の組を足す。ぅ は組にしない（ふぅ は ふ＋う で足りる）。 */
    private fun MutableMap<Int, String>.putVowels(
        first: Char,
        consonant: String,
    ) {
        putPair(first, 'ぁ', consonant + "a")
        putPair(first, 'ぃ', consonant + "i")
        putPair(first, 'ぇ', consonant + "e")
        putPair(first, 'ぉ', consonant + "o")
    }
}
