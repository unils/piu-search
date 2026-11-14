// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.text

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

class RomajiConverterTest {
    @Test
    fun `接頭辞の長さは各語を変換した結果と同じ（促音、拗音、先読み、補助漢字も含む）`() {
        val random = Random(32)
        val pieces =
            listOf(
                "あ",
                "き",
                "ゃ",
                "っ",
                "ー",
                "n",
                "s",
                "h",
                "i",
                "c",
                "t",
                "a",
                "f",
                "u",
                "é",
                "𠮷",
                "1",
                "ふ",
                "ぇ",
                "し",
                "ち",
                "て",
                "ぃ",
                "じ",
            ) +
                ('a'..'z').map { it.toString() }
        val words =
            listOf("", "っーーnna", "cccha", "nnnna", "きゃっしゅ", "é𠮷っ") +
                List(500) { buildString { repeat(64) { append(pieces.random(random)) } } }
        for (word in words) {
            val text = TextNormalizer.strict(word)
            val expected =
                IntArray(text.value.codePointCount(0, text.value.length)) { i ->
                    val end = text.value.offsetByCodePoints(0, i + 1)
                    RomajiConverter.toStandard(NormalizedText(text.value.substring(0, end))).value.length
                }
            assertArrayEquals(expected, RomajiConverter.prefixLengths(text), word)
        }
    }

    private fun romaji(
        raw: String,
        mode: RomajiMode = RomajiMode.COMPLETE,
    ) = RomajiConverter.toStandard(TextNormalizer.strict(raw), mode).value

    private fun looseRomaji(
        raw: String,
        mode: RomajiMode = RomajiMode.COMPLETE,
    ) = RomajiConverter.loose(RomajiConverter.toStandard(TextNormalizer.strict(raw), mode)).value

    /** 比べ方。matching.md の段階のうち、ローマ字のキーで起きるもの。 */
    enum class Relation { EXACT, PREFIX }

    /** どの形で一致するか。 */
    enum class Form { STRICT, LOOSE }

    /**
     * matching.md の「確かめる例」と、「ローマ字」「検索キーの生成」の例。
     *
     * キーは索引の側（読みか名前。完結した入力）、検索語は打っている途中の入力として変換して比べる。
     * 緩い形の行は、厳密形では一致しないことも確かめる（緩い形を同じ段階の下に並べるため、どちらで当たるかが順位に効く）。
     */
    @ParameterizedTest(name = "{0} ↔ {1}（{2}・{3}）")
    @CsvSource(
        delimiter = '|',
        textBlock = """
# 検索語     | キー（読みか名前）       | 比べ方 | 形
norikae      | のりかえ                 | EXACT  | STRICT
norikae      | のりかえあんない         | PREFIX | STRICT
norikaeannai | のりかえあんない         | EXACT  | STRICT
norikaeanai  | のりかえあんない         | EXACT  | LOOSE
のりk        | のりかえあんない         | PREFIX | STRICT
のりか       | のりかえあんない         | PREFIX | STRICT
shinkansen   | しんかんせん             | EXACT  | STRICT
sinkansen    | しんかんせん             | EXACT  | STRICT
tokyo        | とうきょう               | EXACT  | LOOSE
toukyou      | とうきょう               | EXACT  | STRICT
ramen        | らーめん                 | EXACT  | STRICT
raamen       | らーめん                 | EXACT  | LOOSE
fuji         | ふじ                     | EXACT  | STRICT
huzi         | ふじ                     | EXACT  | STRICT
hot          | ほっとぺっぱーぐるめ     | PREFIX | STRICT
hott         | ほっとぺっぱーぐるめ     | PREFIX | STRICT
ほt          | ほっとぺっぱーぐるめ     | PREFIX | STRICT
ほっt        | ほっとぺっぱーぐるめ     | PREFIX | STRICT
hotpepper    | hotpepper                | EXACT  | STRICT
hotpe        | hotpepper                | PREFIX | STRICT
nhk          | NHKプラス                | PREFIX | STRICT
んhk         | NHKプラス                | PREFIX | STRICT
nhkpurasu    | NHKプラス                | EXACT  | STRICT
yafu         | やふー                   | EXACT  | STRICT
yahu         | やふーてんき             | PREFIX | STRICT
yahoo        | Yahoo!天気               | PREFIX | STRICT
""",
    )
    fun `確かめる例`(
        query: String,
        key: String,
        relation: Relation,
        form: Form,
    ) {
        fun matches(
            q: String,
            k: String,
        ) = if (relation == Relation.EXACT) q == k else k.startsWith(q)

        val queryMode = if (relation == Relation.EXACT) RomajiMode.COMPLETE else RomajiMode.PREFIX
        val strictQuery = romaji(query, queryMode)
        val strictKey = romaji(key)
        val looseQuery = looseRomaji(query, queryMode)
        val looseKey = looseRomaji(key)
        when (form) {
            Form.STRICT -> {
                assertTrue(matches(strictQuery, strictKey), "厳密形: $strictQuery ↔ $strictKey")
            }

            Form.LOOSE -> {
                assertTrue(!matches(strictQuery, strictKey), "厳密形では一致しないはず: $strictQuery ↔ $strictKey")
                assertTrue(matches(looseQuery, looseKey), "緩い形: $looseQuery ↔ $looseKey")
            }
        }
        // 厳密形で一致するなら、緩い形でも一致する
        assertTrue(matches(looseQuery, looseKey), "緩い形: $looseQuery ↔ $looseKey")
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
# かな                     | 標準形
あいうえお                 | aiueo
しちつふじぢづ             | sitituhuzizizu
しゃちゃじゃぢゃ           | syatyazyazya
きゃぎゅにょひゃびゅぴょみゃりょ | kyagyunyohyabyupyomyaryo
ん                         | n
しんかんせん               | sinkansen
きっぷ                     | kippu
まっちゃ                   | mattya
ほっと                     | hotto
# 「っ」の後が子音でなければ何も出さない
あっ                       | a
っあ                       | a
らーめん                   | ramen
をゐゑ                     | woie
ゔぁいおりん               | vaiorin
ふぁいる                   | fairu
ふぃふぇふぉふゅ           | fifefofyu
てぃっしゅ                 | thissyu
でぃずにー                 | dhizuni
でゅとぅどぅ               | dhutwudwu
しぇちぇじぇいぇ           | syetyezyeye
うぃうぇうぉ               | wiwewo
つぁつぃつぇつぉ           | tsatsitsetso
くぁぐぁ                   | kwagwa
# 組にならない小さい仮名は普通の仮名と同じ
きぇぁゎゕゖ               | kieawakake
# 漢字・数字はそのまま
乗換あんない2              | 乗換annai2
""",
    )
    fun `かなから標準形`(
        kana: String,
        expected: String,
    ) {
        assertEquals(expected, romaji(kana))
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
# 英字             | 標準形
# ヘボン式
shinkansen         | sinkansen
chizu              | tizu
tsukuba            | tukuba
fujisan            | huzisan
jiyuu              | ziyuu
shashin            | syasin
chanoyu            | tyanoyu
jamu               | zyamu
matcha             | mattya
annai              | annai
onna               | onna
# 訓令式（そのまま）
sinkansen          | sinkansen
tizu               | tizu
tukuba             | tukuba
huzisan            | huzisan
syasin             | syasin
# IME の入力の流儀
shinnkannsenn      | sinkansen
annnai             | annai
konnnichiha        | konnitiha
jyamu              | zyamu
cya                | tya
maccha             | mattya
camera             | kamera
cocoa              | kokoa
cinema             | sinema
# アクセント付きの字は基の字にする
Tōkyō              | tokyo
Pokémon            | pokemon
# 綴りの規則に当たらない英語の綴りは変えない
nhk                | nhk
push               | push
chrome             | chrome
jr                 | jr
""",
    )
    fun `英字から標準形`(
        latin: String,
        expected: String,
    ) {
        assertEquals(expected, romaji(latin))
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
# 打っている途中      | 前方一致に使う形
sh                    | s
shinkans              | sinkans
ch                    | ''
c                     | ''
ts                    | t
f                     | ''
j                     | ''
shinn                 | sin
nn                    | n
# 途中でない綴りは完結した入力と同じ
nhk                   | nhk
のりk                 | norik
んhk                  | nhk
ほっ                  | ho
""",
    )
    fun `入力の途中の英字`(
        query: String,
        expected: String,
    ) {
        assertEquals(expected, romaji(query, RomajiMode.PREFIX))
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
# 標準形           | 緩い形
toukyou            | tokyo
tokyo              | tokyo
raamen             | ramen
norikaeannai       | norikaeanai
hottopeppagurume   | hotopepagurume
yahoo              | yaho
kuuki              | kuki
ouou               | o
""",
    )
    fun `ローマ字の緩い形`(
        romaji: String,
        expected: String,
    ) {
        assertEquals(expected, RomajiConverter.loose(NormalizedText(romaji)).value)
    }

    @Test
    fun `空の入力は空になる`() {
        assertEquals("", romaji(""))
        assertEquals("", romaji("", RomajiMode.PREFIX))
        assertEquals("", RomajiConverter.loose(NormalizedText("")).value)
    }

    @Test
    fun `サロゲートペアを壊さない`() {
        assertEquals("𠮷noya", romaji("𠮷のや"))
        assertEquals("𠮷", RomajiConverter.loose(NormalizedText("𠮷𠮷")).value)
    }

    @Test
    fun `性質 - 打っている途中の形は、続きを打った後の形の前方にある`() {
        // IME で打つと 1 字ずつ伸びる。どの時点の検索語でも、完成した語のキーに前方一致しないと結果が途切れる。
        // かなの拗音（し → しゃ）は IME が一度に確定するので、小さい仮名の直前で切る場合は除く
        val random = Random(SEED)
        repeat(PROPERTY_CASES) {
            val full = buildString { repeat(random.nextInt(1, 16)) { append(ALPHABET[random.nextInt(ALPHABET.size)]) } }
            val complete = RomajiConverter.toStandard(NormalizedText(full), RomajiMode.COMPLETE)
            val completeLoose = RomajiConverter.loose(complete).value
            for (end in 0..full.length) {
                if (end < full.length && full[end] in SMALL_KANA) continue
                val partial = RomajiConverter.toStandard(NormalizedText(full.substring(0, end)), RomajiMode.PREFIX)
                assertTrue(complete.value.startsWith(partial.value), "$full の $end 字目まで: ${partial.value} ↔ ${complete.value}")
                val partialLoose = RomajiConverter.loose(partial).value
                assertTrue(completeLoose.startsWith(partialLoose), "緩い形 $full の $end 字目まで: $partialLoose ↔ $completeLoose")
            }
        }
    }

    @Test
    fun `性質 - 緩い形は 2 回かけても変わらない`() {
        // 標準形への変換そのものは 2 回かけると変わることがある（「んん」→ nn → n）。索引も検索語も 1 回だけかける
        val random = Random(SEED)
        repeat(PROPERTY_CASES) {
            val text = buildString { repeat(random.nextInt(0, 16)) { append(ALPHABET[random.nextInt(ALPHABET.size)]) } }
            for (mode in RomajiMode.entries) {
                val loose = RomajiConverter.loose(RomajiConverter.toStandard(NormalizedText(text), mode))
                assertEquals(loose, RomajiConverter.loose(loose), "$text（$mode）")
            }
        }
    }

    companion object {
        private const val SEED = 20261027
        private const val PROPERTY_CASES = 20_000
        private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎ"

        // 綴りの規則に関わる字を多めに入れる
        private val ALPHABET: List<Char> =
            "aiueoknstchfjmyrwzgbdpvxqlsshhcnnn".toList() +
                "あいうかきしちつてとなにのはひふみやゆよらりわをんっーがじぢでどぱゔ".toList() +
                SMALL_KANA.toList() + listOf('漢', '1')
    }
}
