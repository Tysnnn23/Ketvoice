package com.ketvoice.app.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 8.0 « Vrai verre réfractif »: which material each surface gets per Android version and mode, and the AGSL
 * source checked on the JVM (the shader itself only compiles on a device): every uniform Kotlin sets exists,
 * none is declared twice, the braces balance, and the rainbow stays on the rim.
 */
class GlassMaterialPathTest {
    private fun path(api: Int = 35, performance: Boolean = false, reducedTransparency: Boolean = false,
                     backdrop: Boolean = true, reducedMotion: Boolean = false, lowMemory: Boolean = false): GlassMaterialPath {
        val mode = glassRenderMode(api, backdrop, enabled = true, captured = false, opaque = false, reduceBlur = false,
            highPerformance = performance, lowMemory = lowMemory, reducedMotion = reducedMotion)
        return glassMaterialPath(api, mode, performance, reducedTransparency)
    }

    @Test fun eachAndroidVersionAndModeGetsItsMaterial() {
        assertEquals(GlassMaterialPath.Refractive, path(api = 33))
        assertEquals(GlassMaterialPath.Refractive, path(api = 36))
        assertEquals(GlassMaterialPath.RefractiveLite, path(api = 34, performance = true))
        assertEquals(GlassMaterialPath.Blur, path(api = 31))
        assertEquals(GlassMaterialPath.Blur, path(api = 32, performance = true))
        assertEquals(GlassMaterialPath.SoftBlur, path(api = 30))
        assertEquals(GlassMaterialPath.Blur, path(reducedMotion = true))
        assertEquals(GlassMaterialPath.Blur, path(lowMemory = true))
        assertEquals(GlassMaterialPath.Static, path(backdrop = false))
        // Reduced transparency wins over every live path, Android 11 included.
        assertEquals(GlassMaterialPath.Opaque, path(reducedTransparency = true))
        assertEquals(GlassMaterialPath.Opaque, path(api = 30, reducedTransparency = true))
        assertEquals(GlassMaterialPath.Static, path(backdrop = false, reducedTransparency = true))
    }

    @Test fun performanceDropsDispersionAndReducedTransparencyDropsTheLens() {
        val full = RefractionSpec.forPath(GlassMaterialPath.Refractive)
        val lite = RefractionSpec.forPath(GlassMaterialPath.RefractiveLite)
        val opaque = RefractionSpec.forPath(GlassMaterialPath.Opaque)
        assertEquals(1f, full.dispersion, 0f); assertEquals(1f, full.quality, 0f)
        assertEquals(0f, lite.dispersion, 0f); assertEquals(0f, lite.quality, 0f)
        assertTrue("cheaper, still a lens", lite.lens in .4f..<1f)
        assertEquals(0f, opaque.lens, 0f); assertEquals(0f, opaque.dispersion, 0f)
        // Same instance every time: nothing allocated per frame.
        assertTrue(full === RefractionSpec.forPath(GlassMaterialPath.Refractive))
    }

    @Test fun reducedTransparencyFollowsTheSliderAndHighContrast() {
        assertFalse(glassReducedTransparency(GlassClarity.DEFAULT, highContrast = false))
        assertFalse(glassReducedTransparency(GlassClarity.FROSTED, highContrast = false))
        assertTrue(glassReducedTransparency(GlassClarity.TINTED, highContrast = false))
        assertTrue(glassReducedTransparency(GlassClarity.MAX, highContrast = false))
        assertTrue(glassReducedTransparency(GlassClarity.DEFAULT, highContrast = true))
        assertFalse(glassReducedTransparency(Float.NaN, highContrast = false))
    }

    private fun declared(agsl: String) = Regex("uniform\\s+\\w+\\s+(\\w+);").findAll(agsl).map { it.groupValues[1] }.toList()

    @Test fun everyUniformTheMaterialSetsExistsOnceInBothShaders() {
        val dir = "src/main/kotlin/com/ketvoice/app/glass"
        val kotlin = File("$dir/Liquid27Shader.kt").readText() + File("$dir/Liquid27Fusion.kt").readText()
        val set = (Regex("setFloatUniform\\(\"(\\w+)\"").findAll(kotlin) + Regex("rect\\(\"(\\w+)\",").findAll(kotlin))
            .map { it.groupValues[1] }.toSet()
        for ((name, agsl, own) in listOf(
            Triple("single", LIQUID27_AGSL, setOf("size", "cornerRadii")),
            Triple("fused", LIQUID27_FUSED_AGSL, setOf("rect0", "rect1", "rect2", "radii", "present", "fuse")))) {
            val names = declared(agsl)
            assertEquals("$name: no uniform declared twice", names.size, names.toSet().size)
            val shared = set - setOf("size", "cornerRadii", "rect0", "rect1", "rect2", "radii", "present", "fuse")
            assertEquals("$name: every uniform set from Kotlin is declared", emptySet<String>(), (shared + own) - names.toSet())
            assertTrue("$name: the 8.0 quality switch is declared", "quality" in names)
        }
    }

    @Test fun agslSourceIsWellFormed() {
        for (agsl in listOf(LIQUID27_AGSL, LIQUID27_FUSED_AGSL)) {
            val code = agsl.lines().joinToString("\n") { it.substringBefore("//") }
            assertEquals("braces balance", code.count { it == '{' }, code.count { it == '}' })
            assertEquals("parentheses balance", code.count { it == '(' }, code.count { it == ')' })
            assertEquals("one entry point", 1, Regex("float4\\s+main\\(float2\\s+coord\\)").findAll(code).count())
            assertTrue("the content is sampled as a child shader", "uniform shader content;" in code)
            // The colour split grows only on the outer part of the lens band.
            assertTrue(Regex("split\\s*=\\s*dispersion\\s*\\*\\s*smoothstep\\(0\\.6,\\s*1\\.0,\\s*x\\)").containsMatchIn(code))
            assertTrue("the performance mode takes one luminance tap", "if (quality > 0.5)" in code)
        }
    }

    @Test fun theRainbowStaysFaint() {
        // At most a fraction of a percent of the bend: a rim fringe, not neon.
        assertTrue(com.ketvoice.app.theme.DesignTokens.Glass.DISPERSION * 1.5f <= .01f)
    }
}
