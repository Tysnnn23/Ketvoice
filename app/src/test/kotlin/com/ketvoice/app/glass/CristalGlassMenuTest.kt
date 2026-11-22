package com.ketvoice.app.glass

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.ui.common.CristalMenuMotion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cristal's menus (8.4): their springs, their timings and where they open. */
class CristalGlassMenuTest {
    @Test fun aPageMenuGrowsOnACriticalSpringAndClosesIn170Ms() {
        assertEquals(spring<Float>(Spring.DampingRatioNoBouncy, 600f), CristalMenuMotion.pageOpen(false))
        assertEquals(tween<Float>(170, easing = FastOutSlowInEasing), CristalMenuMotion.pageClose(false))
        assertEquals(tween<Float>(120, easing = LinearEasing), CristalMenuMotion.pageOpen(true))
        assertEquals(tween<Float>(100, easing = FastOutSlowInEasing), CristalMenuMotion.pageClose(true))
        assertEquals(.3f, CristalMenuMotion.pageScale(0f), 1e-6f); assertEquals(1f, CristalMenuMotion.pageScale(1f), 1e-6f)
        assertEquals("opaque by 40 % of the way", 1f, CristalMenuMotion.pageAlpha(.4f), 1e-6f)
        assertEquals(.5f, CristalMenuMotion.pageAlpha(.2f), 1e-6f)
    }

    @Test fun aHeldMenuOpensOnASofterSpring() {
        assertEquals(spring<Float>(.72f, 420f), CristalMenuMotion.heldOpen(false))
        assertEquals(tween<Float>(230, easing = FastOutSlowInEasing), CristalMenuMotion.heldClose(false))
        assertEquals(tween<Float>(140, easing = LinearEasing), CristalMenuMotion.heldOpen(true))
        assertEquals(tween<Float>(110, easing = FastOutSlowInEasing), CristalMenuMotion.heldClose(true))
        assertEquals(.55f, CristalMenuMotion.heldMenuScale(0f), 1e-6f)
        assertEquals(0f, CristalMenuMotion.heldMenuAlpha(.1f), 1e-6f); assertEquals(1f, CristalMenuMotion.heldMenuAlpha(1f), 1e-6f)
        assertEquals(1f, CristalMenuMotion.plateAlpha(.34f), 1e-2f)
    }

    @Test fun aPageMenuHangsFromItsButtonInTheTopHalfAndSitsLowOtherwise() {
        // 400 × 900 px window, 256 px panel, 300 px tall, 14 px margins.
        val high = CristalMenuMotion.placePage(Rect(330f, 60f, 378f, 108f), 400, 900, 256, 300, 14, 32, 876)
        assertTrue(high.hangsDown); assertEquals(378 - 256, high.x); assertEquals(60, high.y)
        val low = CristalMenuMotion.placePage(Rect(330f, 700f, 378f, 748f), 400, 900, 256, 300, 14, 32, 876)
        assertFalse(low.hangsDown); assertEquals(876 - 300, low.y)
        val none = CristalMenuMotion.placePage(null, 400, 900, 256, 300, 14, 32, 876)
        assertEquals(400 - 14 - 256, none.x); assertFalse(none.hangsDown)
        val left = CristalMenuMotion.placePage(Rect(4f, 60f, 52f, 108f), 400, 900, 256, 300, 14, 32, 876)
        assertEquals("kept on screen", 14, left.x)
    }

    @Test fun aHeldTitleIsLiftedToTheWindowsWidthAndItsMenuHangsUnderIt() {
        val item = Rect(0f, 300f, 400f, 368f)
        val at = CristalMenuMotion.placeHeld(item, 400, 416, 84, 256, 380, 12, 10, 32, 892)
        assertEquals((400f - 24f) / 416f, at.scale, 1e-4f)
        assertTrue(at.below)
        assertTrue("the menu is under the plate", at.menuY >= 300)
        assertEquals("from where the title was", 0f, at.fromX, 1e-3f)
        val bottom = CristalMenuMotion.placeHeld(Rect(0f, 820f, 400f, 888f), 400, 416, 84, 256, 380, 12, 10, 32, 892)
        assertFalse("no room below: above", bottom.below)
        assertTrue(bottom.menuY + 380 <= bottom.plateY + 84)
    }

    @Test fun aPageMenuWaitsTwoFramesAndAtMost150MsForItsStill() = runBlocking {
        val api = GlassCompat.api
        val glass = AppSettings.liquidGlass.value
        val clock = object : MonotonicFrameClock {
            var frames = 0
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R { frames++; return onFrame(frames * 16_000_000L) }
        }
        try {
            GlassCompat.api = 35
            AppSettings.liquidGlass.value = true
            FrozenStills.taking.intValue = 1
            val start = System.nanoTime()
            withContext(clock) { CristalMenuMotion.awaitStill() }
            val waited = (System.nanoTime() - start) / 1_000_000
            assertEquals(2, clock.frames)
            assertTrue("waited $waited ms", waited in 140..1500)
            FrozenStills.taking.intValue = 0
            val again = System.nanoTime()
            withContext(clock) { CristalMenuMotion.awaitStill() }
            assertTrue("no still on its way: no wait", (System.nanoTime() - again) / 1_000_000 < 100)
        } finally {
            GlassCompat.api = api
            AppSettings.liquidGlass.value = glass
            FrozenStills.taking.intValue = 0
        }
    }
}
