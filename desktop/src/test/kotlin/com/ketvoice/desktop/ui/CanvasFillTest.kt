package com.ketvoice.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Frame
import com.ketvoice.app.data.KetvoiceApi
import com.ketvoice.app.data.MediaOverride
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.canvas.CanvasFixtures
import com.ketvoice.desktop.library.StudioContent
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Canvas in the full player fills its place edge to edge, sharp, framed and switched as the Studio says. */
@OptIn(ExperimentalTestApi::class)
class CanvasFillTest {
    @AfterTest fun tearDown() {
        MediaOverrides.replaceForTest(emptyList())
        val until = System.currentTimeMillis() + 5_000
        while (CanvasRepo.loading.value.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        CanvasRepo.reset(null)
    }

    @Test fun decodesInStepsAtTheScreensPixelsAndSharpensWhatIsEnlarged() {
        assertEquals(1440, CanvasSizing.step(1300))
        assertEquals(2160, CanvasSizing.step(2160))
        assertEquals(3840, CanvasSizing.step(9000))
        assertEquals(360, CanvasSizing.step(10))
        assertTrue(CanvasSizing.cubic(2f)); assertFalse(CanvasSizing.cubic(1f)); assertFalse(CanvasSizing.cubic(.5f))
        assertEquals(CanvasSizing.UPRIGHT, CanvasSizing.shape(null, null))
        assertEquals(.5f, CanvasSizing.shape(.5f, null))
    }

    @Test fun theCanvasColumnTakesTheShapeOfTheCanvasWithinTheWindow() {
        // 2560 × 1440, an upright Canvas: wider than its natural 810 (a third of the screen at least), never a strip.
        assertEquals(2560f * CanvasArea.MIN_SHARE, CanvasArea.widthDp(2560f, 1440f, 9f / 16f, lyrics = true), .01f)
        // A square Canvas with the lyrics: half the window; without them, all but the controls.
        assertEquals(1280f, CanvasArea.widthDp(2560f, 1440f, 1f, lyrics = true), .01f)
        assertEquals(1440f, CanvasArea.widthDp(2560f, 1440f, 1f, lyrics = false), .01f)
        assertEquals(2560f - CanvasArea.CONTROLS_DP, CanvasArea.widthDp(2560f, 1440f, 16f / 9f, lyrics = false), .01f)
        // Unknown shape: upright.
        assertEquals(CanvasArea.widthDp(1920f, 1080f, 9f / 16f, true), CanvasArea.widthDp(1920f, 1080f, null, true), .01f)
    }

    @Test fun thePlayersOwnSayWinsOverTheAlbumPage() {
        val track = Track("t1", "Un", "Nora Vale", "Marée basse")
        val key = MediaOverrides.albumKey("Marée basse", "Nora Vale")!!
        // Off on the album page, on in the player: the player shows it (the phone's rule).
        MediaOverrides.replaceForTest(listOf(MediaOverride(MediaOverrides.ALBUM, key, canvasOff = true, playerCanvas = true)))
        assertTrue(MediaOverrides.canvasDisabled(track)); assertFalse(MediaOverrides.canvasHiddenInPlayer(track))
        // Off everywhere: not even fetched.
        MediaOverrides.replaceForTest(listOf(MediaOverride(MediaOverrides.ALBUM, key, canvasOff = true)))
        assertTrue(MediaOverrides.canvasHiddenInPlayer(track)); assertTrue(MediaOverrides.canvasUnused(track))
        // The player's framing, else the album page's.
        val page = Frame(w = .5f, h = .5f)
        MediaOverrides.replaceForTest(listOf(MediaOverride(MediaOverrides.ALBUM, key, frame = page)))
        assertEquals(page, MediaOverrides.playerFrame(track))
        val player = Frame(cx = .1f, w = .4f, h = .7f)
        MediaOverrides.replaceForTest(listOf(MediaOverride(MediaOverrides.ALBUM, key, frame = page, playerFrame = player)))
        assertEquals(player, MediaOverrides.playerFrame(track))
    }

    @Test fun thePublishedListIsFetchedOftenWhileTheWindowIsUsed() {
        val now = 10_000_000L
        assertTrue(StudioContent.Plan.due(now, now - StudioContent.ACTIVE_EVERY_MS, now - 60_000L, playing = false))
        assertFalse(StudioContent.Plan.due(now, now - 30_000L, now, playing = true), "not twice in two minutes")
        assertFalse(StudioContent.Plan.due(now, 0L, now - StudioContent.ACTIVE_FOR_MS - 1, playing = false), "idle and silent: the 15 minutes")
        assertTrue(StudioContent.Plan.due(now, 0L, 0L, playing = true), "music playing counts as use")
        assertTrue(StudioContent.Plan.soon(now, now - StudioContent.SOON_MS)); assertFalse(StudioContent.Plan.soon(now, now - 1_000L))
    }

    @Test fun theFullPlayersCanvasFillsItsColumnEdgeToEdge() = runComposeUiTest {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = true
        AppSettings.animatedArtwork.value = true
        FullPlayerLyrics.shown.value = true
        CanvasRepo.reset(android.content.Context(File("build/test-data/canvas-fill-${System.nanoTime()}")))
        CanvasRepo.lookup = { KetvoiceApi.CanvasResult("https://canvas.example/${it.id}.mp4", "fill-${it.albumId}") }
        CanvasRepo.fetchFile = { _, dest, _ -> CanvasFixtures.video().copyTo(dest, overwrite = true) }
        val t = Fixtures.albumTracks(0)[3]
        runBlocking { CanvasRepo.resolve(t); CanvasRepo.ensurePoster(CanvasRepo.localPath(t.id)!!) }
        try {
            setContent {
                CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                    KetvoiceShell(RecordingPlayer().playing().apply { toggle() }, Fixtures.library(), FixtureCatalog, dark = true, initialFullPlayer = true)
                }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("full-canvas-area")).fetchSemanticsNodes().isNotEmpty() }
            val root = onRoot().getBoundsInRoot()
            val area = onNodeWithTag("full-canvas-area").getBoundsInRoot()
            assertEquals(root.top, area.top); assertEquals(root.bottom, area.bottom)
            assertEquals(root.left, area.left)
            assertTrue(area.right - area.left >= (root.right - root.left) * CanvasArea.MIN_SHARE - 1.dp, "column ${area.right - area.left}")
            // The Canvas covers its whole column: at least as wide and as tall, never inside it with bars.
            val inArea = hasAnyAncestor(hasTestTag("full-canvas-area"))
            val fill = onNode(hasTestTag("canvas-fill") and inArea, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(fill.left <= area.left && fill.top <= area.top && fill.right >= area.right && fill.bottom >= area.bottom,
                "canvas $fill in $area")
            onNode(hasTestTag("canvas-poster") and inArea, useUnmergedTree = true).assertExists()
        } finally { FullPlayerLyrics.shown.value = false }
    }
}
