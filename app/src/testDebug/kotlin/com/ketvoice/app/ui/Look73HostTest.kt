package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
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
 * 7.3 (owner's picks): album header in its three styles, the search start page, the breathing artwork and the
 * credits sheet, drawn on the host (not a device) with made-up data. A Canvas cannot decode here: its place shows
 * the cover it starts from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Look73HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tracks = listOf("Lumière froide", "Nuit d'encre", "Les heures bleues", "Septembre", "Métropole", "Écho").mapIndexed { i, t ->
        Track("qa:look:$i", t, if (i == 2) "Nova Lune, Iris Vale" else "Nova Lune", "Horizons", durationMs = 151_000L + i * 13_400L,
            coverUrl = "fixture:cover:${i % 5}", artistId = "UCqaNova", albumId = "MPREqaLook", isrc = "FRQA12500001")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Class<*>, name: String) = ReflectionHelpers.getStaticField<MutableStateFlow<T>>(owner, name)

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        AppSettings.albumHeroCanvas.value = true
        pictures()
    }

    @After fun reset() {
        AppSettings.albumHeader.value = 0
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = emptyMap()
        field<List<Track>>(ListeningHistory::class.java, "_tracks").value = emptyList()
    }

    @Test fun headerStylesFallBackToTheCover() {
        assertEquals(com.ketvoice.app.ui.sub.HeaderStyle.Cover, com.ketvoice.app.ui.sub.AlbumHeader.style(1, hasCanvas = false))
        assertEquals(com.ketvoice.app.ui.sub.HeaderStyle.Canvas, com.ketvoice.app.ui.sub.AlbumHeader.style(1, hasCanvas = true))
        assertEquals(com.ketvoice.app.ui.sub.HeaderStyle.CoverOnCanvas, com.ketvoice.app.ui.sub.AlbumHeader.style(2, hasCanvas = true))
        assertTrue(!com.ketvoice.app.ui.sub.AlbumHeader.wantsCanvas(true, 2, reduced = true))
        assertTrue(!com.ketvoice.app.ui.sub.AlbumHeader.wantsCanvas(false, 1, reduced = false))
    }

    @Test fun albumHeaderCover() = album(0, "album-header-cover", "look73-album-1-cover")

    @Test fun albumHeaderCanvas() = album(1, "album-header-canvas", "look73-album-2-canvas")

    @Test fun albumHeaderCoverOnCanvas() = album(2, "album-header-coveroncanvas", "look73-album-3-cover-canvas")

    private fun album(style: Int, tag: String, name: String) {
        AppSettings.albumHeader.value = style
        if (style != 0) {
            val file = File.createTempFile("canvas", ".mp4").apply { deleteOnExit() }
            field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = mapOf(tracks[0].id to file.absolutePath)
        }
        frame {
            com.ketvoice.app.ui.sub.AlbumPage(CatalogRequest("album", "MPREqaLook", "Horizons"),
                CatalogPage("Horizons", "Nova Lune", "fixture:cover:3", tracks, "2025-03-14"), tracks, false, {})
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag(tag).assertExists()
        compose.onNodeWithTag("album-title").assertIsDisplayed()
        image(name)
    }

    @Test fun searchStartPlayedFirstThenCategories() {
        field<List<Track>>(ListeningHistory::class.java, "_tracks").value = tracks + tracks.first()
        val genres = listOf("Rap/Hip Hop", "Pop", "Électro", "R&B", "Rock", "Jazz", "Chill", "Années 2000").mapIndexed { i, g ->
            CatalogGenre("$i", g, "fixture:cover:$i")
        }
        frame { Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).statusBarsPadding().padding(top = 12.dp)) {
            com.ketvoice.app.ui.overlay.SearchStart(genres = genres)
        } }
        compose.mainClock.advanceTimeBy(800)
        val played = compose.onNodeWithTag("search-recent-played").fetchSemanticsNode().boundsInRoot
        val browse = compose.onNodeWithTag("search-browse").fetchSemanticsNode().boundsInRoot
        assertTrue("the titles played come first", played.bottom <= browse.top)
        compose.onAllNodesWithText("Recherches récentes").assertCountEquals(0)
        image("look73-search-start")
        // The tile colour keeps white text readable whatever the artwork.
        val light = com.ketvoice.app.ui.overlay.SearchStartModel.tileColor(Color(0xFFF8E9A1), Color.Black)
        assertTrue(light.luminance() <= com.ketvoice.app.ui.overlay.SearchStartModel.MAX_LUMINANCE)
    }

    @Test fun theArtworkBreathesOnPauseAndPlay() {
        val playing = mutableStateOf(true)
        frame {
            val ambience = com.ketvoice.app.ui.player.CoverAmbience(Color(0xFF3A1D44), accent = Color(0xFFE96443))
            Box(Modifier.fillMaxSize()) {
                com.ketvoice.app.ui.player.AmbientArtwork("fixture:cover:1", ambience, playing.value)
                val breath = com.ketvoice.app.ui.player.rememberArtworkBreath(playing.value)
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(340.dp).graphicsLayer {
                        val s = com.ketvoice.app.ui.player.pausedArtworkScale(breath.value); scaleX = s; scaleY = s
                        shape = RoundedCornerShape((28f * breath.value.coerceIn(0f, 1f)).dp); clip = true
                    }.testTag("breath-artwork")) {
                        coil.compose.AsyncImage("fixture:cover:1", null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val full = compose.onNodeWithTag("breath-artwork").fetchSemanticsNode().boundsInRoot
        image("look73-player-1-playing")
        playing.value = false
        compose.mainClock.advanceTimeBy(1_200)
        image("look73-player-2-paused")
        playing.value = true
        compose.mainClock.advanceTimeBy(160)
        image("look73-player-3-springing-back")
        compose.mainClock.advanceTimeBy(1_200)
        assertTrue(full.width > 0f)
    }

    @Test fun creditsShowOnlyWhatIsKnown() {
        val track = tracks[2]
        val details = TrackCreditsFixture.details
        frame {
            Box(Modifier.fillMaxSize().background(Color(0xFF101014)), contentAlignment = Alignment.BottomCenter) {
                com.ketvoice.app.ui.common.TrackCreditsPanel(track, 760.dp, details)
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("credits-label").assertExists()
        compose.onNodeWithTag("credits-year").assertExists()
        compose.onAllNodesWithTag("credits-bpm").assertCountEquals(0)
        compose.onNodeWithTag("credits-artists").assertExists()
        compose.onAllNodesWithText("Nova Lune, Iris Vale").assertCountEquals(2)
        image("look73-credits")
        val rows = com.ketvoice.app.ui.common.TrackCredits.rows(Track("x", "T", "", ""), com.ketvoice.app.ui.common.TrackCredits.Details(), null)
        assertTrue("an unknown title shows no empty row", rows.isEmpty())
    }

    private object TrackCreditsFixture {
        val details = com.ketvoice.app.ui.common.TrackCredits.Details(released = "2025-03-14", label = "Lune Records",
            genres = listOf("Pop", "Électro"))
    }

    private fun frame(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KetvoiceTheme(useDark = true) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                val width = config.screenWidthDp.toFloat(); val height = config.screenHeightDp.toFloat()
                CompositionLocalProvider(LocalLargeScreen provides null,
                    com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout provides com.ketvoice.app.ui.adaptive.AdaptiveLayout(width, height),
                    androidx.compose.foundation.LocalOverscrollFactory provides null) { content() }
            }
        }
    }

    private fun pictures() {
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val index = chain.request.data.toString().substringAfterLast(':').toIntOrNull() ?: 0
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, cover(index)), chain.request,
                    coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun cover(index: Int): Bitmap {
        val palettes = listOf(
            intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF6DD5ED.toInt()),
            intArrayOf(0xFF41295A.toInt(), 0xFF2F0743.toInt(), 0xFFE96443.toInt()),
            intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt(), 0xFFF5F7A1.toInt()),
            intArrayOf(0xFFFF512F.toInt(), 0xFFDD2476.toInt(), 0xFF3A1C71.toInt()),
            intArrayOf(0xFF1D2B64.toInt(), 0xFFF8CDDA.toInt(), 0xFF8E9EAB.toInt()),
            intArrayOf(0xFF232526.toInt(), 0xFF414345.toInt(), 0xFFC9A227.toInt()),
            intArrayOf(0xFF5A3F37.toInt(), 0xFF2C7744.toInt(), 0xFFDCE35B.toInt()),
            intArrayOf(0xFF614385.toInt(), 0xFF516395.toInt(), 0xFFF0C27B.toInt()))
        val size = 400
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), palettes[index % palettes.size], null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null; paint.color = 0x55FFFFFF
        canvas.drawCircle(size * .5f, size * .5f, size * .26f, paint)
        return bitmap
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/look73/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
