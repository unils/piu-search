// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile（起動に使うコードを先にコンパイルしておく規則）と、起動用のプロファイル（startup profile）を集める。
 *
 * - [startup]: 呼び出してから検索窓に焦点が来るまで。起動用のプロファイルにも入れ、R8 がこの流れのクラスを
 *   最初の DEX（classes.dex）にまとめる（DEX の並べ替え）
 * - [search]: 呼び出し → 検索窓に打つ → 結果が出る。漢字の検索語も打ち、読みを付ける Kuromoji の経路も含める
 *   （索引の作り直しでも同じコードを使う）。Baseline Profile にだけ入れる
 *
 * 結果が出るのを待つので、テスト用のアプリ（test-apps/）が要る（生成の前に入る）。
 * `./gradlew :app:generateBaselineProfile` で回す。結果は app の `src/release/generated/baselineProfiles/` に入り、
 * リポジトリに入れる（CONTRIBUTING.md の「起動の速さ」）。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    // 入口（アイコン・アシスタント・タイル）はどれも SearchActivity を開き、onCreate からの流れは同じなので、
    // アイコンからの起動だけを通す。検索語を打った後の照合と Kuromoji は入れない。起動の流れを classes.dex に
    // 収めるため（公式の文書は、起動用のプロファイルの流れを 1 つの DEX に収まる範囲に絞るよう勧める）
    @Test
    fun startup() =
        rule.collect(packageName = APP, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            // 最初の描画の後も、検索窓に焦点が来る（reportFullyDrawn）までは起動の流れに数える
            checkNotNull(device.wait(Until.findObject(By.res(SEARCH_FIELD).focused(true)), TIMEOUT_MILLIS)) {
                "検索窓に焦点が来ない"
            }
        }

    @Test
    fun search() =
        rule.collect(packageName = APP) {
            pressHome()
            startActivityAndWait()
            for ((query, expected) in QUERIES) {
                val field = checkNotNull(device.wait(Until.findObject(By.res(SEARCH_FIELD)), TIMEOUT_MILLIS)) { "検索窓が出ない" }
                field.text = query
                // 結果の行は、名前と出た理由を文字か説明に持つ（M5-02）。端末の言語で名前が変わるので、どれかを待つ
                val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
                var shown = false
                while (!shown && SystemClock.uptimeMillis() < deadline) {
                    shown = expected.any { device.hasObject(By.textContains(it)) || device.hasObject(By.descContains(it)) }
                    if (!shown) SystemClock.sleep(POLL_MILLIS)
                }
                check(shown) { "「$query」で ${expected.joinToString("・")} が出ない" }
            }
        }

    private companion object {
        /** 検索語と、出るのを待つアプリの名前（日本語・英語）。 */
        val QUERIES =
            listOf(
                "norikae" to listOf("乗換案内テスト", "Transit Test"),
                "やふー" to listOf("Yahoo!テスト"),
                "乗り換え" to listOf("乗換案内テスト", "Transit Test"),
            )
        const val POLL_MILLIS = 200L
        const val TIMEOUT_MILLIS = 60_000L
    }
}
