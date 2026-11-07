// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

class TextNormalizerTest {
    private fun strict(raw: String) = TextNormalizer.strict(raw).value

    private fun loose(raw: String) = TextNormalizer.loose(TextNormalizer.strict(raw)).value

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
# 入力                 | 厳密形
Yahoo!天気             | yahoo天気
NHKプラス              | nhkぷらす
Google マップ          | googleまっぷ
ジョルダン乗換案内     | じょるだん乗換案内
三井住友銀行           | 三井住友銀行
# 全角・半角（NFKC）
ＹＡＨＯＯ！           | yahoo
ﾎｯﾄﾍﾟｯﾊﾟｰｸﾞﾙﾒ          | ほっとぺっぱーぐるめ
ｶﾞｲﾄﾞ                  | がいど
１２３                 | 123
# カタカナ → ひらがな
ヴァイオリン           | ゔぁいおりん
ヵヶ                   | ゕゖ
ヽヾ                   | ゝゞ
# 対応するひらがなのないカタカナは残す（ヿ は NFKC で コト になる）
ヷヿ                   | ヷこと
# 長音は残し、中黒・記号・空白は除く
ラーメン               | らーめん
ポケモン・ゴー         | ぽけもんごー
'AT&T (US) - 1'        | attus1
'a b　c'               | abc
〜メモ帳〜             | めも帳
# 互換文字
㈱ヤフー               | 株やふー
①ｱﾌﾟﾘ                  | 1あぷり
Ⅻ                      | xii
# 単独の濁点は結合文字として前の字と合わさる
か゛                   | が
# アクセントは厳密形では残す（ローマ字の段で外す）
Pokémon                | pokémon
""",
    )
    fun `厳密形`(
        raw: String,
        expected: String,
    ) {
        assertEquals(expected, strict(raw))
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
# 入力         | 緩い形
らーめん       | らめん
らめん         | らめん
ぁぃぅぇぉ     | あいうえお
ほっと         | ほつと
しゃしゅしょ   | しやしゆしよ
ゎゕゖ         | わかけ
ヴァイオリン   | ぶあいおりん
ばいおりん     | ばいおりん
Yahoo!天気     | yahoo天気
""",
    )
    fun `緩い形`(
        raw: String,
        expected: String,
    ) {
        assertEquals(expected, loose(raw))
    }

    @Test
    fun `緩い形では「らーめん」と「らめん」が一致する`() {
        assertEquals(loose("ラーメン"), loose("らめん"))
    }

    @Test
    fun `空の入力と記号だけの入力は空になる`() {
        for (raw in listOf("", " ", "　", "!?・-&()[]{}", "​‍", "️", "😀‍💻")) {
            assertEquals("", strict(raw), "入力: $raw")
            assertEquals("", loose(raw), "入力: $raw")
        }
    }

    @Test
    fun `補助面の漢字はサロゲートペアのまま残る`() {
        assertEquals("𠮷野家", strict("𠮷野家"))
    }

    @Test
    fun `絵文字は異体字セレクタや結合子ごと除き、片方のサロゲートを残さない`() {
        // ❤️（U+2764 U+FE0F）、👨‍💻（ZWJ の連結）、1️⃣（キーキャップ）、肌の色の修飾
        assertEquals("天気1", strict("❤️天気👨‍💻1️⃣👍🏽"))
    }

    @Test
    fun `漢字の異体字セレクタを除いて同じ字として比べる`() {
        // 葛 ＋ IVS（U+E0100）
        assertEquals("葛飾", strict("葛󠄀飾"))
    }

    @Test
    fun `孤立したサロゲートは除く`() {
        assertEquals("ab", strict("a\uD800b\uDC00"))
    }

    @Test
    fun `記号を除いた後に合成できる結合文字も、2 回目で変わらない形にする`() {
        // か と結合文字の濁点の間に記号がある。除くと合成されて が になる
        val once = strict("カ!゙")
        assertEquals("が", once)
        assertEquals(once, strict(once))
    }

    @Test
    fun `性質 - 2 回かけても変わらない、壊れたサロゲートを出さない`() {
        val random = Random(SEED)
        repeat(PROPERTY_CASES) {
            val raw = randomText(random)
            val once = TextNormalizer.strict(raw)
            assertEquals(once, TextNormalizer.strict(once.value), "厳密形: ${dump(raw)}")
            val looseOnce = TextNormalizer.loose(once)
            assertEquals(looseOnce, TextNormalizer.loose(looseOnce), "緩い形: ${dump(raw)}")
            assertTrue(isWellFormed(once.value), "厳密形のサロゲート: ${dump(raw)}")
            assertTrue(isWellFormed(looseOnce.value), "緩い形のサロゲート: ${dump(raw)}")
        }
    }

    @Test
    fun `1 万字の入力を十分に速く正規化する`() {
        val random = Random(SEED)
        val raw = buildString { while (length < LONG_INPUT) append(randomText(random)) }.take(LONG_INPUT)
        val strictMs = medianMillis { TextNormalizer.strict(raw) }
        val normalized = TextNormalizer.strict(raw)
        val looseMs = medianMillis { TextNormalizer.loose(normalized) }
        val romajiMs = medianMillis { RomajiConverter.toStandard(normalized, RomajiMode.PREFIX) }
        println("1 万字: 厳密形 %.3f ms、緩い形 %.3f ms、ローマ字 %.3f ms（中央値）".format(strictMs, looseMs, romajiMs))
        // 検索語は数十字なので、1 万字で 1 コマ（16 ms）に収まれば十分。CI の遅い機械でも落ちないよう、上限はゆるく置く
        assertTrue(strictMs < LIMIT_MS, "厳密形 $strictMs ms")
        assertTrue(looseMs < LIMIT_MS, "緩い形 $looseMs ms")
        assertTrue(romajiMs < LIMIT_MS, "ローマ字 $romajiMs ms")
    }

    private fun medianMillis(block: () -> Unit): Double {
        repeat(WARMUP) { block() }
        val times =
            List(MEASURE) {
                val start = System.nanoTime()
                block()
                (System.nanoTime() - start) / NANOS_PER_MILLI
            }
        return times.sorted()[times.size / 2]
    }

    private fun isWellFormed(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                    i += 2
                }

                Character.isLowSurrogate(ch) -> {
                    return false
                }

                else -> {
                    i++
                }
            }
        }
        return true
    }

    private fun dump(text: String): String = text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }

    companion object {
        private const val SEED = 20261027
        private const val PROPERTY_CASES = 20_000
        private const val LONG_INPUT = 10_000
        private const val WARMUP = 20
        private const val MEASURE = 31
        private const val LIMIT_MS = 50.0
        private const val NANOS_PER_MILLI = 1_000_000.0

        // 性質のテストの材料。正規化で形が変わりやすい字（全角・半角・互換文字・結合文字・サロゲート）を多めに混ぜる
        private val POOL: List<String> =
            listOf(
                "a",
                "Z",
                "1",
                " ",
                "!",
                "-",
                "&",
                "・",
                "ー",
                "〜",
                "'",
                "　",
                "あ",
                "っ",
                "ゃ",
                "ん",
                "ゔ",
                "ア",
                "ッ",
                "ャ",
                "ヴ",
                "ヵ",
                "ヷ",
                "ヿ",
                "ヽ",
                "ｱ",
                "ｯ",
                "ﾞ",
                "ﾟ",
                "ｰ",
                "Ａ",
                "１",
                "！",
                "㈱",
                "①",
                "Ⅻ",
                "ﬁ",
                "İ",
                "ß",
                "é",
                "é",
                "゙",
                "゚",
                "゛",
                "́",
                "⃣",
                "️",
                "‍",
                "󠄀",
                "漢",
                "々",
                "𠮷",
                "😀",
                "🏽",
                "\uD800",
                "\uDC00",
            )

        fun randomText(random: Random): String =
            buildString {
                repeat(random.nextInt(0, 24)) { append(POOL[random.nextInt(POOL.size)]) }
            }
    }
}
