// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 呼び出してから検索窓に打てるまでの時間（M5-03）。ランチャーのアイコンと同じ intent で開く。
 *
 * 指標は [StartupTimingMetric] の 2 つ。
 * - timeToInitialDisplayMs: 最初の描画まで（OS の「Displayed」）。検索窓はこの描画に入る
 * - timeToFullDisplayMs: 検索窓に焦点が来るまで（アプリの `reportFullyDrawn`。SearchScreen.kt の ReportDrawnWhen）
 *
 * 起動の状態（プロセスなし／プロセスあり）と、コンパイルの状態の組で測る。
 * - [CompilationMode.None]: 先にコンパイルしない（JIT だけ）。Baseline Profile がないときの下限
 * - [CompilationMode.Partial]: APK の Baseline Profile（ライブラリの分とアプリの分）でコンパイルする。インストールの後に
 *   ProfileInstaller が入れる状態
 *
 * エミュレータの値で、実機の値ではない（実機は M6）。比べるのは同じエミュレータの前後だけにする。
 */
@RunWith(Parameterized::class)
class StartupBenchmark(
    private val startupMode: StartupMode,
    private val compilation: CompilationMode,
) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startup() =
        rule.measureRepeated(
            packageName = APP,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilation,
            startupMode = startupMode,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }

    /** 組み合わせ。 */
    companion object {
        /** 1 つの組で測る回数。エミュレータの値はぶれるので、中央値とばらつきを見られる数にする。 */
        const val ITERATIONS = 15

        /** 起動の状態とコンパイルの状態の組。 */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> =
            listOf(StartupMode.COLD, StartupMode.WARM).flatMap { mode ->
                listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require)).map { arrayOf(mode, it) }
            }
    }
}
