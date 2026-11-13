package com.ketvoice.app.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GlassFusionAndTintTest {
    @Test fun bridgeReachIsABellOverTheMorph() {
        assertEquals(0f, fusionStrength(0f, 60f), 1e-3f)
        assertEquals(60f, fusionStrength(.5f, 60f), 1e-3f)
        assertEquals(0f, fusionStrength(1f, 60f), 1e-3f)
        assertTrue(fusionStrength(.25f, 60f) in 30f..60f)
        assertEquals(0f, fusionStrength(Float.NaN, 60f), 0f)
        assertEquals(0f, fusionStrength(.5f, -4f), 0f)
    }

    @Test fun shapesAreMadeLocalAndCappedAtThree() {
        val shapes = listOf(
            FusedShape(Rect(10f, 110f, 60f, 160f), 25f), FusedShape(Rect(70f, 110f, 370f, 160f), 25f),
            FusedShape(Rect(380f, 110f, 430f, 160f), 25f), FusedShape(Rect(0f, 0f, 5f, 5f), 2f),
            FusedShape(Rect.Zero, 0f))
        val local = fusedShapesFor(shapes, Offset(10f, 100f))
        assertEquals(MAX_FUSED_SHAPES, local.size)
        assertEquals(Rect(60f, 10f, 360f, 60f), local.first().bounds)
        assertTrue(local.none { it.bounds.width <= 5f })
    }

    @Test fun coverTintIsSmallBoundedAndOptional() {
        val white = floatArrayOf(1f, 1f, 1f)
        val red = Color(1f, 0f, 0f)
        assertSame(white, GlassTint.tinted(white, red, dark = false, enabled = false))
        assertSame(white, GlassTint.tinted(white, null, dark = false, enabled = true))
        assertSame(white, GlassTint.tinted(white, red, dark = false, enabled = true, strength = 0f))
        val light = GlassTint.tinted(white, red, dark = false, enabled = true)
        assertEquals(1f, light[0], 1e-4f)
        assertTrue("Light glass stays light", light[1] > .85f && light[2] > .85f && light[1] < 1f)
        val dark = GlassTint.tinted(floatArrayOf(.24f, .24f, .27f), red, dark = true, enabled = true)
        assertTrue("Dark glass stays dark", dark.all { it < .4f } && dark[0] > dark[1])
        val half = GlassTint.tinted(white, red, dark = false, enabled = true, strength = .5f)
        assertTrue(half[1] > light[1])
    }

    /** A misspelt uniform makes setFloatUniform throw and silently drops the fused pass. */
    @Test fun everyFusedUniformSetFromKotlinIsDeclared() {
        fun names(path: String) = Regex("setFloatUniform\\(\"(\\w+)\"")
            .findAll(File(path).readText()).map { it.groupValues[1] }.toSet()
        val set = names("src/main/kotlin/com/ketvoice/app/glass/Liquid27Fusion.kt") +
            names("src/main/kotlin/com/ketvoice/app/glass/Liquid27Shader.kt") - setOf("size", "cornerRadii")
        val declared = Regex("uniform\\s+\\w+\\s+(\\w+);").findAll(LIQUID27_FUSED_AGSL).map { it.groupValues[1] }.toSet()
        assertEquals(declared - "content", set)
    }
}
