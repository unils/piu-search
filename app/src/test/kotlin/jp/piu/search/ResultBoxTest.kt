// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ResultBoxTest {
    @Test
    fun anEqualLaterResultIsNotClearedByTheEarlierOnesDone() {
        val box = ResultBox<BackupOutcome>()
        val first = box.post(BackupOutcome.Exported(10))
        val second = box.post(BackupOutcome.Exported(10))
        // 中身は等しいが別の操作の結果。古いほうを出し終えても、新しいほうは残す
        box.done(first)
        assertSame(second, box.current.value)
        box.done(second)
        assertNull(box.current.value)
    }
}
