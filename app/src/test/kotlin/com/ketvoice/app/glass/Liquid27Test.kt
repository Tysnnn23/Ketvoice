package com.ketvoice.app.glass

import android.hardware.SensorManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Liquid27Test {
    @Test fun styleRangeIncludesLiquid27() {
        assertEquals(3, GlassStyles.LIQUID_27)
        assertEquals(GlassStyles.LIQUID_27, GlassStyles.MAX)
    }

    @Test fun blurGoesFromNearlyClearToFrostedAndStaysBounded() {
        for (role in GlassRole.values()) {
            val clear = liquid27BlurPx(0f, role)
            val middle = liquid27BlurPx(.5f, role)
            val tinted = liquid27BlurPx(1f, role)
            assertTrue(clear < middle && middle < tinted)
            assertEquals(clear, liquid27BlurPx(Float.NaN, role), 0f)
            assertEquals(tinted, liquid27BlurPx(7f, role), 0f)
        }
        assertTrue("Clear controls must not be frosted", liquid27BlurPx(0f, GlassRole.Control) <= 2f)
    }

    @Test fun tiltLightIsBoundedAndRestsWithoutSignal() {
        assertEquals(GlassLight.REST_DEGREES, GlassLight.degrees(0f), 0f)
        assertEquals(GlassLight.REST_DEGREES, GlassLight.degrees(Float.NaN), 0f)
        val left = GlassLight.degrees(-3f); val right = GlassLight.degrees(3f)
        assertTrue(left < GlassLight.REST_DEGREES && right > GlassLight.REST_DEGREES)
        assertTrue(right - left <= 64.01f)
        val (x, y) = GlassLight.direction(GlassLight.REST_DEGREES)
        assertEquals(1f, x * x + y * y, 1e-4f)
        assertTrue("Default light comes from the top-left", x < 0f && y < 0f)
        assertEquals(1f, GlassLight.glint(0f), 0f)
        assertTrue(GlassLight.glint(1f) > 1f && GlassLight.glint(-1f) < 1f && GlassLight.glint(9f) <= 1f + GlassLight.GLINT_RANGE)
        // One packed state holds both axes exactly.
        val packed = GlassLight.pack(-.25f, .75f)
        assertEquals(-.25f, GlassLight.tiltX(packed), 0f); assertEquals(.75f, GlassLight.tiltY(packed), 0f)
    }

    @Test fun tiltFilterFollowsMovementSmoothlyAndIgnoresJitter() {
        val filter = GlassTiltFilter()
        // Held upright and still: the first reading is the reference, nothing to draw.
        assertNull(filter.update(0f, .8f, .6f, 0f))
        repeat(20) { assertNull("A still phone never redraws glass", filter.update(0f, .8f, .6f, .02f)) }
        // Sub-threshold jitter is ignored.
        assertNull(filter.update(.0005f, .8f, .6f, .02f))
        // Right edge down: the up vector leans to −x; the light swings, gradually, never past 1.
        var last = 0f; var frames = 0
        repeat(15) {
            filter.update(-.30f, .76f, .58f, .02f)?.let { (x, _) -> assertTrue("Glides one way ($last → $x)", x >= last); last = x; frames++ }
        }
        assertTrue("Moves towards a full sideways tilt ($last)", last > .5f && last <= 1f)
        assertTrue("Several frames, not one jump ($frames)", frames > 3)
        // Held there, the light comes back to rest within a few seconds.
        repeat(600) { filter.update(-.30f, .76f, .58f, .02f)?.let { (x, _) -> last = x } }
        assertTrue("Recentres on how the phone is held ($last)", kotlin.math.abs(last) < .1f)
        // Garbage never reaches the light.
        assertNull(filter.update(Float.NaN, 1f, 0f, .02f)); assertNull(filter.update(0f, 0f, 0f, .02f))
    }

    @Test fun tiltIsOnlyWantedWhenNothingAsksForStillness() {
        assertTrue(GlassLight.tiltWanted(true, true, false, false, false))
        assertFalse("Setting off", GlassLight.tiltWanted(false, true, false, false, false))
        assertFalse("Glass off", GlassLight.tiltWanted(true, false, false, false, false))
        assertFalse("Reduced motion", GlassLight.tiltWanted(true, true, true, false, false))
        assertFalse("Performance mode", GlassLight.tiltWanted(true, true, false, true, false))
        assertFalse("Quest", GlassLight.tiltWanted(true, true, false, false, true))
    }

    @Test fun transparencyLevelsGoFromClearToTintedWithTheDefaultAsBefore() {
        val c = GlassClarity
        // The default is the look of earlier versions: no frost, no extra clearing, the 14 px floor.
        assertEquals(0f, c.frost(c.DEFAULT), 0f); assertEquals(0f, c.clarity(c.DEFAULT), 0f)
        assertEquals(Ket27Glass.MIN_BLUR_PX, c.minimumBlurPx(c.DEFAULT), 0f)
        assertEquals(GlassTuning.fallbackTint(0f, true), c.fallbackVeil(true, c.DEFAULT), 0f)
        assertEquals(0f, c.staticClearing(c.DEFAULT), 0f)
        assertEquals(1f, c.clarity(c.MIN), 0f); assertEquals(1f, c.frost(c.MAX), 0f)
        for (l in c.MIN until c.MAX) {
            assertTrue("frost rises", c.frost(l + 1) >= c.frost(l))
            assertTrue("clarity falls", c.clarity(l + 1) <= c.clarity(l))
            assertTrue("blur floor rises", c.minimumBlurPx(l + 1) >= c.minimumBlurPx(l))
            assertTrue("fallback veil rises", c.fallbackVeil(false, l + 1) > c.fallbackVeil(false, l))
            assertTrue(Ket27Glass.surface(l + 1) > Ket27Glass.surface(l))
        }
        assertEquals(c.MIN, c.sanitize(-4)); assertEquals(c.MAX, c.sanitize(99))
        // The continuous tint of 5.x lands on the nearest step; untouched stays the default.
        assertEquals(c.DEFAULT, c.fromLegacyTint(0f)); assertEquals(c.MAX, c.fromLegacyTint(1f))
        assertEquals(4, c.fromLegacyTint(.5f)); assertEquals(c.DEFAULT, c.fromLegacyTint(Float.NaN))
        assertEquals(7, (c.MIN..c.MAX).map(c::label).toSet().size)
    }

    /** A misspelt uniform makes setFloatUniform throw and silently drops to the fallback. */
    @Test fun everyUniformSetFromKotlinIsDeclaredInItsShader() {
        val source = File("src/main/kotlin/com/ketvoice/app/glass/Liquid27Shader.kt").readText()
        val set = Regex("setFloatUniform\\(\"(\\w+)\"").findAll(source).map { it.groupValues[1] }.toSet()
        val declared = Regex("uniform\\s+\\w+\\s+(\\w+);").findAll(LIQUID27_AGSL).map { it.groupValues[1] }.toSet()
        assertTrue(set.isNotEmpty())
        assertEquals(declared - "content", set)
    }
}
