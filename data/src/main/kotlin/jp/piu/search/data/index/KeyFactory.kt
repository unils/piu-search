// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data.index

import jp.piu.search.core.reading.KuromojiReadingAnalyzer
import jp.piu.search.core.reading.ReadingAnalyzer
import jp.piu.search.core.search.BrandTable
import jp.piu.search.core.search.LatinWordReader
import jp.piu.search.core.search.SearchIndexVersion
import jp.piu.search.core.search.SearchKeyGenerator
import jp.piu.search.core.state.Failure
import jp.piu.search.core.state.IssueCategory
import jp.piu.search.core.state.KeyJob
import jp.piu.search.core.state.KeyOutcome
import jp.piu.search.data.InputSummary
import jp.piu.search.data.MaterialCauses
import java.security.MessageDigest

/** 索引のキーの生成。版とブランド表の値はキーの鍵に入り、変われば全件のキーを作り直す。 */
public interface KeyFactory {
    /** キーの生成の規則の版。 */
    public val generatorVersion: Int

    /** ブランド表の中身から作る値。 */
    public val brandTableHash: String

    /** [jobs] のキーを作る。すべての頼みの分をちょうど返し、作れなかった入口は [KeyOutcome.Failed] にする。 */
    public fun build(jobs: List<KeyJob>): Map<KeyJob, KeyOutcome>
}

/**
 * [SearchKeyGenerator] で作る [KeyFactory]。生成器とブランド表は最初に使うときに読む。
 * キーの生成の失敗は、このアプリの処理の失敗なので、理由によらず不具合の疑い（0.2.0 の `KEYS` の段と同じ）。
 *
 * @param warmUp 形態素解析器の辞書をまだ読んでいなければ読む。読めなければ例外
 * @param generatorFactory 生成器を作る。テストで、ブランド表と形態素解析器を差し替える
 */
internal class GeneratorKeyFactory(
    private val warmUp: () -> Unit,
    generatorFactory: () -> SearchKeyGenerator,
) : KeyFactory {
    constructor(analyzer: ReadingAnalyzer) : this(
        { if (analyzer is KuromojiReadingAnalyzer && !analyzer.isLoaded) analyzer.warmUp() },
        { SearchKeyGenerator(analyzer, LatinWordReader(BrandTable.loadDefault())) },
    )

    private val generator by lazy(generatorFactory)

    // 生成器の版（SearchIndexVersion）は辞書・正規化・読みの補いの変更で上げる。ブランド表は上げ忘れても拾えるよう中身で比べる
    override val generatorVersion: Int = SearchIndexVersion.CURRENT

    override val brandTableHash: String by lazy {
        val bytes =
            checkNotNull(BrandTable::class.java.getResourceAsStream(BrandTable.RESOURCE_NAME)) {
                "資源 ${BrandTable.RESOURCE_NAME} が見つからない"
            }.use { it.readBytes() }
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(HASH_CHARS)
    }

    override fun build(jobs: List<KeyJob>): Map<KeyJob, KeyOutcome> {
        if (jobs.isEmpty()) return emptyMap()
        // 辞書の初期化の失敗の単位は、キーを作る入口すべて。入口ごとに読み込みを試し直さない（0.2.0 と同じ）。Error は捕まえない
        val warmUpFailure =
            try {
                warmUp()
                null
            } catch (e: RuntimeException) {
                e
            }
        return jobs.associateWith { job ->
            warmUpFailure?.let { failed(job, it) } ?: try {
                KeyOutcome.Built(generator.generate(job.entry))
            } catch (e: RuntimeException) {
                failed(job, e)
            }
        }
    }

    private fun failed(
        job: KeyJob,
        e: RuntimeException,
    ): KeyOutcome =
        KeyOutcome.Failed(
            Failure(MaterialCauses.causeOf(e), IssueCategory.SUSPECTED_BUG, MaterialCauses.framesOf(e), InputSummary.of(job.entry, e)),
        )

    private companion object {
        // 衝突は気にしない長さ（変化の検出に使うだけで、安全性には関わらない）
        const val HASH_CHARS = 16
    }
}
