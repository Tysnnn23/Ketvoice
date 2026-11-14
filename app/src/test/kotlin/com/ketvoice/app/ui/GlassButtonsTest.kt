package com.ketvoice.app.ui

import androidx.compose.ui.graphics.Color
import com.ketvoice.app.theme.DesignTokens
import com.ketvoice.app.ui.common.GLASS_SWELL
import com.ketvoice.app.ui.common.glassPressScale
import com.ketvoice.app.ui.common.onAccent
import org.junit.Assert.*
import org.junit.Test

class GlassButtonsTest {
    @Test fun liquid27GlassSwellsEvenlyUnderTheFinger() {
        assertEquals(1f to 1f, glassPressScale(0f, liquid27 = true))
        val (width, height) = glassPressScale(1f, liquid27 = true)
        assertEquals(1f + GLASS_SWELL, width, 1e-6f)
        assertEquals("The swell is uniform", width, height, 0f)
        // The bounce may pass the resting pressure a little, never without bound.
        assertTrue(glassPressScale(5f, liquid27 = true).first <= 1f + GLASS_SWELL * 1.2f + 1e-6f)
        assertEquals(1f to 1f, glassPressScale(Float.NaN, liquid27 = true))
    }

    @Test fun theOtherStylesStillGiveWay() {
        val (width, height) = glassPressScale(1f, liquid27 = false)
        assertTrue(width < 1f && height < 1f)
        assertTrue("They flatten more in width than in height", width < height)
        assertEquals(1f to 1f, glassPressScale(0f, liquid27 = false))
    }

    @Test fun textOnTheProminentButtonStaysReadable() {
        assertEquals(Color.Black, onAccent(Color(0xFFFFD60A)))
        assertEquals(Color.White, onAccent(Color(0xFF0A2540)))
    }

    @Test fun navigationMorphIsThreePercentSlowerThanBefore() {
        // A spring's duration scales with 1 / sqrt(stiffness): 460 was the original value.
        val slower = kotlin.math.sqrt(460f / DesignTokens.Motion.CHROME_STIFFNESS)
        assertEquals(1.03f, slower, .0005f)
    }
}
