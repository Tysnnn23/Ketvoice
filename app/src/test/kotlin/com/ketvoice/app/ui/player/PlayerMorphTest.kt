package com.ketvoice.app.ui.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

/** 8.0: the geometry of the player that morphs (artwork ↔ small header). */
class PlayerMorphTest {
    private val frame = Size(800f, 1000f)
    private val square = PlayerMorph.square(800f, 150f, 850f, 24f)
    private val slot = Rect(64f, 180f, 176f, 292f)

    @Test fun squareIsCentredInThePlaceOfTheWholeCanvas() {
        assertEquals(Rect(50f, 150f, 750f, 850f), square)
        assertEquals(Rect(100f, 0f, 700f, 600f), PlayerMorph.square(800f, 0f, 600f, 24f))
    }

    @Test fun atRestNothingMovesAndTheWholeFrameShows() {
        val art = PlayerMorph.artwork(frame, square, slot, 16f, 0f)
        assertEquals(1f, art.scale, 1e-5f)
        assertEquals(0f, art.translationX, 1e-3f); assertEquals(0f, art.translationY, 1e-3f)
        assertEquals(Rect(0f, 0f, 800f, 1000f), art.clip)
        assertEquals(0f, art.radius, 1e-5f)
    }

    @Test fun inTheHeaderTheSquareLandsExactlyOnTheSlot() {
        val art = PlayerMorph.artwork(frame, square, slot, 16f, 1f)
        assertEquals(square, art.clip)
        // A point of the square, moved by the layer (origin at the top-left), lands on the slot.
        assertEquals(slot.left, art.translationX + art.scale * square.left, 1e-3f)
        assertEquals(slot.bottom, art.translationY + art.scale * square.bottom, 1e-3f)
        assertEquals(16f, art.radius * art.scale, 1e-3f)
        // A spring overshoot never inverts the clip.
        assertEquals(art, PlayerMorph.artwork(frame, square, slot, 16f, 1.02f))
    }

    @Test fun reducedMotionFadesThroughWithoutTravel() {
        assertEquals(0f, PlayerMorph.shape(.3f, true), 0f)
        assertEquals(1f, PlayerMorph.shape(.6f, true), 0f)
        assertEquals(0f, PlayerMorph.fade(.5f, true), 1e-5f)
        assertEquals(1f, PlayerMorph.fade(1f, true), 1e-5f)
        assertEquals(.3f, PlayerMorph.shape(.3f, false), 0f)
        assertEquals(1f, PlayerMorph.fade(.5f, false), 0f)
    }

    @Test fun aDragOpensTheLyricsWhenFarOrFlicked() {
        assertTrue(PlayerMorph.commits(.5f, 0f))
        assertTrue(PlayerMorph.commits(.1f, 2f))
        assertFalse(PlayerMorph.commits(.2f, .3f))
        assertFalse("a flick back down cancels", PlayerMorph.commits(.6f, -2f))
    }
}
