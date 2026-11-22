package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * « Personnalisation » (2026-11-22) must leave the default look exactly as it was: a list of titles (light and dark),
 * Settings → Lyrics and two lyric lines are drawn with the default settings and compared with pictures drawn the same
 * way by 8.4.0 before the change (`src/testDebug/resources/default-look`, made by this same test on the 8.4.0 sources
 * with `build/write-default-look-baseline` present). Mobile flavour only: the pictures are the phone's.
 * This file uses only what 8.4.0 already had, so that it runs on both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefaultLookRenderHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val baselines = File("src/testDebug/resources/default-look")
    private val writing = File("build/write-default-look-baseline").exists()
    private val mobile = com.ketvoice.app.BuildConfig.FLAVOR == "mobile"

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.accentArgb.value = 0xFFFF2D55L
        AppSettings.trueBlack.value = false
        AppSettings.coverAccent.value = false
        AppSettings.coverBackground.value = false
        // The settings of « Personnalisation » at their defaults, when this version has them (8.4.0 does not).
        runCatching {
            val owner = Class.forName("com.ketvoice.app.theme.Personalization")
            owner.getMethod("resetAll").invoke(owner.getField("INSTANCE").get(null))
        }
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun capture(): Bitmap {
        var out: Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            out = bitmap
        }
        return out!!
    }

    /** Writes the baseline, or compares with it: at most 0.1 % of the pixels may differ by more than 6 levels (host Skia noise). */
    private fun same(name: String) {
        val now = capture()
        File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/default-look/$name.png").also { it.parentFile?.mkdirs() }
            .outputStream().use { now.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val file = File(baselines, "$name.png")
        if (writing) { baselines.mkdirs(); file.outputStream().use { now.compress(Bitmap.CompressFormat.PNG, 100, it) }; return }
        assertTrue("baseline $file", file.exists())
        val before = BitmapFactory.decodeFile(file.path)
        assertTrue("$name: size ${before.width}x${before.height} vs ${now.width}x${now.height}", before.width == now.width && before.height == now.height)
        var differing = 0
        for (y in 0 until now.height) for (x in 0 until now.width) {
            val a = before.getPixel(x, y); val b = now.getPixel(x, y)
            val d = maxOf(kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)), kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
                kotlin.math.abs((a and 0xFF) - (b and 0xFF)))
            if (d > 6) differing++
        }
        val share = differing.toFloat() / (now.width * now.height)
        assertTrue("$name: ${differing} pixels differ (${share * 100} %)", share <= .001f)
    }

    private fun rows(dark: Boolean) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            Column(Modifier.fillMaxSize().background(LocalTheme.current.background).padding(top = 24.dp)) {
                listOf("Lumière du soir" to "Artiste QA", "Nuit blanche" to "Autre artiste", "Chanson trois" to "Groupe", "Dernier titre" to "Duo")
                    .forEachIndexed { i, (title, artist) ->
                        com.ketvoice.app.ui.common.TrackRow(Track("qa:look-$i", title, artist, "Album"), null, active = false, playing = false, onClick = {})
                    }
            }
        } }
        compose.waitForIdle()
    }

    @Test fun titlesLightAreUnchanged() { if (!mobile) return; rows(false); same("rows-light") }

    @Test fun titlesDarkAreUnchanged() { if (!mobile) return; rows(true); same("rows-dark") }

    @Test fun settingsLyricsPageIsUnchanged() {
        if (!mobile) return
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Lyrics").performClick()
        compose.waitForIdle()
        same("settings-lyrics-dark")
    }

    @Test fun lyricsAreUnchanged() {
        if (!mobile) return
        val plain = MusicApi.LyricLine(-1L, "Sous la lumière du soir")
        val sung = MusicApi.LyricLine(-20_000L, "On chante encore ensemble", words = listOf(
            MusicApi.LyricWord(-20_000L, "On ", -19_500L), MusicApi.LyricWord(-19_500L, "chante ", -19_000L),
            MusicApi.LyricWord(-19_000L, "encore ", -18_500L), MusicApi.LyricWord(-18_500L, "ensemble", -18_000L)))
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF1B1B20)).padding(24.dp)) {
                com.ketvoice.app.ui.player.LyricVoices(plain, active = false, timed = false)
                com.ketvoice.app.ui.player.LyricVoices(sung, active = true, timed = true)
            }
        } }
        compose.waitForIdle()
        same("lyrics-dark")
    }
}
