package com.ketvoice.app.ui

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.util.LyricVideo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricVideoHostTest {
    private val lines = listOf(
        MusicApi.LyricLine(10_000, "I hope the memories we make", listOf(MusicApi.LyricWord(10_000, "I "), MusicApi.LyricWord(10_400, "hope "),
            MusicApi.LyricWord(10_900, "the "), MusicApi.LyricWord(11_200, "memories "), MusicApi.LyricWord(12_000, "we "), MusicApi.LyricWord(12_300, "make"))),
        MusicApi.LyricLine(13_500, "last all life long"),
        MusicApi.LyricLine(16_000, "I'll hop in the car with my friends"),
        MusicApi.LyricLine(19_000, "and play my song, song"))

    @Test fun aFrameShowsTheCardOverTheCoverColours() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 64) for (y in 0 until 64) setPixel(x, y, if (x < 32) 0xFFE0602A.toInt() else 0xFF2A60E0.toInt())
        }
        val frame = LyricVideo.previewFrame(context, Track("qa:v", "All Life Long", "Juice WRLD", "Album"), lines, 22_000, cover,
            0xFFE0602A.toInt(), true, 11_600)
        File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}").mkdirs()
        File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/lyrics-video-frame.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(LyricVideo.WIDTH, frame.width); assertEquals(LyricVideo.HEIGHT, frame.height)
    }

    @Test fun theWindowCoversTheLinesAndLightsThemInTime() {
        val window = LyricVideo.window(lines, 22_000)
        assertEquals(10_000 - LyricVideo.LEAD_MS, window.first)
        assertEquals(22_000 + LyricVideo.TAIL_MS, window.last)
        val lit = LyricVideo.lit(lines, 22_000, 11_600)
        assertTrue("first line partly sung: ${lit[0]}", lit[0] in .3f..0.8f)
        assertEquals(0f, lit[1], 0f)
        assertEquals(0, LyricVideo.active(lines, 11_600)); assertEquals(-1, LyricVideo.active(lines, 9_000))
        assertTrue(LyricVideo.window(lines, 60_000).let { it.last - it.first } <= LyricVideo.MAX_MS)
    }
}
