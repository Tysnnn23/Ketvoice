package com.ketvoice.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.MainActivity
import com.ketvoice.app.R
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.CoverLegibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The two widgets of 7.4: state → RemoteViews (playing, paused, empty, J'aime), the colours' legibility, where every
 * button's PendingIntent goes, and renders of both widgets and their picker previews under build/host-ui-preview
 * (inflated on the host as an AppWidgetHost does; the launcher's rounded outline is cut in by the test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingWidgetsHostTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val density get() = context.resources.displayMetrics.density
    /** The host's clock: the chronometers count from it. */
    private val now get() = android.os.SystemClock.elapsedRealtime()

    private fun cover(a: Int, b: Int, sun: Int = 0xFFFFE08A.toInt()): Bitmap {
        val out = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawRect(0f, 0f, 300f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, a, b, Shader.TileMode.CLAMP) })
        canvas.drawCircle(200f, 100f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sun })
        return roundedCover(out, 300)
    }

    private fun playing(cover: Bitmap? = cover(0xFF0B3D91.toInt(), 0xFF1FA39A.toInt()), liked: Boolean = true, playing: Boolean = true) =
        NowPlaying("t1", "Midnight City", "M83", playing = playing, liked = liked, positionMs = 72_000, durationMs = 243_000,
            atMs = now, cover = cover, palette = WidgetPalette.of(cover))

    private fun host(views: android.widget.RemoteViews, wDp: Int, hDp: Int): View {
        val frame = FrameLayout(context)
        val view = views.apply(context, frame)
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val w = (wDp * density).toInt(); val h = (hDp * density).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        return frame
    }

    private fun ground(s: NowPlaying) = s.palette.ground()
    private fun small(s: NowPlaying) = host(WidgetViews.small(context, s, now, ground(s)), 170, 170)
    private fun controls(s: NowPlaying, hDp: Int = 170) =
        host(WidgetViews.controls(context, s, now, ground(s), compact = hDp < WidgetViews.FULL_HEIGHT_DP), 340, hDp)

    private fun <T : View> View.v(id: Int): T = findViewById(id)
    private fun View.text(id: Int) = v<TextView>(id).text.toString()

    // ---------------------------------------------------------------- state → views

    @Test fun playingShowsTheTitleAndPause() {
        val view = controls(playing())
        assertEquals("Midnight City", view.text(R.id.widget_title))
        assertEquals("M83", view.text(R.id.widget_artist))
        assertEquals(AppText.t("Pause"), view.v<View>(R.id.widget_play).contentDescription)
        assertEquals(View.VISIBLE, view.v<View>(R.id.widget_like).visibility)
        assertEquals(AppText.t("Retirer des favoris"), view.v<View>(R.id.widget_like).contentDescription)
        // Progress at 72 s of 243 s, on the bar that reads on this (dark) card.
        val bar = view.v<ProgressBar>(R.id.widget_progress_light)
        assertEquals(View.VISIBLE, bar.visibility); assertEquals(View.GONE, view.v<View>(R.id.widget_progress_dark).visibility)
        assertEquals(296, bar.progress)
        // Playing: the times are chronometers that tick on their own; the sample text of the preview is hidden.
        assertEquals(View.VISIBLE, view.v<Chronometer>(R.id.widget_elapsed_clock).visibility)
        assertEquals(View.VISIBLE, view.v<Chronometer>(R.id.widget_remaining_clock).visibility)
        assertTrue(view.v<Chronometer>(R.id.widget_remaining_clock).isCountDown)
        assertEquals(View.GONE, view.v<View>(R.id.widget_elapsed).visibility)
    }

    @Test fun pausedShowsPlayAndStillTimes() {
        val view = controls(playing(liked = false, playing = false))
        assertEquals(AppText.t("Lecture"), view.v<View>(R.id.widget_play).contentDescription)
        assertEquals(AppText.t("Ajouter aux favoris"), view.v<View>(R.id.widget_like).contentDescription)
        assertEquals("01:12", view.text(R.id.widget_elapsed))
        assertEquals("−02:51", view.text(R.id.widget_remaining))
        assertEquals(View.GONE, view.v<View>(R.id.widget_elapsed_clock).visibility)
        // A low widget leaves the times out and keeps the bar and the transport.
        val low = controls(playing(playing = false), 120)
        assertEquals(View.GONE, low.v<View>(R.id.widget_times).visibility)
        assertEquals(View.VISIBLE, low.v<View>(R.id.widget_progress_box).visibility)
    }

    @Test fun emptyIsRienEnLectureWithAPlayButton() {
        val s = NowPlaying()
        for (view in listOf(small(s), controls(s))) {
            assertEquals(AppText.t("Rien en lecture"), view.text(R.id.widget_title))
            assertEquals("Ketvoice", view.text(R.id.widget_artist))
            assertEquals(AppText.t("Lecture"), view.v<View>(R.id.widget_play).contentDescription)
        }
        val view = controls(s)
        assertEquals(View.GONE, view.v<View>(R.id.widget_like).visibility)
        assertEquals(View.INVISIBLE, view.v<View>(R.id.widget_progress_box).visibility)
        assertEquals(WidgetPalette.FALLBACK, s.palette)
    }

    // ---------------------------------------------------------------- intents

    private fun clickBroadcast(view: View, id: Int): String? {
        val app = shadowOf(context as android.app.Application)
        app.clearBroadcastIntents()
        view.v<View>(id).performClick()
        val sent = app.broadcastIntents.lastOrNull() ?: return null
        assertEquals(WidgetActionReceiver::class.java.name, sent.component?.className)
        return sent.action
    }

    @Test fun everyButtonActsThroughTheReceiverAndTheCardOpensThePlayer() {
        // Quest has no home-screen widgets: its manifest removes the receivers.
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        val view = controls(playing())
        assertEquals(WidgetActionReceiver.TOGGLE, clickBroadcast(view, R.id.widget_play))
        assertEquals(WidgetActionReceiver.NEXT, clickBroadcast(view, R.id.widget_next))
        assertEquals(WidgetActionReceiver.PREVIOUS, clickBroadcast(view, R.id.widget_previous))
        assertEquals(WidgetActionReceiver.LIKE, clickBroadcast(view, R.id.widget_like))
        assertEquals(WidgetActionReceiver.TOGGLE, clickBroadcast(small(playing()), R.id.widget_play))
        assertEquals(WidgetActionReceiver.PLAY, clickBroadcast(small(NowPlaying()), R.id.widget_play))
        assertEquals(WidgetActionReceiver.PLAY, clickBroadcast(controls(NowPlaying()), R.id.widget_play))

        val card = shadowOf(WidgetViews.openPlayer(context)).savedIntent
        assertEquals(MainActivity::class.java.name, card.component?.className)
        assertEquals(WidgetIntents.OPEN_PLAYER, card.getStringExtra(WidgetIntents.OPEN))
        assertTrue(shadowOf(WidgetViews.openPlayer(context)).isActivityIntent)
        assertTrue(shadowOf(WidgetViews.action(context, WidgetActionReceiver.NEXT)).isBroadcastIntent)
        // Each action has its own PendingIntent (one request code each), so none replaces another.
        assertEquals(WidgetActionReceiver.all.size, WidgetActionReceiver.all.map(WidgetActionReceiver::requestCode).toSet().size)
        // The receiver is Ketvoice's own: not reachable by other apps.
        val info = context.packageManager.getReceiverInfo(android.content.ComponentName(context, WidgetActionReceiver::class.java), 0)
        assertFalse(info.exported)
    }

    // ---------------------------------------------------------------- colours

    @Test fun everyCoverGivesACalmReadableCard() {
        fun check(p: WidgetPalette, what: String) {
            for (c in intArrayOf(p.top, p.bottom)) {
                val ground = Color(c)
                assertTrue("$what ink", CoverLegibility.contrast(Color(p.ink), ground) >= CoverLegibility.TEXT)
                assertTrue("$what soft ink", CoverLegibility.contrast(Color(p.inkSoft), ground) >= CoverLegibility.TEXT)
                val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
                assertTrue("$what not neon: ${hsl[1]}", hsl[1] <= WidgetPalette.MAX_SAT + .01f)
            }
            assertEquals(what, p.ink == WidgetPalette.WHITE, p.lightInk)
        }
        for (h in 0 until 360 step 15) for (l in listOf(.05f, .2f, .4f, .55f, .7f, .8f, .95f)) for (s in listOf(.1f, .6f, 1f)) {
            val base = ColorUtils.HSLToColor(floatArrayOf(h.toFloat(), s, l))
            val second = ColorUtils.HSLToColor(floatArrayOf((h + 140f) % 360f, s, (1f - l).coerceIn(.1f, .9f)))
            check(WidgetPalette.of(base, second, true), "h$h l$l s$s")
            check(WidgetPalette.of(base, null, s > .3f), "h$h l$l s$s alone")
        }
        check(WidgetPalette.FALLBACK, "fallback")
        assertEquals(WidgetPalette.FALLBACK, WidgetPalette.of(null))
        assertTrue("a white cover gives a light card", !WidgetPalette.of(cover(0xFFF4F4F2.toInt(), 0xFFE6E2DA.toInt(), 0xFFFFFFFF.toInt())).lightInk)
    }

    @Test fun thePreviewCardIsThePaletteOfThePreviewCover() {
        val drawable = context.getDrawable(R.drawable.widget_preview_cover)!!
        val art = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 120, 120); drawable.draw(Canvas(art))
        val p = WidgetPalette.of(art)
        val ground = context.getDrawable(R.drawable.widget_preview_ground) as GradientDrawable
        val colors = ground.colors!!
        println("preview palette: top #%08X bottom #%08X".format(p.top, p.bottom))
        assertEquals("%08X".format(p.top), "%08X".format(colors[0]))
        assertEquals("%08X".format(p.bottom), "%08X".format(colors[1]))
    }

    // ---------------------------------------------------------------- renders

    private fun save(view: View, name: String): Bitmap {
        val raw = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(raw))
        // The launcher clips the card to its outline (system_app_widget_background_radius); the host cannot.
        val out = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val r = context.resources.getDimension(R.dimen.widget_radius)
        Canvas(out).drawRoundRect(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) })
        val dir = File("build/host-ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    private fun sheet(name: String, views: List<View>) {
        val gap = (12 * density).toInt()
        val w = views.maxOf { it.width } + 2 * gap
        val h = views.sumOf { it.height + gap } + gap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF6B7A8F.toInt())
        var y = gap.toFloat()
        views.forEachIndexed { i, v -> canvas.drawBitmap(save(v, "$name-$i"), gap.toFloat(), y, null); y += v.height + gap }
        File("build/host-ui-preview/$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun preview(layout: Int, wDp: Int, hDp: Int): View {
        val frame = FrameLayout(context)
        val view = LayoutInflater.from(context).inflate(layout, frame, false)
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val w = (wDp * density).toInt(); val h = (hDp * density).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        return frame
    }

    @Test fun renderBothWidgetsAndTheirPreviews() {
        val pale = playing(cover(0xFFF6EFE4.toInt(), 0xFFE9D9C2.toInt(), 0xFFFFB37A.toInt()), liked = false, playing = false)
        val warm = playing(cover(0xFFE85D3A.toInt(), 0xFF7A1F4B.toInt()))
        val grey = playing(cover(0xFF3A3A3A.toInt(), 0xFF9A9A9A.toInt(), 0xFFDDDDDD.toInt()), liked = false)
        sheet("widget74-small", listOf(small(playing()), small(pale), small(warm.copy(title = "A very long title that does not fit on one line")),
            small(NowPlaying()), host(WidgetViews.small(context, grey, now, ground(grey)), 150, 190)))
        sheet("widget74-controls", listOf(controls(playing()), controls(pale), controls(warm, 120), controls(grey, 200), controls(NowPlaying())))
        save(preview(R.layout.widget_now_playing_small, 170, 170), "widget74-preview-small")
        save(preview(R.layout.widget_now_playing_controls, 340, 170), "widget74-preview-controls")
    }
}
