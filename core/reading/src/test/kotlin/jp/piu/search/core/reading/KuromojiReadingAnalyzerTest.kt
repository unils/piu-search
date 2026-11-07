// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.reading

import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KuromojiReadingAnalyzerTest {
    /**
     * docs/research/reading-sources.md の 53 語の試しのうち、正しかったもの。
     * 期待値は語ごとの読みをつないだもので、読みのない語（英字・記号）は表記のまま入れる。空白の語は入らない。
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '\t',
        textBlock = """
乗換案内	のりかえあんない
乗り換え案内	のりかえあんない
ジョルダン乗換案内	じょるだんのりかえあんない
Yahoo!乗換案内	Yahoo!のりかえあんない
地図	ちず
天気	てんき
Yahoo!天気	Yahoo!てんき
電卓	でんたく
時計	とけい
設定	せってい
楽天市場	らくてんしじょう
ヤフオク!	やふおく!
メルカリ	めるかり
三井住友銀行	みついすみともぎんこう
マイナポータル	まいなぽーたる
日経電子版	にっけいでんしばん
LINEマンガ	LINEまんが
楽天ペイ	らくてんぺい
d払い	dはらい
モバイルSuica	もばいるSuica
NHKプラス	NHKぷらす
家計簿	かけいぼ
お薬手帳	おくすりてちょう
連絡帳	れんらくちょう
電話	でんわ
写真	しゃしん
防災速報	ぼうさいそくほう
特務機関NERV防災	とくむきかんNERVぼうさい
ゆうちょ銀行	ゆうちょぎんこう
三菱UFJ銀行	みつびしUFJぎんこう
みずほ銀行	みずほぎんこう
日本経済新聞	にほんけいざいしんぶん
乗換NAVITIME	のりかえNAVITIME
駅すぱあと	えきすぱあと
食べログ	たべろぐ
出前館	でまえかん
青空文庫	あおぞらぶんこ
佐川急便	さがわきゅうびん
郵便局	ゆうびんきょく
市役所	しやくしょ
交通系IC	こうつうけいIC
生活	せいかつ
今日	きょう
雨雲レーダー	あまぐもれーだー
文字起こし	もじおこし
翻訳	ほんやく
辞書	じしょ
大辞林	だいじりん
ニコニコ動画	にこにこどうが
乗換案内 時刻表	のりかえあんないじこくひょう
""",
    )
    fun `試しで正しかった語の読み`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, joinedReading(text))
    }

    /**
     * 試しで誤りか揺れがあった語。今の挙動を固定し、辞書や設定を変えて挙動が変わったら気づけるようにする。
     * 直すのは読みの組み合わせ（M1-04）・評価セット・学習の側で、ここで期待値を正しい読みに変えない。
     */
    @ParameterizedTest(name = "既知の弱点: {0} → {1}（{2}）")
    @CsvSource(
        delimiter = '\t',
        textBlock = """
ゆうちょ通帳アプリ	ゆうちょかよいちょうあぷり	「通帳」を「かよいちょう」と読む。「つうちょう」で引けない
日本郵便	にっぽんゆうびん	「にほん」と打つ人もいる
明日の天気	あしたのてんき	「あす」と打つ人もいる
""",
    )
    fun `既知の弱点`(
        text: String,
        current: String,
        problem: String,
    ) {
        assertEquals(current, joinedReading(text), problem)
    }

    /** 複合語を区切らない（M1-06b の決定）。期待値は「表記/読み」を空白でつないだもの。 */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '\t',
        textBlock = """
三井住友銀行	三井住友銀行/みついすみともぎんこう
佐川急便	佐川急便/さがわきゅうびん
日本経済新聞	日本経済新聞/にほんけいざいしんぶん
ホットペッパーグルメ	ホットペッパーグルメ/ほっとぺっぱーぐるめ
""",
    )
    fun `辞書で 1 語の複合語は区切らない`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, analyzer.analyze(text).joinToString(" ") { "${it.surface}/${it.reading}" })
    }

    @Test
    fun `語ごとに表記・読み・位置を返す`() {
        assertEquals(
            listOf(
                ReadingToken("Yahoo", null, 0),
                ReadingToken("!", null, 5),
                ReadingToken("乗換", "のりかえ", 6),
                ReadingToken("案内", "あんない", 8),
            ),
            analyzer.analyze("Yahoo!乗換案内"),
        )
    }

    @Test
    fun `空白の語は返さず、後の語の位置は元の文字列のまま`() {
        val tokens = analyzer.analyze("乗換案内 時刻表")
        assertEquals(listOf("乗換", "案内", "時刻", "表"), tokens.map { it.surface })
        assertEquals(5, tokens[2].start)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "　", " \t\n"])
    fun `空や空白だけの文字列には空の一覧を返す`(text: String) {
        assertEquals(emptyList<ReadingToken>(), analyzer.analyze(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["NHK", "123", "!?", "ＮＨＫ", "１２３", "Google Maps"])
    fun `日本語の文字を含まない語には読みを付けない`(text: String) {
        // 全角の英字（ＮＨＫ）は IPADIC に読みがあるが、英字の読みはブランド表に任せる
        val tokens = analyzer.analyze(text)
        assertTrue(tokens.isNotEmpty())
        tokens.forEach { assertNull(it.reading, it.toString()) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Yahoo!乗換案内", "📷カメラ", "🍣寿司🍺", "乗換案内 時刻表", "ｶﾒﾗ", "a😀b乗換", "𠮷野家", "ゆうちょ通帳アプリ",
            "Google マップ", "ーー", "・", "123円",
        ],
    )
    fun `位置と表記が元の文字列と合い、読みはひらがなと長音符だけ`(text: String) {
        val tokens = analyzer.analyze(text)
        var previousEnd = 0
        for (token in tokens) {
            assertEquals(token.surface, text.substring(token.start, token.end), token.toString())
            assertTrue(token.start >= previousEnd, "語が重なるか、順が逆: $tokens")
            previousEnd = token.end
            token.reading?.let { reading ->
                assertTrue(reading.codePoints().allMatch { isHiraganaOrProlonged(it) }, "ひらがなでない読み: $token")
            }
        }
        // 捨ててよいのは空白だけ
        val covered = tokens.joinToString("") { it.surface }
        assertEquals(text.filterNot { it.isWhitespace() }, covered)
    }

    @Test
    fun `辞書にないかなの語は表記を読みにする`() {
        // 表記をそのまま読みにする道を通ることを、辞書の読み（*）で確かめる
        assertEquals("まいなぽーたる", readingOf("マイナポータル", "*"))
        assertEquals("ゆうちょ", readingOf("ゆうちょ", null))
    }

    @Test
    fun `ひらがなにならない読みは付けない`() {
        // 半角カナは呼び出す側の正規化で全角にする約束
        assertNull(readingOf("ｶﾒﾗ", "*"))
        assertNull(readingOf("ヷ", "*"))
    }

    @Test
    fun `辞書にない漢字の語には読みを付けない`() {
        assertNull(readingOf("𠮷", "*"))
        assertNull(readingOf("乗換x", null))
    }

    @Test
    fun `日本語の文字を含む語は辞書の読みを使う`() {
        assertEquals("てぃーしゃつ", readingOf("Ｔシャツ", "ティーシャツ"))
    }

    @Test
    fun `辞書は最初に使うときに 1 回だけ読み込む`() {
        val created = AtomicInteger()
        val lazyAnalyzer = KuromojiReadingAnalyzer { created.incrementAndGet().let { sharedTokenizer } }
        assertFalse(lazyAnalyzer.isLoaded)
        assertEquals(0, created.get())
        lazyAnalyzer.analyze("天気")
        lazyAnalyzer.analyze("地図")
        lazyAnalyzer.warmUp()
        assertTrue(lazyAnalyzer.isLoaded)
        assertEquals(1, created.get())
    }

    @Test
    fun `同時に初めて使っても辞書は 1 回だけ読み込み、どのスレッドも同じ結果を得る`() {
        val created = AtomicInteger()
        val gate = CountDownLatch(1)
        val lazyAnalyzer =
            KuromojiReadingAnalyzer {
                created.incrementAndGet()
                // 読み込みの途中で他のスレッドが入ってくる状況を作る
                Thread.sleep(50)
                sharedTokenizer
            }
        val texts = List(64) { listOf("乗換案内", "Yahoo!天気", "三井住友銀行", "ゆうちょ通帳アプリ")[it % 4] }
        val expected = texts.map { analyzer.analyze(it) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures =
                texts.map { text ->
                    pool.submit<List<ReadingToken>> {
                        gate.await()
                        lazyAnalyzer.analyze(text)
                    }
                }
            gate.countDown()
            assertEquals(expected, futures.map { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, created.get())
    }

    @Test
    fun `release の後は読み込み直す`() {
        val created = AtomicInteger()
        val lazyAnalyzer =
            KuromojiReadingAnalyzer {
                created.incrementAndGet()
                sharedTokenizer
            }
        lazyAnalyzer.warmUp()
        lazyAnalyzer.release()
        assertFalse(lazyAnalyzer.isLoaded)
        assertEquals("ちず", lazyAnalyzer.analyze("地図").single().reading)
        assertEquals(2, created.get())
    }

    @Test
    fun `読み込みに失敗したら例外を投げ、次の呼び出しで読み込み直す`() {
        val attempts = AtomicInteger()
        val flaky =
            KuromojiReadingAnalyzer {
                if (attempts.incrementAndGet() == 1) throw IllegalStateException("辞書を読めない")
                sharedTokenizer
            }
        assertThrows(IllegalStateException::class.java) { flaky.warmUp() }
        assertFalse(flaky.isLoaded)
        assertEquals("ちず", flaky.analyze("地図").single().reading)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `既定の作り方は Kuromoji の辞書を読み込む`() {
        val fresh = KuromojiReadingAnalyzer()
        assertFalse(fresh.isLoaded)
        fresh.warmUp()
        assertTrue(fresh.isLoaded)
        assertEquals("のりかえあんない", fresh.analyze("乗り換え案内").joinToString("") { it.reading.orEmpty() })
    }

    private fun joinedReading(text: String): String = analyzer.analyze(text).joinToString("") { it.reading ?: it.surface }

    private fun isHiraganaOrProlonged(codePoint: Int): Boolean =
        codePoint == 'ー'.code || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA

    private companion object {
        // 辞書の読み込みは 0.2 秒ほどかかるので、テストの間で 1 つを使い回す
        val sharedTokenizer: Tokenizer by lazy { Tokenizer() }
        val analyzer = KuromojiReadingAnalyzer { sharedTokenizer }
    }
}
