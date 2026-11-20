package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import org.junit.Assert.assertEquals
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
 * 7.5 (owner): album pages with real-looking covers (the 64 px fixtures of `CoverPickerFixturesTest`), light and
 * dark, at the top and scrolled: the cover's colours, the tighter text, the bigger cover and no fade or shrink
 * while scrolling. Drawn on the host, not a device; glass draws flat here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Album75HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.albumHeader.value = 0
        AppSettings.albumColors.value = true
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val name = chain.request.data.toString().substringAfter("fixture:")
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, cover(name)), chain.request,
                    coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun cover(name: String): Bitmap {
        val bytes = javaClass.classLoader!!.getResourceAsStream("covers/$name.png")!!.readBytes()
        val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return Bitmap.createScaledBitmap(small, 600, 600, true)
    }

    @Test fun thePartyNeverEndsLight() = album("the_party_never_ends", "The Party Never Ends", "Juice WRLD", dark = false)
    @Test fun thePartyNeverEndsDark() = album("the_party_never_ends", "The Party Never Ends", "Juice WRLD", dark = true)
    @Test fun skyBlueLight() = album("1989", "1989", "Taylor Swift", dark = false)
    @Test fun blackWithAGreenDiscDark() = album("synthetic_black_green_speck", "Midnight", "Nova Lune", dark = true)

    private fun album(fixture: String, title: String, artist: String, dark: Boolean) {
        val tracks = listOf("Intro", "Lucid", "Empty Out Your Pockets", "Love Letter", "Party By Myself", "Celebrate", "Oxy").mapIndexed { i, t ->
            Track("qa:a75:$fixture:$i", t, artist, title, durationMs = 151_000L + i * 13_400L, coverUrl = "fixture:$fixture",
                artistId = "UCqa75", albumId = "MPREqa75$fixture")
        }
        compose.setContent {
            KetvoiceTheme(useDark = dark) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                CompositionLocalProvider(LocalLargeScreen provides null,
                    com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout provides com.ketvoice.app.ui.adaptive.AdaptiveLayout(
                        config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat()),
                    androidx.compose.foundation.LocalOverscrollFactory provides null) {
                    com.ketvoice.app.ui.sub.AlbumPage(CatalogRequest("album", "MPREqa75$fixture", title),
                        CatalogPage(title, artist, "fixture:$fixture", tracks, "2024-11-29"), tracks, false, {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        val art = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
        val titleTop = compose.onNodeWithTag("album-title").fetchSemanticsNode().boundsInRoot
        // About three quarters of the 400 dp phone, centred.
        assertEquals("cover side", 300f, art.width / density, 2f)
        val top = capture()
        compose.onNodeWithTag("album-list").performTouchInput { swipeUp(startY = bottom - 60f, endY = bottom - 60f - 260f * density) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val moved = compose.onNodeWithTag("album-title").fetchSemanticsNode().boundsInRoot
        // No shrink, no fade: the header keeps its size and only moves up with the list.
        assertEquals("the header keeps its size", titleTop.width, moved.width, 1f)
        assertTrue("the header moved up", moved.top < titleTop.top - 100f)
        save(listOf(top, capture()), "album75-$fixture-${if (dark) "dark" else "light"}")
    }

    private fun capture(): Bitmap {
        var out: Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            out = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
                view.draw(canvas)
            }
        }
        return out!!
    }

    private fun save(frames: List<Bitmap>, name: String) {
        val w = frames.sumOf { it.width / 2 }; val h = frames.maxOf { it.height / 2 }
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet); var x = 0
        frames.forEach {
            canvas.drawBitmap(Bitmap.createScaledBitmap(it, it.width / 2, it.height / 2, true), x.toFloat(), 0f, null); x += it.width / 2
        }
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/album75/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
