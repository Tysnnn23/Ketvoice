package com.ketvoice.app.stems

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.automix.AnalysisDepth
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The work process's requests and answers (6.1): the playing process asks [StemWorkService] through
 * [StemWork] and reads the result from the store. Robolectric has one process: the service's binder is
 * handed to the binding as Android would hand that of `:stems`, and the messages really cross threads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StemWorkTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var service: ServiceController<StemWorkService>
    private val rate = 44_100
    private val pcm = ShortArray(rate * 2 * 8) { (kotlin.math.sin(it * .01) * 8_000).toInt().toShort() }

    @Before fun start() {
        service = Robolectric.buildService(StemWorkService::class.java).create()
        val component = ComponentName(app, StemWorkService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindService(component, service.get().onBind(Intent().setComponent(component)))
        StemWork.init(app)
        StemWork.inProcess = false
    }

    @After fun stop() {
        StemWork.disconnect()
        StemWork.inProcess = true
        StemEngine.testNet = null
        runCatching { service.destroy() }
    }

    private fun <T> await(work: Deferred<T>): T {
        val until = System.currentTimeMillis() + 20_000
        while (!work.isCompleted && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", work.isCompleted)
        return work.getCompleted()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun <T> run(block: suspend () -> T): T = await(GlobalScope.async(Dispatchers.Default) { block() })

    @Test fun aPartIsSeparatedInTheWorkProcessAndReadFromTheStore() {
        var runs = 0
        StemEngine.testNet = object : StemNet {
            override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) { runs++; vocals.fill(0f); input.copyInto(accompaniment) }
        }
        val id = "qa:work-${System.nanoTime()}"
        assertNull(StemStore.quality(id, 200_000, true))
        assertEquals(StemWork.Outcome.Done, run { StemWork.part(id, 200_000, true, pcm, rate, 0L) })
        assertTrue("the network ran on the other side", runs > 0)
        assertEquals("its result is where the playing process reads it", StemMask.FAST, StemStore.quality(id, 200_000, true))
        // Prepared already: the second request does nothing more.
        val before = runs
        assertEquals(StemWork.Outcome.Done, run { StemWork.part(id, 200_000, true, pcm, rate, 0L) })
        assertEquals(before, runs)
        // The sound travelled as a temporary file, gone once read.
        assertTrue(StemWork.pcmDir(app).listFiles().orEmpty().isEmpty())
    }

    @Test fun withoutAModelTheWorkFailsWithoutBeingLost() {
        if (StemModel.anyReady) return
        assertEquals(StemWork.Outcome.Failed, run { StemWork.part("qa:none", 200_000, true, pcm, rate, 0L, StemMask.FAST) })
    }

    @Test fun theLibraryAnswersWithItsProgress() {
        val seen = ArrayList<Pair<Int, Int>>()
        val items = listOf(StemLibrary.LibraryItem("qa:missing", "/nonexistent/qa.m4a", 200_000))
        val outcome = run { StemWork.library(app, items, AnalysisDepth.Light, separate = false) { done, total -> synchronized(seen) { seen += done to total } } }
        // Off the charger the library holds back; on it the unreadable file is counted and skipped.
        assertEquals(StemWork.Outcome.Done, outcome)
        assertTrue(synchronized(seen) { seen.all { it.second == 1 } })
    }

    /** The work process stopped by Android: the request is lost (asked again later), the caller is never left waiting. */
    @Test fun aStoppedWorkProcessLosesTheRequestAndNothingElse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        StemEngine.testNet = object : StemNet {
            override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) {
                entered.countDown(); release.await(10, TimeUnit.SECONDS); vocals.fill(0f); input.copyInto(accompaniment)
            }
        }
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val work = GlobalScope.async(Dispatchers.Default) { StemWork.part("qa:lost-${System.nanoTime()}", 200_000, true, pcm, rate, 0L, StemMask.FAST) }
        val until = System.currentTimeMillis() + 10_000
        while (entered.count > 0 && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals("the separation started on the other side", 0L, entered.count)
        val connection = shadowOf(app).boundServiceConnections.last()
        connection.onServiceDisconnected(ComponentName(app, StemWorkService::class.java))
        try {
            assertEquals(StemWork.Outcome.Lost, await(work))
        } finally { release.countDown() }
    }

    @Test fun theWorkRunsInItsOwnProcess() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, StemWorkService::class.java), 0)
        assertEquals(app.packageName + com.ketvoice.app.WorkProcess.SUFFIX, info.processName)
        assertFalse(info.exported)
    }

    /** What the work process measured joins what the playing process knows, and neither erases the other's. */
    @Test fun measurementsOfBothProcessesAreKept() {
        val store = com.ketvoice.app.automix.AnalysisStore
        val id = "qa:measured-${System.nanoTime()}"
        val mine = com.ketvoice.app.automix.SegmentAnalysis(0L, 40_000L, bpm = 124f, analyser = 1)
        val theirs = com.ketvoice.app.automix.SegmentAnalysis(0L, 40_000L, bpm = 90f, analyser = 1)
        store.put(id, 200_000L, true, mine)
        synchronized(store) { store.merge(mapOf("i|200|$id" to theirs, "o|200|$id" to theirs)) }
        assertEquals("this process's own measure stays", 124f, store.intro(id, 200_000L)!!.bpm)
        assertEquals("the other's is taken up", 90f, store.outro(id, 200_000L)!!.bpm)
    }
}
