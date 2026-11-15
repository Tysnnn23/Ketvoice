package com.ketvoice.app.ui

import com.ketvoice.app.data.Frame
import com.ketvoice.app.data.MediaOverride
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.ui.sub.AlbumPresentation
import com.ketvoice.app.ui.sub.Framing
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FramingTest {
    private val upright = 9f / 16f   // a Canvas
    private fun near(expected: Float, actual: Float, delta: Float = 1e-4f) = assertEquals(expected, actual, delta)

    @Test fun withoutAFramingTheRectangleIsWhatThePageAlreadyShows() {
        // A square cover in a hero 1.2 times wider than tall: its full width, a band of its height.
        val cover = Framing.default(1f, 1.2f)
        near(1f, cover.w); near(1f / 1.2f, cover.h); near(0f, cover.cx); near(0f, cover.cy)
        // An upright Canvas in the same hero: its full width too.
        val canvas = Framing.default(upright, 1.2f)
        near(1f, canvas.w); near(1f / 1.2f, canvas.h)
        // A picture wider than the hero: its full height.
        val wide = Framing.default(2f, 1.2f)
        near(.5f, wide.h); near(.6f, wide.w)
        for (frame in listOf(cover, canvas, wide)) near(1.2f, frame.aspect, 1e-3f)
        // Drawn with that rectangle, the picture is exactly where plain "cover the box" puts it.
        val layer = Framing.layer(canvas, 600f, 500f, upright)
        near(1f, layer.scaleX); near(1f, layer.scaleY); near(0f, layer.translationX); near(0f, layer.translationY)
    }

    @Test fun theChosenRectangleFillsTheBox() {
        // Half the width of a square cover, centred a quarter width to the right and up, in a 400 × 400 box.
        val layer = Framing.layer(Frame(cx = .25f, cy = -.25f, w = .5f, h = .5f), 400f, 400f, 1f)
        near(2f, layer.scaleX); near(2f, layer.scaleY)
        near(-200f, layer.translationX); near(200f, layer.translationY)
        // A rectangle of another shape than the box covers it: the tighter side decides.
        val band = Framing.layer(Frame(w = 1f, h = .25f), 400f, 400f, 1f)
        near(4f, band.scaleY)
        // Mirrored and turned: the layer mirrors and turns, the rectangle stays where it was drawn.
        val turned = Framing.layer(Frame(cx = .1f, w = .5f, h = .5f, rotation = 90f, flip = true), 400f, 400f, 1f)
        near(-2f, turned.scaleX); near(2f, turned.scaleY); near(90f, turned.rotation); near(-80f, turned.translationX)
        // In a box that an upright picture overflows, lengths follow the drawn width.
        assertEquals(800f, Framing.baseWidth(800f, 400f, upright), 0f)
        assertEquals(800f, Framing.baseWidth(400f, 400f, 2f), 0f)
    }

    @Test fun originalAndSquareFollowThePictureAndItsTurn() {
        val whole = Framing.original(upright)
        near(1f, whole.w); near(16f / 9f, whole.h, 1e-3f)
        val lying = Framing.original(upright, rotation = 90f)
        near(16f / 9f, lying.w, 1e-3f); near(1f, lying.h, 1e-3f)
        val square = Framing.square(upright)
        near(1f, square.w); near(1f, square.h)
        assertTrue(Framing.fits(whole, upright)); assertTrue(Framing.fits(lying, upright)); assertTrue(Framing.fits(square, upright))
        // Tilted, the whole picture cannot be an upright rectangle any more: the largest that fits is kept.
        val tilted = Framing.original(1f, rotation = 20f)
        assertTrue(Framing.fits(tilted, 1f)); assertTrue(tilted.w < 1f && tilted.w > .5f)
        near(tilted.w, tilted.h, 1e-3f)
    }

    @Test fun aRectangleIsBroughtBackOntoThePicture() {
        assertTrue(Framing.fits(Frame(w = 1f, h = 1f), 1f))
        assertFalse(Framing.fits(Frame(cx = .2f, w = 1f, h = 1f), 1f))
        assertFalse(Framing.fits(Frame(w = .9f, h = .9f, rotation = 30f), 1f))
        val back = Framing.contain(Frame(cx = .3f, cy = 0f, w = .8f, h = .4f), 1f)
        assertTrue(Framing.fits(back, 1f))
        near(2f, back.aspect, 1e-3f)
        near(.3f, back.cx); near(.4f, back.w, 1e-3f)
        // A centre outside the picture comes back in, with a usable size.
        val lost = Framing.contain(Frame(cx = 1.5f, cy = -1.5f, w = .5f, h = .5f), 1f)
        assertTrue(Framing.fits(lost, 1f)); assertTrue(lost.w >= Frame.MIN_SIDE)
        // What already fits is left alone.
        val fine = Frame(cx = .1f, cy = -.1f, w = .5f, h = .5f, rotation = 10f)
        assertEquals(fine, Framing.contain(fine, 1f))
    }

    @Test fun aQuarterTurnAndAMirrorKeepShowingTheSameThing() {
        val frame = Frame(cx = .2f, cy = -.1f, w = .5f, h = .3f)
        val turned = Framing.quarterTurn(frame)
        near(.1f, turned.cx); near(.2f, turned.cy); near(.3f, turned.w); near(.5f, turned.h); near(90f, turned.rotation)
        var four = frame
        repeat(4) { four = Framing.quarterTurn(four) }
        near(frame.cx, four.cx, 1e-4f); near(frame.cy, four.cy, 1e-4f); near(frame.w, four.w); near(0f, four.rotation, 1e-3f)
        val mirrored = Framing.mirror(frame.copy(rotation = 20f))
        near(-.2f, mirrored.cx); near(-20f, mirrored.rotation); assertTrue(mirrored.flip)
        assertEquals(frame.copy(rotation = 20f), Framing.mirror(mirrored))
    }

    @Test fun fingersTakeTheMiddleAnEdgeOrACorner() {
        fun grab(x: Float, y: Float) = Framing.grab(x, y, 100f, 100f, 400f, 300f, reach = 30f)
        assertEquals(Framing.Grab(0, 0), grab(250f, 200f)); assertTrue(grab(250f, 200f)!!.moves)
        assertEquals(Framing.Grab(-1, -1), grab(95f, 110f))
        assertEquals(Framing.Grab(1, 1), grab(410f, 305f))
        assertEquals(Framing.Grab(0, -1), grab(250f, 90f))
        assertEquals(Framing.Grab(1, 0), grab(395f, 200f))
        assertNull(grab(450f, 200f))
        // A small rectangle still has a middle to move it by.
        assertEquals(Framing.Grab(0, 0), Framing.grab(125f, 125f, 100f, 100f, 150f, 150f, 30f))
    }

    @Test fun draggingMovesOrResizesWithoutLeavingThePicture() {
        val frame = Frame(w = .5f, h = .5f)
        val moved = Framing.drag(frame, Framing.Grab(0, 0), .1f, -.2f, 1f, square = false)
        near(.1f, moved.cx); near(-.2f, moved.cy); near(.5f, moved.w)
        // Against an edge the rectangle slides along it instead of leaving or shrinking.
        val edge = Framing.drag(frame.copy(cx = .25f), Framing.Grab(0, 0), .1f, .1f, 1f, square = false)
        near(.25f, edge.cx); near(.1f, edge.cy); near(.5f, edge.w)
        // The right edge follows the finger; the left one stays where it was.
        val wider = Framing.drag(frame, Framing.Grab(1, 0), .2f, 0f, 1f, square = false)
        near(.7f, wider.w); near(.1f, wider.cx); near(-.25f, wider.cx - wider.w / 2f); near(.5f, wider.h)
        // The top-left corner pulled outwards.
        val corner = Framing.drag(frame, Framing.Grab(-1, -1), -.1f, -.05f, 1f, square = false)
        near(.6f, corner.w); near(.55f, corner.h); near(.25f, corner.cx + corner.w / 2f); near(.25f, corner.cy + corner.h / 2f)
        // Never smaller than a usable size, never past the picture.
        assertTrue(Framing.drag(frame, Framing.Grab(1, 0), -5f, 0f, 1f, false).w >= Frame.MIN_SIDE * 2f - 1e-6f)
        assertEquals(frame, Framing.drag(frame, Framing.Grab(1, 0), .9f, 0f, 1f, false))
        // A square stays a square, whichever handle is held.
        val square = Framing.drag(frame, Framing.Grab(1, 1), .2f, 0f, 1f, square = true)
        near(square.w, square.h); near(.6f, square.w); near(-.25f, square.cx - square.w / 2f); near(-.25f, square.cy - square.h / 2f)
        val fromEdge = Framing.drag(frame, Framing.Grab(0, -1), 0f, -.1f, 1f, square = true)
        near(.6f, fromEdge.w); near(.6f, fromEdge.h); near(0f, fromEdge.cx); near(.25f, fromEdge.cy + fromEdge.h / 2f)
    }

    @Test fun thePlayersRectangleKeepsTheShapeOfItsArtwork() {
        val box = .85f
        // A wide rectangle made for the page, as the player's artwork crops it: the largest part of that shape, same centre.
        val wide = Frame(.1f, -.05f, .8f, .4f)
        val shaped = Framing.shaped(wide, box, 1f)
        assertEquals(box, shaped.aspect, 1e-3f)
        assertEquals(.4f, shaped.h, 1e-3f); assertEquals(.34f, shaped.w, 1e-3f)
        assertEquals(.1f, shaped.cx, 1e-3f); assertEquals(-.05f, shaped.cy, 1e-3f)
        // A tall one keeps its width instead.
        assertEquals(.3f, Framing.shaped(Frame(0f, 0f, .3f, .9f), box, 1f).w, 1e-3f)
        assertTrue(Framing.fits(Framing.shaped(Frame(.4f, .4f, .5f, .5f), box, 1f), 1f))
        // Dragging an edge or a corner changes both sides together; the shape never moves.
        var frame = Framing.default(9f / 16f, box)
        assertEquals(box, frame.aspect, 1e-3f)
        for ((grab, dx, dy) in listOf(Triple(Framing.Grab(1, 0), -.1f, 0f), Triple(Framing.Grab(0, 1), 0f, -.12f),
            Triple(Framing.Grab(-1, -1), .05f, .08f), Triple(Framing.Grab(1, 1), -.2f, .01f))) {
            val next = Framing.drag(frame, grab, dx, dy, 9f / 16f, box)
            assertEquals("$grab", box, next.aspect, 1e-3f)
            assertTrue(Framing.fits(next, 9f / 16f))
            assertTrue("$grab changed something", next != frame)
            frame = next
        }
        // The right edge pulled in by 0.1: the left edge stays where it was.
        val before = Framing.default(1f, box)
        val pulled = Framing.drag(before, Framing.Grab(1, 0), -.1f, 0f, 1f, box)
        assertEquals(before.w - .1f, pulled.w, 1e-4f)
        assertEquals(before.cx - before.w / 2f, pulled.cx - pulled.w / 2f, 1e-4f)
        // The square of the page's tools is the same lock with a shape of one.
        assertEquals(Framing.drag(Frame(w = .5f, h = .5f), Framing.Grab(1, 1), .1f, .06f, 1f, square = true),
            Framing.drag(Frame(w = .5f, h = .5f), Framing.Grab(1, 1), .1f, .06f, 1f, 1f))
        // Moving it is free.
        assertEquals(.05f, Framing.drag(Frame(w = .4f, h = .4f / box), Framing.Grab(0, 0), .05f, 0f, 1f, box).cx, 1e-5f)
    }

    @Test fun anAlbumPageTakesTheColourOfWhatItsHeroShows() {
        // The Canvas plays: its colour; until a frame of it is read, the cover's rather than none.
        assertEquals("canvas", AlbumPresentation.pageAccent(true, "canvas", "cover"))
        assertEquals("cover", AlbumPresentation.pageAccent(true, null, "cover"))
        // The Canvas is off, or was removed: the cover's, whatever was read of a Canvas before.
        assertEquals("cover", AlbumPresentation.pageAccent(false, "canvas", "cover"))
        assertNull(AlbumPresentation.pageAccent(false, "canvas", null))
        // The player's artwork: the width over 55 % of the height on a phone, a square on a wide or lying window.
        assertEquals(400f / (900f * .55f), AlbumPresentation.playerArtworkAspect(400f, 900f), 1e-4f)
        assertEquals(1f, AlbumPresentation.playerArtworkAspect(1280f, 800f), 0f)
        assertEquals(1f, AlbumPresentation.playerArtworkAspect(800f, 1280f), 0f)
        assertEquals(1f, AlbumPresentation.playerArtworkAspect(400f, 0f), 0f)
    }

    @Test fun aWeekIsSevenBarsAndRecentTitlesFillTheWidth() {
        val today = java.time.LocalDate.of(2026, 11, 15)
        var days = com.ketvoice.app.data.ListeningWeek.addTime(emptyMap(), today, 3_000L)
        days = com.ketvoice.app.data.ListeningWeek.addTime(days, today.minusDays(2), 9_000L)
        days = com.ketvoice.app.data.ListeningWeek.addTime(days, today.minusDays(8), 99_000L)
        // Oldest first, today last; a day outside the week is not part of it.
        assertArrayEquals(longArrayOf(0, 0, 0, 0, 9_000, 0, 3_000), com.ketvoice.app.data.ListeningWeek.daily(days, today))
        assertEquals(1f, com.ketvoice.app.data.ListeningWeek.barShare(9_000, 9_000), 0f)
        assertEquals(.4f, com.ketvoice.app.data.ListeningWeek.barShare(3_000, 9_000), 1e-4f)
        assertEquals("A day without music keeps a stub", .1f, com.ketvoice.app.data.ListeningWeek.barShare(0, 9_000), 0f)
        assertEquals(.1f, com.ketvoice.app.data.ListeningWeek.barShare(0, 0), 0f)
        assertEquals(3, com.ketvoice.app.ui.home.recentColumns(364f)); assertEquals(3, com.ketvoice.app.ui.home.recentColumns(200f))
        assertEquals(5, com.ketvoice.app.ui.home.recentColumns(600f)); assertEquals(6, com.ketvoice.app.ui.home.recentColumns(1400f))
    }

    @Test fun twoFingersGrowAndShrinkAroundTheCentre() {
        val frame = Frame(cx = .1f, w = .4f, h = .2f)
        val bigger = Framing.pinch(frame, 1.5f, 1f)
        near(.6f, bigger.w); near(.3f, bigger.h, 1e-4f); near(.1f, bigger.cx)
        assertEquals("Exactly to the edge is still on the picture", .8f, Framing.pinch(frame, 2f, 1f).w, 1e-4f)
        val off = frame.copy(cx = .2f)
        assertEquals("Past the picture: refused", off, Framing.pinch(off, 2f, 1f))
        assertTrue(Framing.pinch(frame, .01f, 1f).h >= Frame.MIN_SIDE * 2f - 1e-6f)
        assertEquals(frame, Framing.pinch(frame, Float.NaN, 1f))
    }

    @Test fun theFineAngleIsReadApartFromTheQuarterTurns() {
        near(0f, Framing.tilt(0f)); near(12f, Framing.tilt(12f)); near(-12f, Framing.tilt(-12f))
        near(10f, Framing.tilt(100f)); near(-30f, Framing.tilt(60f)); near(0f, Framing.tilt(180f)); near(-5f, Framing.tilt(-95f))
        val tilted = Framing.withTilt(Frame(w = .9f, h = .9f, rotation = 90f), 15f, 1f)
        near(105f, tilted.rotation); assertTrue(Framing.fits(tilted, 1f)); assertTrue(tilted.w < .9f)
        near(45f, Framing.tilt(Framing.withTilt(Frame(w = .2f, h = .2f), 80f, 1f).rotation))
    }

    @Test fun theHeroTakesTheShapeOfTheRectangleWithinWhatAPageAllows() {
        near(400f, Framing.heroHeightDp(Frame(w = 1f, h = 1f), 400f, 900f))
        near(200f, Framing.heroHeightDp(Frame(w = 1f, h = .2f), 400f, 900f))
        near(558f, Framing.heroHeightDp(Frame(w = .5f, h = 1f), 400f, 900f))
    }

    @Test fun aFramingSurvivesTheServerAndTheCache() {
        val frame = Frame(.12f, -.3f, .6f, .45f, 97f, true)
        val saved = MediaOverride("album", "discovery|daft punk", "Discovery — Daft Punk", frame = frame)
        val back = MediaOverrides.decode(MediaOverrides.encode(listOf(saved)))["album:discovery|daft punk"]!!
        assertEquals(frame, back.frame)
        assertNull("A framing alone is enough to keep the row", back.cover)
        // The zoom-and-offset framings of an older version carry no rectangle: they are ignored.
        val old = """{"overrides":[{"kind":"album","key":"a|b","coverURL":"https://c/x.jpg","frame":{"scale":1.5,"x":0.1,"y":0}}]}"""
        assertNull(MediaOverrides.decode(old)["album:a|b"]!!.frame)
        // Out-of-range values from anywhere are brought back in range.
        val wild = Frame(9f, -9f, 0f, 99f, 540f).clamped()
        near(2f, wild.cx); near(-2f, wild.cy); near(Frame.MIN_SIDE, wild.w); near(3f, wild.h); near(-180f, abs(wild.rotation) * -1f)
        near(1.5f, Frame(w = .6f, h = .4f).aspect)
    }
}
