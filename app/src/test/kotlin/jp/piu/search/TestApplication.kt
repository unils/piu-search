// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.os.Looper
import jp.piu.search.data.records.DeviceProfileProbe
import jp.piu.search.data.records.ProfileProbe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 統合のテストのアプリ。本物の書き手（`piu.db`）を使い、画面が読む索引だけを [TestHooks.index] に差し替える
 * （端末から読ませずに、決まったアプリで画面を出すため）。記録の書き手は [TestHooks.pause] で止められる。
 */
class TestApplication : PiuApplication() {
    override fun createContainer(): AppContainer {
        val device = DeviceProfileProbe(this)
        TestHooks.index = FakeIndex()
        TestHooks.gate = null
        return AppContainer(
            this,
            profileProbe =
                ProfileProbe {
                    // 書き手は頼みのたびに最初に問い合わせる。ここで待てば、その頼みの処理を止められる
                    TestHooks.gate?.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    device.check()
                },
            indexPortOverride = TestHooks.index,
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}

/** 統合のテストから部品を操る口。 */
internal object TestHooks {
    lateinit var index: FakeIndex

    @Volatile var gate: CountDownLatch? = null

    /** 記録の書き手を止めたまま [action] を行い、最後に再開する。 */
    fun pause(action: () -> Unit) {
        val latch = CountDownLatch(1)
        gate = latch
        try {
            action()
        } finally {
            gate = null
            latch.countDown()
        }
    }

    /** 記録の書き手がそれまでの頼みを終え、画面のスレッドの続きを動かし終えるまで進める。 */
    fun drain(container: AppContainer) {
        repeat(2) {
            runBlocking { withTimeout(TIMEOUT_MILLIS) { container.records.refresh().await() } }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private const val TIMEOUT_MILLIS = 10_000L
}
