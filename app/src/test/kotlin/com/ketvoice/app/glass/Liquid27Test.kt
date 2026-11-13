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
        assertEquals(GlassLight.REST_DEGREES, GlassLight.angleForGravity(0f), 0f)
        assertEquals(GlassLight.REST_DEGREES, GlassLight.angleForGravity(Float.NaN), 0f)
        val left = GlassLight.angleForGravity(-SensorManager.GRAVITY_EARTH * 3f)
        val right = GlassLight.angleForGravity(SensorManager.GRAVITY_EARTH * 3f)
        assertTrue(left < GlassLight.REST_DEGREES && right > GlassLight.REST_DEGREES)
        assertTrue(right - left <= 64.01f)
        val (x, y) = GlassLight.direction(GlassLight.REST_DEGREES)
        assertEquals(1f, x * x + y * y, 1e-4f)
        assertTrue("Default light comes from the top-left", x < 0f && y < 0f)
    }

    @Test fun idleJitterDoesNotRedrawGlass() {
        assertEquals(45f, GlassLight.next(45f, 45.5f), 0f)
        assertTrue(GlassLight.next(45f, 70f) > 45f)
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
