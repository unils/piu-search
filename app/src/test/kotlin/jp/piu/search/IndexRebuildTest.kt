// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService

// 更新の知らせから同期の仕事を 1 つ預け、仕事が同期を 1 回頼んで、終わったら jobFinished を呼ぶこと。
// 同期の頼みが重なったときにまとめるのは IndexActorTest（data）が確かめる
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 37], application = TestApplication::class)
class IndexRebuildTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    @Test
    fun theUpdateBroadcastSchedulesOneJobEvenIfItArrivesTwice() {
        assertTrue(scheduler.allPendingJobs.isEmpty())
        repeat(2) {
            // マニフェストの受け手に、OS と同じく自分のパッケージ宛てで送る
            context.sendBroadcast(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            val job = scheduler.allPendingJobs.single()
            assertEquals(IndexRebuildJob.JOB_ID, job.id)
            assertEquals(IndexRebuildJob::class.java.name, job.service.className)
            // 急ぎの仕事は Android 12 から（isExpedited も 12 から）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(job.isExpedited)
        }
    }

    // 急ぎの枠を使い切って断られたら、同じ番号のふつうの仕事で預け直す（急ぎの仕事は Android 12 から）
    @Test
    @Config(sdk = [37])
    fun aRefusedExpeditedJobIsScheduledAsARegularJob() {
        shadowOf(scheduler).failExpeditedJob(true)
        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val job = scheduler.allPendingJobs.single()
        assertEquals(IndexRebuildJob.JOB_ID, job.id)
        assertFalse(job.isExpedited)
    }

    @Test
    fun anotherActionSchedulesNothing() {
        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_PACKAGE_REPLACED))
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun theJobRequestsOneSyncAndFinishesWithoutRetryingWhenItIsDone() {
        val service = Robolectric.buildService(IndexRebuildJob::class.java).create().get()
        val shadow = Shadow.extract<ShadowJobService>(service)
        val before = TestHooks.index.syncs
        assertTrue(service.onStartJob(jobParameters()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before + 1, TestHooks.index.syncs)
        assertFalse(shadow.isJobFinished)

        TestHooks.index.syncReplies
            .last()
            .complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadow.isJobFinished)
        assertFalse(shadow.isRescheduleNeeded)
        assertEquals(before + 1, TestHooks.index.syncs)
    }

    @Test
    fun aStoppedJobIsNotRetriedAndDoesNotFinishLater() {
        val service = Robolectric.buildService(IndexRebuildJob::class.java).create().get()
        val shadow = Shadow.extract<ShadowJobService>(service)
        val params = jobParameters()
        assertTrue(service.onStartJob(params))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(service.onStopJob(params))

        // 止めた後に同期が終わっても、終わったとは知らせない（OS は止めた時点で仕事を片付けている）
        TestHooks.index.syncReplies
            .last()
            .complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadow.isJobFinished)
    }

    // JobParameters の作り方は公開されておらず、引数が版ごとに違う。いちばん引数の多い（Parcel からでない）ものを既定の値で呼ぶ
    private fun jobParameters(): JobParameters {
        val constructor = JobParameters::class.java.declaredConstructors.maxBy { it.parameterCount }
        constructor.isAccessible = true
        val args =
            constructor.parameterTypes.map<Class<*>, Any?> {
                when (it) {
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
        return constructor.newInstance(*args.toTypedArray()) as JobParameters
    }
}
