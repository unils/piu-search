// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.reading

import com.atilika.kuromoji.ipadic.Tokenizer
import jp.piu.search.core.text.katakanaToHiragana

/**
 * Kuromoji（IPADIC）による [ReadingAnalyzer]。
 *
 * 語は Kuromoji の NORMAL モードの区切りのまま返す。辞書で 1 語の複合語（「三井住友銀行」「佐川急便」）も分けない。
 * M1-06b で SEARCH モードと固有名詞だけの分け直しを評価セットにかけ、差がなかったので採らなかった（docs/decisions.md）。
 * SEARCH モードは読みの変わる語があり（「日本経済新聞」の 日本 → にっぽん）、分け直しは辞書を 2 つ読み込みメモリが倍になる。
 *
 * 辞書の読み込みは重い（JVM で 0.2 秒・40 MB ほど）ので、最初に使うときに 1 回だけ行う。
 * 起動の速さに効かないよう、画面の表示の後に裏のスレッドで [warmUp] を呼んでおく使い方を想定する
 * （docs/design/architecture.md の「索引の作成と更新」）。
 *
 * 複数のスレッドから同時に呼んでよい。Kuromoji の `Tokenizer.tokenize` は呼び出しごとに状態を作るので、
 * 1 つの辞書を共有できる。
 */
public class KuromojiReadingAnalyzer internal constructor(
    private val createTokenizer: () -> Tokenizer,
) : ReadingAnalyzer {
    /** 辞書を読み込まずに作る。読み込みは [warmUp] か最初の [analyze] で行う。 */
    public constructor() : this(::Tokenizer)

    private val lock = Any()

    @Volatile
    private var tokenizer: Tokenizer? = null

    /** 辞書を読み込み済みか。 */
    public val isLoaded: Boolean get() = tokenizer != null

    /**
     * 辞書を読み込む。読み込み済みなら何もしない。
     *
     * 読み込みが終わるまで戻らないので、UI のスレッドから呼ばない。
     * 辞書を読めないときは Kuromoji の例外（`RuntimeException`）を投げ、次の呼び出しでもう一度試す。
     */
    public fun warmUp() {
        tokenizer()
    }

    /**
     * 辞書を手放す。次に使うときに読み込み直す。
     *
     * メモリが足りなくなったとき（Android の `onTrimMemory`）に呼ぶため。
     * 解析の途中のスレッドは、手放す前の辞書で最後まで進む。
     */
    public fun release() {
        synchronized(lock) { tokenizer = null }
    }

    /**
     * 辞書を読み込んでいなければ読み込んでから、[text] を語に分ける。
     *
     * 辞書を読めないときの例外は [warmUp] と同じ。
     */
    override fun analyze(text: String): List<ReadingToken> {
        if (text.isBlank()) return emptyList()
        return tokenizer()
            .tokenize(text)
            // 空白は語の区切りでしかなく、読みの組み合わせに入れないため捨てる
            .filterNot { it.surface.isBlank() }
            .map { ReadingToken(it.surface, readingOf(it.surface, it.reading), it.position) }
    }

    private fun tokenizer(): Tokenizer =
        tokenizer ?: synchronized(lock) {
            tokenizer ?: createTokenizer().also { tokenizer = it }
        }
}

/** Kuromoji が読みを持たない語に返す値。 */
private const val UNKNOWN_READING = "*"

/** 長音符。Unicode の文字種は COMMON で、かなの判定から漏れるので名前で扱う。 */
private const val PROLONGED_SOUND_MARK = 'ー'.code

/**
 * 語の読みをひらがなで返す。読みを付けない語は null。
 *
 * - 日本語の文字（ひらがな・カタカナ・漢字）を含まない語は、辞書に読みがあっても null。
 *   英字の読みはブランド表で付け、ここと二重にしないため
 * - 辞書にない語（[UNKNOWN_READING]）でも、かなだけなら表記をそのまま読みにする（「マイナポータル」など）
 * - ひらがなと長音符だけにならない読み（半角カナの「ｶﾒﾗ」など）は null。読みの形を一つに保ち、
 *   照合の側で字の種類を確かめずに済ませるため。半角カナは呼び出す側の正規化（NFKC）で全角にしておく
 */
internal fun readingOf(
    surface: String,
    dictionaryReading: String?,
): String? {
    if (surface.codePoints().noneMatch(::isJapaneseScript)) return null
    val reading =
        dictionaryReading?.takeUnless { it == UNKNOWN_READING }
            ?: surface.takeIf { it.codePoints().allMatch(::isKanaOrProlonged) }
    return reading?.let(::katakanaToHiragana)?.takeIf { it.codePoints().allMatch(::isHiraganaOrProlonged) }
}

private fun isHiraganaOrProlonged(codePoint: Int): Boolean =
    codePoint == PROLONGED_SOUND_MARK || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA

private fun isJapaneseScript(codePoint: Int): Boolean =
    when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN -> true
        else -> false
    }

private fun isKanaOrProlonged(codePoint: Int): Boolean =
    codePoint == PROLONGED_SOUND_MARK ||
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
            else -> false
        }
