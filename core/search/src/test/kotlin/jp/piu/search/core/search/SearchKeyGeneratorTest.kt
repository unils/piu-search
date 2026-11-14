// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

import jp.piu.search.core.reading.NoReadingAnalyzer
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.reading.ReadingToken
import jp.piu.search.core.text.NormalizedText
import jp.piu.search.core.text.RomajiConverter
import jp.piu.search.core.text.RomajiMode
import jp.piu.search.core.text.TextNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.Normalizer

/**
 * キーの一覧は 1 行 1 キーで「種類 文字列 語の頭の位置」と書く。緩い形は文字列の頭に `~` を付ける。
 * 並びは [SearchKeyGenerator.generate] の順（厳密形が先、緩い形が後）。
 */
class SearchKeyGeneratorTest {
    private val generator = TestAnalyzers.generator

    @Test
    fun `Yahoo!天気 は matching_md の表のとおり`() {
        // matching.md の「検索キーの生成」の例: 名前 yahoo天気、読み やふーてんき、ローマ字 yahutenki と yahootenki
        assertKeys(
            """
            NAME yahoo天気 [5]
            READING やふーてんき [3]
            ROMAJI yahutenki [4]
            ROMAJI yahootenki [5]
            PACKAGE_NAME yahoo []
            PACKAGE_NAME weather []
            PACKAGE_NAME type1 []
            PACKAGE_NAME やふー []
            PACKAGE_NAME うぇざー []
            GENRE weather []
            READING ~やふてんき [2]
            ROMAJI ~yahotenki [4]
            PACKAGE_NAME ~やふ []
            PACKAGE_NAME ~うえざ []
            """,
            app("Yahoo!天気", "jp.co.yahoo.android.weather.type1", genres = listOf("weather")),
        )
    }

    @Test
    fun `ジョルダン乗換案内 は語の頭の位置を持つ`() {
        // matching.md の段階 3 の例:「あんない」で語の頭から一致させるため、読みとローマ字に あんない の頭の位置がある
        assertKeys(
            """
            NAME じょるだん乗換案内 [5, 7]
            READING じょるだんのりかえあんない [5, 9]
            ROMAJI zyorudannorikaeannai [8, 15]
            PACKAGE_NAME jorudan []
            PACKAGE_NAME nrkj []
            PACKAGE_NAME じょるだん []
            NAME ~じよるだん乗換案内 [5, 7]
            READING ~じよるだんのりかえあんない [5, 9]
            ROMAJI ~zyorudanorikaeanai [7, 14]
            PACKAGE_NAME ~じよるだん []
            """,
            app("ジョルダン乗換案内", "jp.co.jorudan.nrkj"),
        )
    }

    @Test
    fun `ホットペッパーグルメ はローマ字とパッケージ名の語で英字の検索語に当たる`() {
        // matching.md の「カタカナの名前を英字で打つ場合」: hot・hott・ほt は読みのローマ字の前方、hotpepper はパッケージ名の語。
        // 読みは名前の厳密形と同じなので、読みのキーは名前のキーにまとまる
        assertKeys(
            """
            NAME ほっとぺっぱーぐるめ []
            ROMAJI hottopeppagurume []
            PACKAGE_NAME recruit []
            PACKAGE_NAME mtl []
            PACKAGE_NAME hotpepper []
            PACKAGE_NAME りくるーと []
            PACKAGE_NAME ほっとぺっぱー []
            NAME ~ほつとぺつぱぐるめ []
            ROMAJI ~hotopepagurume []
            PACKAGE_NAME ~りくると []
            PACKAGE_NAME ~ほつとぺつぱ []
            """,
            app("ホットペッパーグルメ", "jp.co.recruit.mtl.android.hotpepper"),
        )
        val keys = generator.generate(app("ホットペッパーグルメ", "jp.co.recruit.mtl.android.hotpepper"))
        val romaji = keys.single { it.kind == SearchKeyKind.ROMAJI && it.form == KeyForm.STRICT }.text.value
        listOf("hot", "hott", "ほt").forEach { query ->
            assertTrue(romaji.startsWith(prefixRomaji(query)), query)
        }
    }

    @Test
    fun `NHKプラス は略語の読みと英字を残したローマ字を持つ`() {
        // matching.md の「ローマ字」: 英字を残した名前から nhk + purasu。「んhk」も nhk になり前方一致する
        assertKeys(
            """
            NAME nhkぷらす [3]
            READING えぬえいちけーぷらす [7]
            ROMAJI enueitikepurasu [9]
            ROMAJI nhkpurasu [3]
            PACKAGE_NAME nhk []
            PACKAGE_NAME nhkplus []
            READING ~えぬえいちけぷらす [6]
            """,
            app("NHKプラス", "jp.or.nhk.nhkplus"),
        )
        val romaji = generator.generate(app("NHKプラス", "jp.or.nhk.nhkplus")).filter { it.kind == SearchKeyKind.ROMAJI }.map { it.text.value }
        assertTrue(romaji.any { it.startsWith(prefixRomaji("んhk")) })
    }

    @Test
    fun `名前の「+」は読みとローマ字にだけ ぷらす を足す`() {
        // M1-05 の判断: 「でぃずにーぷらす」で Disney+ が出るように。名前のキーは記号を除いた形のまま
        assertKeys(
            """
            NAME disney []
            READING でぃずにーぷらす [5]
            ROMAJI dhizunipurasu [7]
            ROMAJI disneyplus [6]
            PACKAGE_NAME disneyplus []
            PACKAGE_NAME でぃずにー []
            READING ~でいずにぷらす [4]
            PACKAGE_NAME ~でいずに []
            """,
            app("Disney+", "com.disney.disneyplus"),
        )
        assertKeys(
            """
            NAME 少年じゃんぷ [2]
            READING しょうねんじゃんぷぷらす [5, 9]
            ROMAJI syounenzyanpupurasu [7, 13]
            ROMAJI syounenzyanpuplus [7, 13]
            NAME ~少年じやんぷ [2]
            READING ~しようねんじやんぷぷらす [5, 9]
            ROMAJI ~syonenzyanpupurasu [6, 12]
            ROMAJI ~syonenzyanpuplus [6, 12]
            """,
            app("少年ジャンプ+"),
        )
    }

    @Test
    fun `Google マップ は英語名 Maps のキーも持つ`() {
        // 英語名は別の言語の名前のキーになり、英語名の読みとローマ字も足す。パッケージ名の語 maps は英語名のキーと重なるので除く
        assertKeys(
            """
            NAME googleまっぷ [6]
            READING ぐーぐるまっぷ [4]
            ROMAJI gugurumappu [6]
            ROMAJI googlemappu [6]
            LOCALIZED_NAME maps []
            READING まっぷ []
            ROMAJI mappu []
            READING まっぷす []
            ROMAJI mappusu []
            ROMAJI maps []
            PACKAGE_NAME google []
            PACKAGE_NAME ぐーぐる []
            GENRE maps []
            NAME ~googleまつぷ [6]
            READING ~ぐぐるまつぷ [3]
            ROMAJI ~gugurumapu [6]
            ROMAJI ~goglemapu [5]
            READING ~まつぷ []
            ROMAJI ~mapu []
            READING ~まつぷす []
            ROMAJI ~mapusu []
            PACKAGE_NAME ~ぐぐる []
            """,
            app("Google マップ", "com.google.android.apps.maps", localized = listOf("Maps", "Google マップ"), genres = listOf("maps")),
        )
    }

    @Test
    fun `空白と記号で分かれる英字の名前は語ごとに表を引く`() {
        assertKeys(
            """
            NAME wifi [2]
            READING わいふぁい [2]
            ROMAJI waifai [3]
            ROMAJI wifi [2]
            READING ~わいふあい [2]
            """,
            app("Wi-Fi"),
        )
        assertKeys(
            """
            NAME fdroid [1]
            READING えふどろいど [2]
            ROMAJI ehudoroido [3]
            ROMAJI fdroid [1]
            """,
            app("F-Droid"),
        )
        assertKeys(
            """
            NAME ubereats [4]
            READING うーばーいーつ [4]
            ROMAJI ubaitu [3]
            ROMAJI ubereats [4]
            READING ~うばいつ [2]
            """,
            app("Uber Eats"),
        )
        // アポストロフィは語の中に入れ、表の綴り mcdonalds で引く
        assertKeys(
            """
            NAME mcdonalds []
            READING まくどなるど []
            ROMAJI makudonarudo []
            READING まっく []
            ROMAJI makku []
            ROMAJI mcdonalds []
            READING ~まつく []
            ROMAJI ~maku []
            """,
            app("McDonald's"),
        )
    }

    @Test
    fun `辞書で 1 語の複合語は読みのキーに語の頭を持たない`() {
        // 複合語を区切らない（KuromojiReadingAnalyzer の KDoc）ので、「すみとも」「ぎんこう」は語の頭にならない
        val reading = generator.generate(app("三井住友銀行")).first { it.kind == SearchKeyKind.READING }
        assertEquals("READING みついすみともぎんこう []", describe(reading))
    }

    @Test
    fun `読みの組み合わせは上限で止まる`() {
        // x は えっくす・ついったー の 2 通り。4 語で 16 通りになるが、既定の上限 8 で止まる
        val keys = generator.generate(app("X X X X"))
        val readings = keys.filter { it.kind == SearchKeyKind.READING && it.form == KeyForm.STRICT }.map { it.text.value }
        assertEquals(SearchKeyGenerator.DEFAULT_MAX_READING_COMBINATIONS, readings.size)
        // どの語も先頭の読みを使う組が最初に来て、後ろの語から順に替える
        assertEquals("えっくすえっくすえっくすえっくす", readings[0])
        assertEquals("えっくすえっくすえっくすついったー", readings[1])
        assertEquals("えっくすえっくすついったーえっくす", readings[2])
    }

    @Test
    fun `読みの補いの表の語は辞書の読みに加えて補いの読みを持つ`() {
        // Kuromoji は 通帳 を かよいちょう、市場 を しじょう と読む。辞書の読みを先に残し、補いの読みを足す
        val readings =
            generator
                .generate(app("ゆうちょ通帳アプリ"))
                .filter { it.kind == SearchKeyKind.READING && it.form == KeyForm.STRICT }
                .map { it.text.value }
        assertEquals(listOf("ゆうちょかよいちょうあぷり", "ゆうちょつうちょうあぷり"), readings)
        val market = generator.generate(app("楽天市場")).filter { it.kind == SearchKeyKind.READING }.map { it.text.value }
        assertTrue("らくてんいちば" in market, market.toString())
        // 表記が語と完全に一致しなければ引かない（市場調査 は 1 語）
        assertEquals(emptyList<NormalizedText>(), ReadingSupplements.of("市場調査"))
    }

    @Test
    fun `読みの組み合わせの上限を変えられる`() {
        val one = SearchKeyGenerator(TestAnalyzers.kuromoji, LatinWordReader(TestAnalyzers.brands), maxReadingCombinations = 1)
        val keys = one.generate(app("X X X X"))
        assertEquals(
            listOf("えっくすえっくすえっくすえっくす"),
            keys.filter { it.kind == SearchKeyKind.READING && it.form == KeyForm.STRICT }.map { it.text.value },
        )
        assertThrows(IllegalArgumentException::class.java) {
            SearchKeyGenerator(TestAnalyzers.kuromoji, LatinWordReader(TestAnalyzers.brands), maxReadingCombinations = 0)
        }
    }

    @Test
    fun `組み合わせは候補の数の積で尽きる`() {
        assertEquals(
            listOf(listOf(1, 3), listOf(1, 4), listOf(2, 3), listOf(2, 4)),
            combinations(listOf(listOf(1, 2), listOf(3, 4)), limit = 100),
        )
        assertEquals(emptyList<List<Int>>(), combinations(emptyList<List<Int>>(), limit = 8))
    }

    @Test
    fun `読みを付けない解析器でも英字の語の読みは付く`() {
        // 辞書を読み込む前の索引。漢字は読みのキーに表記のまま残る
        val keys = SearchKeyGenerator(NoReadingAnalyzer, LatinWordReader(TestAnalyzers.brands)).generate(app("Yahoo!天気"))
        assertEquals(
            listOf(
                "NAME yahoo天気 [5]",
                "READING やふー天気 [3]",
                "ROMAJI yahu天気 [4]",
                "ROMAJI yahoo天気 [5]",
                "READING ~やふ天気 [2]",
                "ROMAJI ~yaho天気 [4]",
            ),
            keys.map(::describe),
        )
    }

    @Test
    fun `解析器には NFKC だけをかけた名前を渡す`() {
        val received = ArrayList<String>()
        val recording =
            ReadingAnalyzer { text ->
                received.add(text)
                emptyList<ReadingToken>()
            }
        SearchKeyGenerator(recording, LatinWordReader(TestAnalyzers.brands)).generate(app("ﾔﾌｰ!天気", localized = listOf("Ｙａｈｏｏ！ Ｗｅａｔｈｅｒ")))
        assertEquals(listOf("ヤフー!天気", "Yahoo! Weather"), received)
    }

    @Test
    fun `表示名と同じ別の言語の名前と、空の名前からはキーを作らない`() {
        assertEquals(
            listOf("NAME てんき []", "ROMAJI tenki []", "PACKAGE_NAME weather []", "PACKAGE_NAME うぇざー []", "PACKAGE_NAME ~うえざ []"),
            generator.generate(app("てんき", "x.weather", localized = listOf("テンキ", "", "!!"))).map(::describe),
        )
        assertEquals(emptyList<String>(), generator.generate(app("", "x")).map(::describe))
    }

    @Test
    fun `表示名が空なら別の言語の名前を名前のキーにする`() {
        assertEquals(
            listOf("NAME maps []", "READING まっぷ []"),
            generator.generate(app("", localized = listOf("Maps"))).map(::describe).take(2),
        )
    }

    @Test
    fun `パッケージ名の語からありふれた語を除く`() {
        assertEquals(listOf("recruit", "mtl", "hotpepper"), packageWords("jp.co.recruit.mtl.android.hotpepper").map { it.value })
        assertEquals(listOf("google", "maps"), packageWords("com.google.android.apps.maps").map { it.value })
        assertEquals(listOf("naver", "line"), packageWords("jp.naver.line.android").map { it.value })
        // _ でも分け、1 字の語と数字だけの語を除く
        assertEquals(listOf("mo", "go", "v2"), packageWords("com.mo_t.go.v2.app_2.go").map { it.value })
        assertEquals(emptyList<NormalizedText>(), packageWords("android"))
    }

    @Test
    fun `パッケージ名の 2 番目の部分のドメインの語と、置き場・技術・版の語を除く`() {
        // research/package-names.md の 4 分類
        assertEquals(listOf("rakuten", "sec", "ispeed"), packageWords("jp.co.rakuten_sec.ispeed").map { it.value })
        assertEquals(listOf("woolworths"), packageWords("au.com.woolworths").map { it.value })
        assertEquals(listOf("digital", "mynaportal"), packageWords("jp.go.digital.mynaportal").map { it.value })
        assertEquals(listOf("woheller69", "weather"), packageWords("io.github.woheller69.weather").map { it.value })
        assertEquals(listOf("myapp"), packageWords("com.example.myapp.free.debug").map { it.value })
        assertEquals(listOf("nextcloud"), packageWords("com.nextcloud.client").map { it.value })
        // 2 番目でない位置のドメインの語は名前の中の語として残す
        assertEquals(listOf("mo", "go"), packageWords("com.mo_t.go").map { it.value })
        assertEquals(listOf("tracker", "net"), packageWords("org.tracker.net.foss").map { it.value })
    }

    @Test
    fun `表示名の頭の Y! を やふー と読み、綴りの形では yahoo とも書く`() {
        val keys = generator.generate(app("Y!乗換案内", "jp.co.yahoo.android.apps.transit")).map(::describe)
        assertTrue("READING やふーのりかえあんない [3, 7]" in keys, keys.toString())
        assertTrue("READING わいのりかえあんない [2, 6]" in keys, keys.toString())
        assertTrue("ROMAJI yahoonorikaeannai [5, 12]" in keys, keys.toString())
        assertTrue("ROMAJI ynorikaeannai [1, 8]" in keys, keys.toString())
        // 頭でない Y! と、Y のあとに ! のない名前は略語の読みのまま
        val other = generator.generate(app("ABC Y!")).map(::describe)
        assertTrue(other.none { "やふー" in it }, other.toString())
        val plain = generator.generate(app("Y mobile")).map(::describe)
        assertTrue(plain.none { "やふー" in it }, plain.toString())
    }

    @Test
    fun `語の頭の位置はキーの文字列の中にあり昇順`() {
        val names = listOf("Yahoo!乗換案内", "三菱UFJ銀行", "Google Play ストア", "特務機関NERV防災", "LINE 2", "ゆうちょ通帳アプリ", "ｶﾒﾗ")
        for (name in names) {
            for (key in generator.generate(app(name))) {
                assertEquals(key.wordStarts.sorted().distinct(), key.wordStarts, describe(key))
                assertTrue(key.wordStarts.all { it in 1 until key.text.value.length }, describe(key))
            }
        }
    }

    @Test
    fun `キーは正規化の後の形で、重なりがない`() {
        val keys = generator.generate(app("乗り換え案内 Yahoo!JAPAN", "jp.co.yahoo.android.norikae", localized = listOf("Transit")))
        for (key in keys) {
            val text = key.text.value
            if (key.kind != SearchKeyKind.GENRE) assertEquals(TextNormalizer.strict(text).value, text, describe(key))
        }
        val spaces = keys.map { (if (it.kind == SearchKeyKind.ROMAJI) "r" else "t") + it.text.value }
        assertEquals(spaces.distinct(), spaces)
    }

    private fun assertKeys(
        expected: String,
        app: AppEntry,
    ) {
        assertEquals(expected.trimIndent().trim(), generator.generate(app).joinToString("\n", transform = ::describe))
    }

    private fun describe(key: SearchKey): String {
        val loose = if (key.form == KeyForm.LOOSE) "~" else ""
        return "${key.kind} $loose${key.text.value} ${key.wordStarts}"
    }

    /** 検索語の前方一致に使う標準形のローマ字（照合の側と同じ作り方）。 */
    private fun prefixRomaji(query: String): String =
        RomajiConverter.toStandard(TextNormalizer.strict(Normalizer.normalize(query, Normalizer.Form.NFKC)), RomajiMode.PREFIX).value

    private fun app(
        label: String,
        packageName: String = "x",
        localized: List<String> = emptyList(),
        genres: List<String> = emptyList(),
    ): AppEntry = AppEntry(AppId(packageName, "$packageName.Main", 0), label, localized, genres.map(::GenreId))
}
