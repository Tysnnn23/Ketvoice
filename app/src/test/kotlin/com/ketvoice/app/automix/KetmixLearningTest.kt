package com.ketvoice.app.automix

import com.ketvoice.app.data.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** « Ketmix apprend de vos avis » on the phone: ratings reach the planner only while the setting is on. */
class KetmixLearningTest {
    private val duration = 200_000L
    private val out = MixFacts(duration, SegmentAnalysis(155_000, 45_000, 120f, 155_000.0, .9f, -1, -14f, 155_000, 199_000, 155_000,
        185_000, false, false, MusicalKey.UNREAD, 0f, -1.0), vocalEndMs = 170_000)
    private val into = MixFacts(duration, SegmentAnalysis(0, 40_000, 123f, 500.0, .9f, -1, -14f, 300, 40_000, 8_000, 40_000))
    private var saves = 0

    private fun plan(style: MixStyle = MixStyle.Auto) =
        TransitionPlanner.plan(out, into, MixContext(avoid = KetmixLearning.avoid()), MixSettings(style = style))

    private fun dislike(times: Int, plan: MixPlan) = repeat(times) { KetmixLearning.played(plan, "a — A", "b — B"); KetmixLearning.rate(false) }

    @Before fun setUp() {
        AppSettings.ketmixLearns.value = false; AppSettings.ketmixTaste.value = ""
        KetmixLearning.resetForTest(); saves = 0; KetmixLearning.persist = { saves++ }
    }

    @After fun tearDown() {
        AppSettings.ketmixLearns.value = false; AppSettings.ketmixTaste.value = ""
        KetmixLearning.resetForTest()
    }

    @Test fun threeDislikesLeaveAKindOutOfTheAutomaticStyle() {
        AppSettings.ketmixLearns.value = true
        val first = plan()
        assertEquals(MixKind.Beatmatch, first.kind)
        dislike(2, first)
        assertEquals("two votes are not enough", MixKind.Beatmatch, plan().kind)
        dislike(1, first)
        assertEquals(setOf(MixKind.Beatmatch), KetmixLearning.avoid())
        val learnt = plan()
        assertTrue(learnt.mixes)
        assertNotEquals(MixKind.Beatmatch, learnt.kind)
        assertEquals("each rating is written to the phone's settings", 3, saves)
        assertTrue(AppSettings.ketmixTaste.value.startsWith("Beatmatch:"))
    }

    @Test fun aStylePickedByHandIsNeverOverridden() {
        AppSettings.ketmixLearns.value = true
        dislike(4, plan())
        assertEquals(MixKind.Beatmatch, plan(MixStyle.Tempo).kind)
    }

    @Test fun offTheLearntTasteIsKeptButNotUsed() {
        AppSettings.ketmixLearns.value = true
        dislike(3, plan())
        AppSettings.ketmixLearns.value = false
        assertEquals(emptySet<MixKind>(), KetmixLearning.avoid())
        assertEquals(MixKind.Beatmatch, plan().kind)
    }

    @Test fun aRatingGivenWhileOffCountsForNothing() {
        dislike(5, plan())
        assertNull(KetmixLearning.last.value?.rating)
        assertEquals("", AppSettings.ketmixTaste.value)
        assertEquals(0, saves)
        AppSettings.ketmixLearns.value = true
        assertEquals(emptySet<MixKind>(), KetmixLearning.avoid())
        assertEquals(MixKind.Beatmatch, plan().kind)
    }

    @Test fun changingOnesMindAndForgetting() {
        AppSettings.ketmixLearns.value = true
        val first = plan()
        KetmixLearning.played(first, "a — A", "b — B")
        KetmixLearning.rate(false)
        KetmixLearning.rate(false)
        assertEquals("the same rating twice counts once", 1, KetmixTaste.decode(AppSettings.ketmixTaste.value)[MixKind.Beatmatch]?.votes)
        KetmixLearning.rate(true)
        assertEquals(true, KetmixLearning.last.value?.rating)
        assertTrue((KetmixTaste.decode(AppSettings.ketmixTaste.value)[MixKind.Beatmatch]?.score ?: 0f) > 0f)
        KetmixLearning.reset()
        assertEquals("", AppSettings.ketmixTaste.value)
        assertNull(KetmixLearning.last.value?.rating)
    }

    @Test fun aTitleSimplyFollowingAnotherIsNotATransition() {
        KetmixLearning.played(MixPlan(MixKind.None, reason = "gapless album"), "a — A", "b — B")
        assertNull(KetmixLearning.last.value)
    }
}
