package com.ketvoice.app.ui.player

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class SharedPlayerTransitionTest {
    private val mini = Rect(20f, 2000f, 152f, 2132f)

    @Test fun artworkStartsOnTheMiniCoverAndRestsUntransformed() {
        val start = sharedCoverMorph(mini, 1080f, 1200f, 0f)
        assertEquals(132f / 1080f, start.scale, 1e-4f)
        // The scaled frame is centred on the mini cover.
        assertEquals(mini.center.x, 540f + start.translationX, 1e-3f)
        assertEquals(mini.center.y, 600f + start.translationY, 1e-3f)
        assertEquals(CoverMorph(1f, 0f, 0f), sharedCoverMorph(mini, 1080f, 1200f, 1f))
    }

    @Test fun artworkMovesMonotonicallyTowardRest() {
        var last = sharedCoverMorph(mini, 1080f, 1200f, 0f)
        for (step in 1..10) {
            val next = sharedCoverMorph(mini, 1080f, 1200f, step / 10f)
            assertTrue(next.scale >= last.scale)
            assertTrue(kotlin.math.abs(next.translationY) <= kotlin.math.abs(last.translationY))
            last = next
        }
    }

    @Test fun missingOrInvalidInputsLeaveTheArtworkAtRest() {
        val rest = CoverMorph(1f, 0f, 0f)
        assertEquals(rest, sharedCoverMorph(null, 1080f, 1200f, .3f))
        assertEquals(rest, sharedCoverMorph(Rect.Zero, 1080f, 1200f, .3f))
        assertEquals(rest, sharedCoverMorph(mini, 0f, 1200f, .3f))
        assertEquals(rest, sharedCoverMorph(mini, 1080f, 1200f, Float.NaN))
    }

    @Test fun controlsFollowTheArtwork() {
        assertEquals(0f, controlsReveal(0f), 0f)
        assertEquals(0f, controlsReveal(.2f), 0f)
        assertEquals(.5f, controlsReveal(.5f), 1e-4f)
        assertEquals(1f, controlsReveal(.8f), 1e-4f)
        assertEquals(1f, controlsReveal(1f), 0f)
    }

    @Test fun heldBackGestureNeverPullsThePlayerPastAQuarter() {
        assertEquals(0f, predictiveBackTravel(0f, 2400f), 0f)
        assertEquals(2400f * .22f, predictiveBackTravel(1f, 2400f), 1e-3f)
        assertEquals(2400f * .22f, predictiveBackTravel(9f, 2400f), 1e-3f)
        assertEquals(0f, predictiveBackTravel(Float.NaN, 2400f), 0f)
        assertEquals(0f, predictiveBackTravel(.5f, -10f), 0f)
    }

    @Test fun meshColoursStayDarkEnoughForWhiteText() {
        val colours = meshColors(androidx.compose.ui.graphics.Color(.5f, .2f, .1f), androidx.compose.ui.graphics.Color(1f, .9f, .2f),
            androidx.compose.ui.graphics.Color(1f, 1f, 1f) to androidx.compose.ui.graphics.Color(.9f, .9f, 1f))
        assertEquals(4, colours.size)
        colours.forEach { c -> assertTrue(.2126f * c.red + .7152f * c.green + .0722f * c.blue < .6f) }
        assertEquals(4, meshColors(androidx.compose.ui.graphics.Color.Black, null, null).size)
    }

    @Test fun meshOnlyAnimatesWhenItIsAllowedTo() {
        assertEquals(MeshPolicy(true, true), meshPolicy(34, true, false, false, true, false))
        assertEquals(MeshPolicy(true, false), meshPolicy(34, true, false, true, true, false))
        assertEquals(MeshPolicy(true, false), meshPolicy(34, true, false, false, false, false))
        assertEquals(MeshPolicy(true, false), meshPolicy(34, true, false, false, true, true))
        assertEquals(MeshPolicy(false, false), meshPolicy(32, true, false, false, true, false))
        assertEquals(MeshPolicy(false, false), meshPolicy(34, false, false, false, true, false))
        assertEquals(MeshPolicy(false, false), meshPolicy(34, true, true, false, true, false))
    }
}
