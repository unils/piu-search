// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

import java.text.Normalizer

/** 入力の終わりの扱い。 */
public enum class RomajiMode {
    /**
     * 入力は完結している。索引のキーと、検索語の完全一致に使う。
     */
    COMPLETE,

    /**
     * 入力の途中かもしれない。検索語の前方一致に使う。
     *
     * 末尾の英字が、続きによって別の標準形になる綴りの途中（`sh` は `shi` → `si` にも `sha` → `sya` にもなる）なら、
     * どの続きでも共通の部分だけを出す（`sh` → `s`）。こうすると、検索語を 1 字ずつ打つどの時点でも、
     * 出力が完成した語の標準形の前方に一致する。共通の部分がなければ空になる（`c`・`f`・`j` で終わる場合）。
     */
    PREFIX,
}

/**
 * かなと英字 → 標準形のローマ字（matching.md の「ローマ字」）。
 *
 * 綴りの流儀（ヘボン式・訓令式・IME の入力）の違いを吸収するため、両側を標準形にしてから比べる。
 * 標準形は訓令式に近い: し→`si`、ち→`ti`、つ→`tu`、ふ→`hu`、じ・ぢ→`zi`、しゃ→`sya`、ん→`n`、
 * っ＋子音→子音を重ねる、ー→除く。
 *
 * 入力は [TextNormalizer.strict] を通した文字列（小文字・ひらがな）を想定する。かなと英字が混ざっていてよい
 * （IME の変換前の「のりk」→ `norik`、「んhk」→ `nhk`）。漢字・数字・ほかの文字はそのまま残す。
 */
public object RomajiConverter {
    /** [text] を標準形のローマ字にする。[mode] は入力の終わりの扱い。 */
    public fun toStandard(
        text: NormalizedText,
        mode: RomajiMode = RomajiMode.COMPLETE,
    ): NormalizedText {
        val input = stripLatinDiacritics(text.value)
        val out = Emitter(StringBuilder(input.length * 2))
        val partial = mode == RomajiMode.PREFIX
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            i =
                when {
                    ch == SOKUON -> {
                        out.sokuon = true
                        i + 1
                    }

                    ch == PROLONGED_SOUND_MARK -> {
                        i + 1
                    }

                    ch in 'a'..'z' -> {
                        latin(input, i, partial, out)
                    }

                    else -> {
                        kana(input, i, out)
                    }
                }
        }
        return NormalizedText(out.toString())
    }

    /**
     * 標準形のローマ字の緩い形。
     *
     * 同じ文字の連続を 1 つにし（`nn`→`n`、`kk`→`k`）、`ou`・`oo` → `o`、`uu` → `u` にする
     * （「とうきょう」の `toukyou` と `tokyo`、「らーめん」の `ramen` と `raamen`）。
     * 左から 1 回なめるだけで決まるので、前方一致の関係を保つ（検索語の緩い形は、続きを打った後の緩い形の前方にある）。
     * 2 回かけても結果は変わらない。
     */
    public fun loose(romaji: NormalizedText): NormalizedText {
        val value = romaji.value
        val out = StringBuilder(value.length)
        var last = -1
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            val absorbed = cp == last || (cp == 'u'.code && last == 'o'.code)
            if (!absorbed) {
                out.appendCodePoint(cp)
                last = cp
            }
        }
        return NormalizedText(out.toString())
    }

    /** かな 1 つ（拗音などは 2 字）を変換し、次の位置を返す。表にない字はそのまま残す。 */
    private fun kana(
        input: String,
        i: Int,
        out: Emitter,
    ): Int {
        val ch = input[i]
        if (i + 1 < input.length) {
            val pair = KanaRomajiTable.digraph(ch, input[i + 1])
            if (pair != null) {
                out.emit(pair)
                return i + 2
            }
        }
        val single = KanaRomajiTable.single(ch)
        if (single != null) {
            out.emit(single)
        } else {
            out.passThrough(ch)
        }
        return i + 1
    }

    /**
     * 英字の綴り 1 つを標準形にし、次の位置を返す。
     *
     * 先読みは 2 字まで。[partial] のとき、先読みが入力の終わりで決まらない綴りは、続きのどれとも共通の部分だけを出す。
     */
    private fun latin(
        s: String,
        i: Int,
        partial: Boolean,
        out: Emitter,
    ): Int {
        val n1 = s.charOrEnd(i + 1)
        val n2 = s.charOrEnd(i + 2)
        return when (s[i]) {
            // shi → si、sha → sya、she → sye
            's' -> {
                when {
                    n1 != 'h' -> out.emitAndAdvance("s", i, 1)
                    n2 == 'i' -> out.emitAndAdvance("si", i, 3)
                    n2.isNonIVowel() -> out.emitAndAdvance("sy", n2, i, 3)
                    n2 == END && partial -> out.emitAndAdvance("s", i, 2)
                    else -> out.emitAndAdvance("sh", i, 2)
                }
            }

            // chi → ti、cha → tya。IME の流儀の ca・cu・co（か行）、ci・ce（さ行）、cya（ちゃ）も受ける
            'c' -> {
                when {
                    n1 == 'h' -> {
                        when {
                            n2 == 'i' -> out.emitAndAdvance("ti", i, 3)
                            n2.isNonIVowel() -> out.emitAndAdvance("ty", n2, i, 3)
                            n2 == END && partial -> i + 2
                            else -> out.emitAndAdvance("ch", i, 2)
                        }
                    }

                    // matcha・maccha（まっちゃ）。子音を重ねる側の c は t にそろえる
                    n1 == 'c' && n2 == 'h' -> {
                        out.emitAndAdvance("t", i, 1)
                    }

                    n1 == 'c' && n2 == END && partial -> {
                        i + 1
                    }

                    n1 == 'a' || n1 == 'u' || n1 == 'o' -> {
                        out.emitAndAdvance("k", i, 1)
                    }

                    n1 == 'i' || n1 == 'e' -> {
                        out.emitAndAdvance("s", i, 1)
                    }

                    n1 == 'y' -> {
                        out.emitAndAdvance("t", i, 1)
                    }

                    n1 == END && partial -> {
                        i + 1
                    }

                    else -> {
                        out.emitAndAdvance("c", i, 1)
                    }
                }
            }

            // tsu → tu
            't' -> {
                when {
                    n1 == 's' && n2 == 'u' -> out.emitAndAdvance("tu", i, 3)
                    n1 == 's' && n2 == END && partial -> out.emitAndAdvance("t", i, 2)
                    else -> out.emitAndAdvance("t", i, 1)
                }
            }

            // fu → hu。fa・fi・fe・fo は ふぁ 行の標準形のまま
            'f' -> {
                when {
                    n1 == 'u' -> out.emitAndAdvance("hu", i, 2)
                    n1 == END && partial -> i + 1
                    else -> out.emitAndAdvance("f", i, 1)
                }
            }

            // ji → zi、ja → zya、jya → zya
            'j' -> {
                when {
                    n1 == 'i' -> out.emitAndAdvance("zi", i, 2)
                    n1.isNonIVowel() -> out.emitAndAdvance("zy", n1, i, 2)
                    n1 == 'y' -> out.emitAndAdvance("z", i, 1)
                    n1 == END && partial -> i + 1
                    else -> out.emitAndAdvance("j", i, 1)
                }
            }

            'n' -> {
                nasal(s, i, out)
            }

            else -> {
                out.emitAndAdvance(s[i], i)
            }
        }
    }

    /**
     * ん の綴りの揺れ。標準形の ん は `n`。
     *
     * - `nnn`: IME の流儀（`nn` で ん、続けて な 行）。`nn` を ん として `n` を出す（`annnai` → `annai`）
     * - `nn` の後が母音か `y`: ヘボン式の ん＋な行（`annai`・`onna`）。`n` を 1 つ出し、残りを な行として読む。
     *   IME の流儀の ん＋母音（`kanni` = かんい）はここで `kanni` になり、厳密形では一致しない（緩い形では一致する）
     * - `nn` の後が子音か終わり: IME の流儀の ん（`shinnkannsenn` → `sinkansen`）
     */
    private fun nasal(
        s: String,
        i: Int,
        out: Emitter,
    ): Int {
        val run =
            when {
                s.charOrEnd(i + 1) != 'n' -> 1
                s.charOrEnd(i + 2) != 'n' -> 2
                else -> 3
            }
        out.emit("n")
        return when (run) {
            1 -> {
                i + 1
            }

            2 -> {
                val after = s.charOrEnd(i + 2)
                if (after.isVowel() || after == 'y') i + 1 else i + 2
            }

            else -> {
                i + 2
            }
        }
    }

    /**
     * アクセント付きのラテン文字を基の字にする（`tōkyō` → `tokyo`、`pokémon` → `pokemon`）。
     *
     * 正規化の規則（NFKC）ではアクセントが残るが、ローマ字として比べるときは区別しない。
     * かなの濁点まで外さないよう、ラテン文字だけに限る。
     */
    private fun stripLatinDiacritics(value: String): String {
        if (value.all { it.code < ASCII_LIMIT || CharKind.of(it.code) != CharKind.LATIN }) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            if (cp >= ASCII_LIMIT && CharKind.of(cp) == CharKind.LATIN) {
                val base = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)[0]
                out.append(if (base in 'a'..'z') base else Character.toChars(cp).concatToString())
            } else {
                out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    private const val SOKUON = 'っ'
    private const val PROLONGED_SOUND_MARK = 'ー'
    private const val END = '\u0000'
    private const val ASCII_LIMIT = 0x80

    private fun String.charOrEnd(index: Int): Char = if (index < length) this[index] else END

    private fun Char.isVowel(): Boolean = this == 'a' || this == 'i' || this == 'u' || this == 'e' || this == 'o'

    private fun Char.isNonIVowel(): Boolean = this == 'a' || this == 'u' || this == 'e' || this == 'o'

    /** 出力の組み立て。促音「っ」を、次に出る子音を重ねて表す。 */
    private class Emitter(
        private val out: StringBuilder,
    ) {
        /** 直前に「っ」があり、次の子音を重ねる。 */
        var sokuon = false

        fun emit(piece: String) {
            if (piece.isEmpty()) return
            val first = piece[0]
            // 「っ」の後が子音でなければ（「っあ」など）何も足さない
            if (sokuon && first in 'a'..'z' && !first.isVowel()) out.append(first)
            sokuon = false
            out.append(piece)
        }

        fun emitAndAdvance(
            piece: String,
            i: Int,
            length: Int,
        ): Int {
            emit(piece)
            return i + length
        }

        fun emitAndAdvance(
            piece: String,
            vowel: Char,
            i: Int,
            length: Int,
        ): Int {
            emit(piece)
            out.append(vowel)
            return i + length
        }

        fun emitAndAdvance(
            ch: Char,
            i: Int,
        ): Int {
            if (sokuon && !ch.isVowel()) out.append(ch)
            sokuon = false
            out.append(ch)
            return i + 1
        }

        /** 漢字・数字など、ローマ字にしない字。「っ」の効果はここで切れる。 */
        fun passThrough(ch: Char) {
            sokuon = false
            out.append(ch)
        }

        override fun toString(): String = out.toString()
    }
}
