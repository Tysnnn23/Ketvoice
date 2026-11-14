package com.ketvoice.app.ui.player

import com.ketvoice.app.ui.common.swipeProgress
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class QueueGesturesTest {
    @Test fun aSwipeActsOnlyPastItsThreshold() {
        assertEquals(SwipeSkip.None, swipeSkip(0f, 100f))
        assertEquals(SwipeSkip.None, swipeSkip(-99f, 100f))
        assertEquals(SwipeSkip.Next, swipeSkip(-100f, 100f))
        assertEquals(SwipeSkip.Previous, swipeSkip(140f, 100f))
        assertEquals(SwipeSkip.None, swipeSkip(Float.NaN, 100f))
        assertEquals(SwipeSkip.None, swipeSkip(500f, 0f))
    }

    @Test fun swipedContentFadesButNeverDisappears() {
        assertEquals(1f, swipeAlpha(0f, 100f), 0f)
        assertTrue(swipeAlpha(100f, 100f) < 1f)
        assertEquals(swipeAlpha(-100f, 100f), swipeAlpha(100f, 100f), 0f)
        assertEquals(.15f, swipeAlpha(10_000f, 100f), 1e-4f)
        assertEquals(1f, swipeAlpha(50f, 0f), 0f)
    }

    @Test fun rowSwipeProgressReachesOneAtTheThreshold() {
        assertEquals(0f, swipeProgress(0f, 96f), 0f)
        assertEquals(.5f, swipeProgress(-48f, 96f), 1e-4f)
        assertEquals(1f, swipeProgress(200f, 96f), 0f)
        assertEquals(0f, swipeProgress(Float.NaN, 96f), 0f)
        assertEquals(0f, swipeProgress(10f, 0f), 0f)
    }

    @Test fun aDraggedRowMovesOnceItPassedHalfOfItsNeighbour() {
        assertEquals(0, queueDragStep(30f, aboveSize = 64, belowSize = 64))
        assertEquals(1, queueDragStep(33f, aboveSize = 64, belowSize = 64))
        assertEquals(-1, queueDragStep(-33f, aboveSize = 64, belowSize = 64))
        // The first and last rows have nothing to cross on one side.
        assertEquals(0, queueDragStep(-500f, aboveSize = null, belowSize = 64))
        assertEquals(0, queueDragStep(500f, aboveSize = 64, belowSize = null))
    }

    @Test fun aSavedQueueIsNamedWithItsDate() {
        assertEquals("Queue · 14 Nov", savedQueueName("Queue", LocalDate.of(2026, 11, 14), Locale.ENGLISH))
        assertTrue(savedQueueName("x".repeat(200), LocalDate.of(2026, 1, 2), Locale.ENGLISH).length <= 80)
    }

    @Test fun repeatControlNamesItsThreeStates() {
        assertEquals("Répéter", repeatLabel(repeatOne = false, repeatAll = false))
        assertEquals("Répéter la file", repeatLabel(repeatOne = false, repeatAll = true))
        assertEquals("Répéter le titre", repeatLabel(repeatOne = true, repeatAll = false))
        assertEquals("Répéter le titre", repeatLabel(repeatOne = true, repeatAll = true))
    }
}
