// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// 保存前後を別の仕事に分けたり、同期だけ別スレッドで走らせたりしないことを固定する。
@RunWith(AndroidJUnit4::class)
class DatabaseWriterTest {
    @Test
    fun queuedWritesCannotInterruptAnOperationIncludingItsMemoryUpdate() {
        DatabaseWriter().use { writer ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val events = ArrayList<String>()
            writer.execute {
                events += "save"
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                events += "publish"
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            writer.execute { events += "delete" }
            writer.execute { events += "late record" }
            release.countDown()
            writer.run {
                assertEquals(listOf("save", "publish", "delete", "late record"), events)
                // 同期を既に並んだ仕事から呼んでも、同じスレッドで続けてデッドロックしない
                assertEquals(42, writer.run { 42 })
            }
        }
    }
}
