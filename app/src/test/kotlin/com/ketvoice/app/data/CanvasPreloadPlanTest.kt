package com.ketvoice.app.data

import com.ketvoice.app.data.CanvasPreloadPlan.Conditions
import com.ketvoice.app.data.CanvasPreloadPlan.Reason
import com.ketvoice.app.data.CanvasPreloadPlan.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Canvas preload fetches next and when it stops (6.2.3). */
class CanvasPreloadPlanTest {
    private fun track(id: String) = Track(id = id, title = id, artist = "a", album = "b")
    private val ok = Conditions(enabled = true, canvasOn = true, unmetered = true, inFrontOrCharging = true, network = "100")

    private fun plan(vararg ids: String, max: Int = 40) = CanvasPreloadPlan(ids.map(::track), maxPerRun = max)

    @Test fun favouritesComeFirstThenPlaylistsEachTitleOnce() {
        val order = CanvasPreloadPlan.order(
            listOf(track("f1"), track("f2")),
            listOf(listOf(track("p1"), track("f2"), track("local:9")), listOf(track("p1"), track("p2"))))
        assertEquals(listOf("f1", "f2", "p1", "p2"), order.map { it.id })
    }

    @Test fun titlesAreHandedOutOnceAtFourAMinute() {
        val p = plan("a", "b", "a", "c")
        assertEquals(Step.Fetch(track("a")), p.next(0, ok))
        // The next one is not due yet: the plan says how long to wait.
        assertEquals(Step.Wait(15_000), p.next(0, ok))
        assertEquals(Step.Wait(5_000), p.next(10_000, ok))
        assertEquals(Step.Fetch(track("b")), p.next(15_000, ok))
        assertEquals(Step.Fetch(track("c")), p.next(30_000, ok))
        assertEquals(Step.Stop(Reason.Done), p.next(45_000, ok))
        assertEquals(3, p.fetched)
    }

    @Test fun aRunStopsAtItsLimit() {
        val p = plan("a", "b", "c", max = 2)
        p.next(0, ok); p.next(15_000, ok)
        assertEquals(Step.Stop(Reason.Limit), p.next(30_000, ok))
    }

    @Test fun theDefaultLimitIsAboutForty() {
        val p = CanvasPreloadPlan((1..100).map { track("t$it") })
        var now = 0L; var fetched = 0
        while (true) {
            when (val s = p.next(now, ok)) {
                is Step.Fetch -> fetched++
                is Step.Wait -> now += s.ms
                is Step.Stop -> { assertEquals(Reason.Limit, s.reason); break }
            }
        }
        assertEquals(40, fetched)
    }

    @Test fun titlesThatNeedNothingAreSkippedWithoutWaiting() {
        val p = plan("has", "excluded", "new")
        val needed = { t: Track -> t.id == "new" }
        assertEquals(Step.Fetch(track("new")), p.next(0, ok, needed))
        assertEquals(1, p.fetched)
    }

    @Test fun turningTheSettingOffStopsTheRun() {
        val p = plan("a", "b")
        p.next(0, ok)
        assertEquals(Step.Stop(Reason.Disabled), p.next(15_000, ok.copy(enabled = false)))
    }

    @Test fun bothCanvasSettingsOffMeansNothing() {
        assertEquals(Step.Stop(Reason.CanvasOff), plan("a").next(0, ok.copy(canvasOn = false)))
    }

    @Test fun mobileDataStopsTheRunWhateverTheCanvasMobileSettingSays() {
        val p = plan("a", "b")
        assertEquals(Step.Stop(Reason.NotUnmetered), p.next(0, ok.copy(unmetered = false)))
        val q = plan("a", "b"); q.next(0, ok)
        assertEquals(Step.Stop(Reason.NotUnmetered), q.next(15_000, ok.copy(unmetered = false)))
    }

    @Test fun anotherNetworkEndsTheRun() {
        val p = plan("a", "b")
        p.next(0, ok)
        assertEquals(Step.Stop(Reason.NetworkChanged), p.next(15_000, ok.copy(network = "101")))
    }

    @Test fun behindOtherAppsOnBatteryTheRunWaitsThenGivesUp() {
        val p = plan("a")
        val away = ok.copy(inFrontOrCharging = false)
        assertEquals(Step.Wait(CanvasPreloadPlan.IDLE_POLL_MS), p.next(0, away))
        assertEquals(Step.Wait(CanvasPreloadPlan.IDLE_POLL_MS), p.next(5 * 60_000L, away))
        assertEquals(Step.Stop(Reason.Idle), p.next(CanvasPreloadPlan.MAX_IDLE_MS + 1, away))
    }

    @Test fun comingBackInFrontResumesAndResetsTheIdleClock() {
        val p = plan("a", "b")
        val away = ok.copy(inFrontOrCharging = false)
        p.next(0, away)
        assertEquals(Step.Fetch(track("a")), p.next(60_000, ok))
        assertTrue(p.next(70_000, away) is Step.Wait)
        // The idle time counts from the new absence, not from the first one.
        assertTrue(p.next(CanvasPreloadPlan.MAX_IDLE_MS + 5_000, away) is Step.Wait)
    }
}
