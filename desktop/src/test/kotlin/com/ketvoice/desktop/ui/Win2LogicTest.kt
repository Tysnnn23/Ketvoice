package com.ketvoice.desktop.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.desktop.platform.FrameRate
import com.ketvoice.desktop.platform.ScreenArea
import com.ketvoice.desktop.platform.WindowGlass
import com.ketvoice.desktop.ui.glass.GlassMode
import com.ketvoice.desktop.ui.glass.GlassPolicy
import com.ketvoice.desktop.ui.glass.GlassShader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Windows 2.0's interface logic without a window: the glass's fallbacks, the layout, the island, the motion arithmetic. */
class Win2LogicTest {
    private val win11 = WindowGlass.SystemLook(windows = true, build = 22631, transparencyEffects = true, highContrast = false)
    private fun inputs(system: WindowGlass.SystemLook = win11, enabled: Boolean = true, api: String? = "DIRECT3D", shader: Boolean = true) =
        GlassPolicy.Inputs(enabled, system, api, shader)

    // ── Glass ──

    @Test fun liquidGlassOnlyOverWindows11WithAGpuAndACompiledShader() {
        assertEquals(GlassMode.LIQUID, GlassPolicy.choose(inputs()))
        assertEquals(GlassMode.LIQUID, GlassPolicy.choose(inputs(api = "OPENGL")))
        // Before the first frame the renderer is not known yet: the window starts liquid when everything else allows it.
        assertEquals(GlassMode.LIQUID, GlassPolicy.choose(inputs(api = null)))
        assertEquals(GlassMode.LIQUID, GlassPolicy.choose(inputs(system = win11.copy(build = 22000))))
    }

    @Test fun theShaderFailingOrTheSettingOffOrWindows10FallBackToTheBlur() {
        assertEquals(GlassMode.FROSTED, GlassPolicy.choose(inputs(shader = false)))
        assertEquals(GlassMode.FROSTED, GlassPolicy.choose(inputs(enabled = false)))
        assertEquals(GlassMode.FROSTED, GlassPolicy.choose(inputs(system = win11.copy(build = 19045))))
        assertEquals(GlassMode.FROSTED, GlassPolicy.choose(inputs(system = win11.copy(transparencyEffects = false))))
        assertEquals(GlassMode.FROSTED, GlassPolicy.choose(inputs(system = win11.copy(windows = false))))
    }

    @Test fun highContrastAndSoftwareRenderingGetTheFlatTint() {
        assertEquals(GlassMode.TINTED, GlassPolicy.choose(inputs(system = win11.copy(highContrast = true))))
        assertEquals(GlassMode.TINTED, GlassPolicy.choose(inputs(api = "SOFTWARE_FAST")))
        assertEquals(GlassMode.TINTED, GlassPolicy.choose(inputs(api = "SOFTWARE_COMPAT", shader = false)))
        assertTrue(GlassPolicy.isSoftware("software_fast"))
        assertFalse(GlassPolicy.isSoftware("DIRECT3D"))
    }

    @Test fun theShaderCompilesAndItsEffectIsReusedWhileTheSurfaceStaysTheSame() {
        assertTrue(GlassShader.ok, "SkSL refused: ${GlassShader.failure}")
        val p = GlassShader.Params(Size(300f, 200f), 24f, refraction = 20f, bevel = 24f, frost = 10f)
        val a = assertNotNull(GlassShader.effect(p))
        assertSame(a, GlassShader.effect(p.copy()))
        assertNotNull(GlassShader.effect(p.copy(lens = Offset(40f, 20f), zoom = 1.2f, lensRadius = 30f)))
    }

    // ── Layout ──

    @Test fun theSidebarKeepsItsIconsInANarrowWindow() {
        assertTrue(ShellMetrics.collapsed(940.dp))
        assertFalse(ShellMetrics.collapsed(1440.dp))
        assertEquals(ShellMetrics.SIDEBAR_ICONS, ShellMetrics.sidebar(940.dp, 260.dp))
        assertFalse(ShellMetrics.collapsed(1024.dp))
        assertEquals(260.dp, ShellMetrics.sidebar(1440.dp, 260.dp))
    }

    @Test fun thePanelOpensClosesAndResizesWithinItsLimits() {
        val s = ShellState()
        assertNull(s.panel)
        s.toggle("lyrics"); assertEquals("lyrics", s.panel)
        s.toggle("queue"); assertEquals("queue", s.panel)
        s.toggle("queue"); assertNull(s.panel)
        s.open("artist"); s.close(); assertNull(s.panel)
        s.dragPanel((-1000).dp); assertEquals(ShellMetrics.PANEL_MAX, s.panelWidth)
        s.dragPanel(1000.dp); assertEquals(ShellMetrics.PANEL_MIN, s.panelWidth)
        // Never more than 40 % of a window, never less than its minimum.
        assertEquals(400.dp, ShellMetrics.panel(1000.dp, 540.dp))
        assertEquals(ShellMetrics.PANEL_MIN, ShellMetrics.panel(600.dp, 540.dp))
        assertEquals(ShellState(Panel.LYRICS.tab).panel, "lyrics")
        assertNull(Panel.NONE.tab)
    }

    @Test fun thePagesKeepClearOfTheGlass() {
        val closed = ShellMetrics.insets(1440.dp, 248.dp, panelOpen = false, panelWanted = 360.dp)
        assertEquals(ShellMetrics.MARGIN + 248.dp + ShellMetrics.GAP, closed.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, closed.calculateRightPadding(LayoutDirection.Ltr))
        assertTrue(closed.calculateBottomPadding() > ShellMetrics.ISLAND_HEIGHT)
        val open = ShellMetrics.insets(940.dp, 248.dp, panelOpen = true, panelWanted = 360.dp)
        assertEquals(ShellMetrics.MARGIN + ShellMetrics.SIDEBAR_ICONS + ShellMetrics.GAP, open.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(ShellMetrics.MARGIN + 360.dp + ShellMetrics.GAP, open.calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test fun theIslandGrowsUpwardWhenOpenAndNeverOutgrowsItsRoom() {
        assertEquals(ShellMetrics.ISLAND_WIDTH, ShellMetrics.island(1200.dp, open = false))
        // Open, it grows upward only: nothing moves sideways under the pointer.
        assertEquals(ShellMetrics.island(1200.dp, open = false), ShellMetrics.island(1200.dp, open = true))
        assertEquals(600.dp - ShellMetrics.GAP * 2, ShellMetrics.island(600.dp, open = true))
        assertEquals(320.dp, ShellMetrics.island(200.dp, open = false))
        assertTrue(ShellMetrics.ISLAND_OPEN_HEIGHT > ShellMetrics.ISLAND_HEIGHT)
    }

    @Test fun aNarrowIslandKeepsFewerButtonsButAlwaysTheQueue() {
        val all = listOf("lyrics", "queue", "account", "output")
        assertEquals(all, IslandFit.buttons(all, 680.dp))
        val few = IslandFit.buttons(listOf("lyrics", "account", "output", "queue"), 368.dp)
        assertEquals(2, few.size)
        assertTrue("queue" in few)
    }

    @Test fun theFullPlayerUnfoldsFromTheIslandsPlace() {
        val from = Rect(400f, 800f, 1040f, 868f)
        val to = Rect(0f, 0f, 1440f, 900f)
        assertEquals(from, Unfold.rect(from, to, 0f))
        assertEquals(to, Unfold.rect(from, to, 1f))
        val half = Unfold.rect(from, to, .5f)
        assertEquals(200f, half.left); assertEquals(400f, half.top)
        assertEquals(34f, Unfold.radius(34f, 0f)); assertEquals(0f, Unfold.radius(34f, 1f))
    }

    @Test fun homesHeroResumesTheLastTitleElsePicksForTheListener() {
        val history = Fixtures.tracks.take(3)
        val forYou = Fixtures.tracks.drop(10).take(4)
        val resume = assertNotNull(HomeHeroPick.pick(history, forYou))
        assertTrue(resume.resume); assertEquals(history[0], resume.track)
        val fresh = assertNotNull(HomeHeroPick.pick(emptyList(), forYou))
        assertFalse(fresh.resume); assertEquals(forYou[0], fresh.track)
        assertNull(HomeHeroPick.pick(emptyList(), emptyList()))
        assertTrue(HomeHeroPick.showCover(1100f, 280f))
        assertFalse(HomeHeroPick.showCover(800f, 280f))
    }

    // ── Motion ──

    @Test fun theWheelsGlideIsTheSameAtAnyRefreshRate() {
        fun left(hz: Int, seconds: Float): Float {
            var pending = 300f
            repeat((seconds * hz).toInt()) { pending -= FluidScroll.step(pending, 1f / hz) }
            return pending
        }
        val at60 = left(60, .25f); val at144 = left(144, .25f); val at240 = left(240, .25f)
        assertTrue(abs(at60 - at144) < .5f && abs(at144 - at240) < .5f, "$at60 $at144 $at240")
        assertTrue(at60 < 300f * .1f, "most of a notch is done in a quarter second")
    }

    @Test fun edgesStretchLessAndLessAndComeBack() {
        val limit = 100f
        assertEquals(0f, FluidScroll.rubber(0f, limit))
        val a = FluidScroll.rubber(50f, limit); val b = FluidScroll.rubber(500f, limit); val c = FluidScroll.rubber(50_000f, limit)
        assertTrue(a in 0f..b && b < c && c < limit)
        assertTrue(FluidScroll.rubber(-80f, limit) < 0f)
        var over = 120f
        repeat(60) { over = FluidScroll.relax(over, 1f / 60) }
        assertEquals(0f, over)
    }

    @Test fun theMagnetPullsAFewPixelsAtMost() {
        val size = IntSize(40, 40)
        assertEquals(Offset.Zero, Magnet.pull(Offset(20f, 20f), size, .2f, 5f))
        assertEquals(Offset(2f, 0f), Magnet.pull(Offset(30f, 20f), size, .2f, 5f))
        assertTrue(Magnet.pull(Offset(400f, 400f), size, .2f, 5f).getDistance() <= 5.001f)
    }

    @Test fun slidersStretchPastTheirEndsALittle() {
        assertEquals(0f, ElasticSlider.pull(0f, 200f))
        assertTrue(ElasticSlider.pull(400f, 200f) in 0f..(200f * ElasticSlider.MAX))
        assertTrue(ElasticSlider.pull(-400f, 200f) < 0f)
        assertTrue(ElasticSlider.thin(12f, 200f) < 1f)
        assertEquals(1f, ElasticSlider.thin(0f, 200f))
    }

    @Test fun menusGrowOutOfTheirButton() {
        val o = MenuGrow.origin(IntOffset(300, 100), IntOffset(200, 100), Size(200f, 300f))
        assertEquals(.5f, o.pivotFractionX); assertEquals(0f, o.pivotFractionY)
        val below = MenuGrow.origin(IntOffset(300, 500), IntOffset(200, 100), Size(200f, 300f))
        assertEquals(1f, below.pivotFractionY)
        assertEquals(0f, MenuGrow.origin(null, IntOffset.Zero, Size(10f, 10f)).pivotFractionX)
    }

    @Test fun theLensLeansTowardsThePointerAndStretchesWithSpeed() {
        val lens = SideLens()
        lens.rows["a"] = Rect(0f, 0f, 200f, 38f); lens.rows["b"] = Rect(0f, 40f, 200f, 78f)
        lens.selected = "a"
        assertEquals(lens.rows["a"], lens.target())
        lens.hovered = "b"; lens.pointer = 70f
        val t = assertNotNull(lens.target())
        assertTrue(t.center.y > 59f && t.center.y < 70f, "${t.center}")
        lens.pointer = null
        assertEquals(lens.rows["a"], lens.target())
        assertEquals(1f, LensMath.stretch(0f))
        assertEquals(1f + LensMath.STRETCH, LensMath.stretch(1e6f))
    }

    @Test fun pageTransitionsStandStillUnderReducedMotion() {
        val still = SharedMotion.pages(1, reduced = true)
        assertEquals(EnterTransition.None, still.targetContentEnter)
        assertTrue(SharedMotion.pages(1, reduced = false).targetContentEnter != EnterTransition.None)
        assertEquals(EnterTransition.None, SharedMotion.player(reduced = true).targetContentEnter)
        assertEquals("cover:album:fx-album-0", SharedMotion.albumKey(Fixtures.refs[0]))
        assertEquals("cover:album:T|A", SharedMotion.albumKey(AlbumRef(null, "T", "A", null)))
    }

    @Test fun navigationRemembersWhichWayItWent() {
        val nav = Navigator(Route.Home)
        nav.push(Route.Album(Fixtures.refs[0])); assertEquals(1, nav.direction)
        nav.back(); assertEquals(-1, nav.direction)
        nav.root(Route.Extra("sources")); assertEquals(0, nav.direction)
        assertEquals("extra:sources", sideKey(nav.section))
        assertEquals("playlist:p1", sideKey(Route.Playlist("p1")))
    }

    // ── Frame rate ──

    @Test fun theFrameRateIsReadFromTheFramesAndAHeldRateIsNamed() {
        val at144 = (0 until 50).map { it * 6_944_444L }
        assertEquals(144.0, FrameRate.rate(at144), .5)
        val at60 = (0 until 50).map { it * 16_666_667L }
        assertTrue("held at 60" in FrameRate.verdict(FrameRate.rate(at60), 144))
        assertTrue("at the display's rate" in FrameRate.verdict(143.5, 144))
        assertTrue("unknown" in FrameRate.verdict(60.0, 0))
        assertEquals(0.0, FrameRate.rate(listOf(1L)))
    }

    // ── Compact player ──

    @Test fun theCompactPlayerOpensWhereItWasLeftWhileThatIsOnAScreen() {
        val screens = listOf(ScreenArea(0f, 0f, 1920f, 1040f))
        assertEquals(100f to 200f, CompactPlayer.place(100f to 200f, screens))
        // Unplugged monitor: back to the bottom right of the first screen.
        val home = assertNotNull(CompactPlayer.place(3000f to 200f, screens))
        assertTrue(home.first + CompactPlayer.WIDTH <= 1920f && home.second + CompactPlayer.HEIGHT <= 1040f)
        assertEquals(home, CompactPlayer.place(null, screens))
        assertNull(CompactPlayer.place(10f to 10f, emptyList()))
    }

    @Test fun theCompactPlayerShowsTheLineBeingSung() {
        val lines = listOf(MusicApi.LyricLine(1_000, "Un"), MusicApi.LyricLine(5_000, "Deux"))
        val state = LyricsRepo.State(loading = false, lines = lines, isTimed = true, trackId = "t")
        assertNull(CompactLyric.lineAt(state, 500))
        assertEquals("Un", CompactLyric.lineAt(state, 2_000))
        assertEquals("Deux", CompactLyric.lineAt(state, 6_000))
    }
}
