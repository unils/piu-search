// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * アプリ名・読み・検索語にかける正規化（matching.md の「正規化」）。
 *
 * 同じ規則を両側にかけてから比べるので、ここを変えたら索引を作り直す（索引の版を上げる）。
 */
public object TextNormalizer {
    /**
     * 厳密形。
     *
     * 1. NFKC（全角・半角や互換文字の統一）
     * 2. 言語に依らない規則で小文字にする
     * 3. カタカナをひらがなにする（ヴ → ゔ を含む）
     * 4. 空白・記号（「・」`!` `-` `&` 括弧など）・異体字セレクタを除く
     *
     * 文字・数字・結合文字は残す。絵文字は記号として、サロゲートペアごと除く。
     * 2 回かけても結果は変わらない。
     */
    public fun strict(raw: CharSequence): NormalizedText {
        var text = strictPass(raw.toString())
        // 記号を除くと、基底の文字と結合文字が隣り合い、NFKC で合成される形になることがある（"か!゙" → "が"）。
        // 2 回目の正規化で結果が変わらないように、NFKC の形になるまで繰り返す。
        // 1 回で済むのが普通で、回数の上限は念のため
        var passes = 1
        while (passes < MAX_PASSES && !Normalizer.isNormalized(text, Normalizer.Form.NFKC)) {
            text = strictPass(text)
            passes++
        }
        return NormalizedText(text)
    }

    /**
     * 緩い形。厳密形 [strict] に、打ち方の揺れを吸収する規則を足す。
     *
     * - 長音「ー」を除く（「らーめん」と「らめん」）
     * - 小さい仮名を普通の仮名にする（「ぁ」→「あ」、「っ」→「つ」、「ゃ」→「や」）
     * - 「ゔ」を「ぶ」にする
     *
     * 緩い形での一致は、同じ段階の厳密形の一致より下に並べる（照合の側で扱う）。
     * 2 回かけても結果は変わらない。
     */
    public fun loose(text: NormalizedText): NormalizedText {
        val value = text.value
        val out = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                PROLONGED_SOUND_MARK -> Unit
                VU -> out.append(BU)
                else -> out.append(SMALL_TO_NORMAL[ch] ?: ch)
            }
        }
        return NormalizedText(out.toString())
    }

    private fun strictPass(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            i += Character.charCount(cp)
            if (isKept(cp)) out.appendCodePoint(KatakanaTable.toHiragana(cp))
        }
        return out.toString()
    }

    /** 文字・数字・結合文字を残す。空白・記号・制御文字・孤立したサロゲートは除く。 */
    private fun isKept(cp: Int): Boolean {
        // 異体字セレクタは見た目だけを選ぶので、除いて同じ字として比べる（絵文字の後の U+FE0F、漢字の IVS）。
        // 一般カテゴリは結合文字（Mn）なので、ブロックで判定する
        val block = Character.UnicodeBlock.of(cp)
        if (block == Character.UnicodeBlock.VARIATION_SELECTORS ||
            block == Character.UnicodeBlock.VARIATION_SELECTORS_SUPPLEMENT
        ) {
            return false
        }
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER,
            Character.LOWERCASE_LETTER,
            Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER,
            Character.OTHER_LETTER,
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER,
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER,
            -> true

            // 囲み文字（U+20E3 のキーキャップなど）は記号の飾りなので除く
            else -> false
        }
    }

    private const val MAX_PASSES = 4
    private const val PROLONGED_SOUND_MARK = 'ー'
    private const val VU = 'ゔ'
    private const val BU = 'ぶ'

    // 小さい仮名と普通の仮名の対。対応は字の名前（SMALL の有無）で決まるが、並びに規則がない（ゕ・ゖ）ので表で持つ
    private val SMALL_TO_NORMAL: Map<Char, Char> =
        mapOf(
            'ぁ' to 'あ',
            'ぃ' to 'い',
            'ぅ' to 'う',
            'ぇ' to 'え',
            'ぉ' to 'お',
            'っ' to 'つ',
            'ゃ' to 'や',
            'ゅ' to 'ゆ',
            'ょ' to 'よ',
            'ゎ' to 'わ',
            'ゕ' to 'か',
            'ゖ' to 'け',
        )
}

/**
 * カタカナをひらがなにする（ヴ → ゔ を含む）。対応するひらがながない字（「ヷ」「ヿ」、半角カナ）と、かな以外の字はそのまま残す。
 *
 * 正規化（[TextNormalizer.strict]）と形態素解析器の読み（`core:reading`）で同じ変換を使い、両者の食い違いを防ぐために公開する。
 * 半角カナもひらがなにしたいときは、先に NFKC をかける。
 */
public fun katakanaToHiragana(text: CharSequence): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val cp = Character.codePointAt(text, i)
        out.appendCodePoint(KatakanaTable.toHiragana(cp))
        i += Character.charCount(cp)
    }
    return out.toString()
}

/** カタカナ → ひらがなの対応。 */
internal object KatakanaTable {
    // ひらがなとカタカナは BMP で同じ並びに 0x60 離れて置かれている。カタカナの 0x60 前がひらがなの文字のときだけ対応させる。
    // ヷ〜ヺ の 0x60 前は未割り当てか結合文字なので外れる。ヿ（コト）の 0x60 前は別の字 ゟ（より）なので、字で除く。
    // 半角カナの 0x60 前は全角の英数字なので外れる。
    // 字の名前（Character.getName）で確かめる方法は、名前の表の読み込みに初回 100 ms ほどかかるので使わない
    private const val KANA_OFFSET = 0x60
    private const val KATAKANA_DIGRAPH_KOTO = 0x30FF

    /** [cp] がひらがなを持つカタカナならそのひらがな、そうでなければ [cp] のまま。 */
    fun toHiragana(cp: Int): Int {
        if (cp > Character.MAX_VALUE.code || Character.UnicodeScript.of(cp) != Character.UnicodeScript.KATAKANA) return cp
        if (cp == KATAKANA_DIGRAPH_KOTO) return cp
        val candidate = cp - KANA_OFFSET
        val isHiragana =
            Character.UnicodeScript.of(candidate) == Character.UnicodeScript.HIRAGANA && Character.isLetter(candidate)
        return if (isHiragana) candidate else cp
    }
}
