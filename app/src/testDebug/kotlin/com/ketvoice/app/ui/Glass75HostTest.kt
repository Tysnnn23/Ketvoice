package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * 7.5 host renders (host Skia, not a phone; the glass blur draws flat here): controls that turn to glass while
 * held, search and sort in a playlist, Favoris and Téléchargements, and the standby screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Glass75HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    private val colours = listOf(0xFFB8473A, 0xFF2F6F8F, 0xFFD9A441, 0xFF4F7A4A, 0xFF6B4C9A, 0xFF1F1F24).map { it.toInt() }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        clear()
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).allowHardware(false).crossfade(false).build())
    }

    @After fun clear() {
        coil.Coil.reset()
        saved.value = emptyList(); known.value = emptyList()
        AppSettings.favoriteIds.value = emptySet()
        AppSettings.reduceAnimations.value = false
        AppSettings.highPerformanceMode.value = false
        PlaylistStore.resetForTest()
        AppUi.closeOverlays(); AppUi.playlistId.value = null
    }

    private fun cover(index: Int): String {
        val file = File(compose.activity.cacheDir, "cover75-$index.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colours[index % colours.size]) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return file.toURI().toString()
    }

    private fun track(id: String, title: String, artist: String, album: String, art: Int, ms: Long = 214_000) =
        Track("fixture:$id", title, artist, album, durationMs = ms, coverUrl = cover(art))

    private val tracks by lazy { listOf(
        track("a", "Été indien", "Joe Dassin", "Le Jardin du Luxembourg", 0, 274_000),
        track("b", "Halo", "Beyoncé", "I Am... Sasha Fierce", 1, 261_000),
        track("c", "Alors on danse", "Stromae", "Cheese", 2, 206_000),
        track("d", "Bella", "Maître Gims", "Subliminal", 3, 233_000),
        track("e", "Papaoutai", "Stromae", "Racine carrée", 4, 232_000)) }

    private fun image(name: String) {
        repeat(4) { Thread.sleep(120); compose.waitForIdle() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/glass-75/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null, content = content)
        } }
        compose.waitForIdle()
    }

    // ---- Controls that turn to glass while touched

    @Test fun heldSlidersGrowALensThatGoesAwayOnRelease() {
        var value by mutableFloatStateOf(.55f)
        var on by mutableStateOf(true)
        show(true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF3B2A20)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                com.ketvoice.app.ui.common.GlassSlider(value, { value = it }, Modifier.testTag("held-slider"))
                com.ketvoice.app.ui.player.VolumeRail(.4f, Modifier.fillMaxWidth().testTag("held-volume")) {}
                Box(Modifier.testTag("held-switch")) { com.ketvoice.app.ui.common.LiquidGlassSwitch(on, { on = it }) }
            }
        }
        compose.onNodeWithTag("press-lens").assertDoesNotExist()
        image("controls-rest")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("held-slider").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(30f, 0f)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithTag("press-lens").assertCountEquals(1)
        image("slider-held")
        compose.onNodeWithTag("held-slider").performTouchInput { up() }
        compose.onNodeWithTag("held-volume").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(20f, 0f)) }
        compose.mainClock.advanceTimeBy(600)
        image("volume-held")
        compose.onNodeWithTag("held-volume").performTouchInput { up() }
        compose.onNodeWithTag("held-switch").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        image("switch-held")
        compose.onNodeWithTag("held-switch").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("press-lens").assertDoesNotExist()
    }

    @Test fun reducedMotionAndPerformanceModeKeepTheSimpleSwell() {
        for (setting in listOf("reduced", "performance")) {
            AppSettings.reduceAnimations.value = setting == "reduced"
            AppSettings.highPerformanceMode.value = setting == "performance"
            assertFalse(setting, com.ketvoice.app.ui.common.PressLensLook.available())
        }
        AppSettings.reduceAnimations.value = false; AppSettings.highPerformanceMode.value = false
        assertTrue(com.ketvoice.app.ui.common.PressLensLook.available())
        assertEquals(1f, com.ketvoice.app.ui.common.PressLensLook.scale(1f), 0f)
        assertEquals(0f, com.ketvoice.app.ui.common.PressLensLook.alpha(0f), 0f)
    }

    // ---- Search and sort inside lists

    private fun playlist() {
        kotlinx.coroutines.runBlocking { PlaylistStore.init(compose.activity) }
        @Suppress("UNCHECKED_CAST")
        (PlaylistStore.lists as MutableStateFlow<List<LocalPlaylist>>).value = listOf(LocalPlaylist(id = "qa:p75", name = "Route du soir",
            entries = tracks.map { PlaylistEntry(id = "e:${it.id}", title = it.title, artist = it.artist, track = it) }))
        AppUi.playlistId.value = "qa:p75"
    }

    private fun order(vararg titles: String) = titles.filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        .sortedBy { compose.onAllNodesWithText(it)[0].fetchSemanticsNode().boundsInRoot.top }

    @Test fun aPlaylistIsSearchedAndSortedAndKeepsItsOrder() {
        playlist()
        show(true) { com.ketvoice.app.ui.library.PlaylistScreen() }
        compose.onNodeWithTag("playlist-search").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("playlist-search").performTextInput("stromae")
        compose.waitForIdle()
        assertEquals(listOf("Alors on danse", "Papaoutai"), order("Été indien", "Halo", "Alors on danse", "Bella", "Papaoutai"))
        image("playlist-search-dark")
        compose.onNodeWithTag("playlist-search").performTextClearance()
        compose.onNodeWithTag("playlist-sort").performClick()
        compose.onNodeWithTag("playlist-sort:Title").assertIsDisplayed()
        image("playlist-sort-menu-dark")
        compose.onNodeWithTag("playlist-sort:Title").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Alors on danse", "Bella", "Été indien", "Halo", "Papaoutai"),
            order("Été indien", "Halo", "Alors on danse", "Bella", "Papaoutai"))
        // Remembered for this playlist; the stored order is untouched.
        assertEquals(ListSort.Title, ListOrders.load(compose.activity, "playlist:qa:p75", ListOrder(ListSort.Added)).sort)
        assertEquals(tracks.map { it.title }, PlaylistStore.lists.value.single().entries.map { it.title })
    }

    @Test fun favouritesAndDownloadsShareTheSearchAndSorts() {
        known.value = tracks
        AppSettings.favoriteIds.value = tracks.map { it.id }.toCollection(LinkedHashSet())
        show(false) { com.ketvoice.app.ui.sub.CatalogScreen(CatalogRequest("favorites", "local", "Favoris")) }
        compose.onNodeWithTag("liked-search").performScrollTo().performTextInput("BEYONCE")
        compose.waitForIdle()
        assertEquals(listOf("Halo"), order("Été indien", "Halo", "Alors on danse", "Bella", "Papaoutai"))
        image("liked-search-light")
    }

    @Test fun downloadsSearchIgnoresAccentsAndSortsFromTheBarMenu() {
        saved.value = tracks.mapIndexed { i, t ->
            val file = File(compose.activity.cacheDir, t.id.replace(':', '_') + ".bin").apply { writeText("fixture"); setLastModified(1_700_000_000_000L + i * 1000L) }
            Downloader.Item(t, file, "AAC · 256 kbps", 4_000_000L + i)
        }
        show(true) { com.ketvoice.app.ui.sub.DownloadsScreen() }
        compose.onNodeWithTag("downloads-search").performScrollTo().performTextInput("ete")
        compose.waitForIdle()
        assertEquals(listOf("Été indien"), order("Été indien", "Halo", "Alors on danse", "Bella", "Papaoutai"))
        compose.onNodeWithTag("downloads-search").performTextClearance()
        compose.onNodeWithTag("downloads-menu").performClick()
        compose.onNodeWithTag("downloads-sort:Duration").assertIsDisplayed()
        image("downloads-sort-menu-dark")
        compose.onNodeWithTag("downloads-sort:Duration").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Alors on danse", "Papaoutai"), order("Alors on danse", "Papaoutai"))
    }

    // ---- Standby while charging

    @Test @Config(qualifiers = "w860dp-h400dp-land-mdpi")
    fun theStandbyScreenShowsTheClockTitleAndControlsOnTap() {
        val track = tracks[1]
        val clock = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 14); set(java.util.Calendar.MINUTE, 7) }
        show(true) { com.ketvoice.app.ui.standby.StandbyScreen(track, playing = true, onClose = {}, clock = { clock }) }
        compose.onNodeWithTag("standby-clock").assertIsDisplayed()
        compose.onNodeWithText("Halo").assertIsDisplayed()
        compose.onNodeWithTag("standby-controls").assertDoesNotExist()
        image("standby-dark")
        compose.onNodeWithTag("standby").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("standby-controls").assertIsDisplayed()
        compose.onNodeWithTag("standby-close").assertIsDisplayed()
        image("standby-controls-dark")
    }

    @Test fun standbyRules() {
        val r = com.ketvoice.app.ui.standby.StandbyRules
        val now = 10_000_000L
        assertTrue(r.shows(true, true, true, true, 0L, now, true, false))
        assertTrue("recently playing", r.shows(true, true, true, false, now - 60_000L, now, true, false))
        assertFalse("paused long ago", r.shows(true, true, true, false, now - r.RECENT_MS - 1, now, true, false))
        assertFalse("off by default", r.shows(false, true, true, true, 0L, now, true, false))
        assertFalse("unplugged", r.shows(true, false, true, true, 0L, now, true, false))
        assertFalse("upright", r.shows(true, true, false, true, 0L, now, true, false))
        assertFalse("closed", r.shows(true, true, true, true, 0L, now, true, true))
        assertFalse(AppSettings.standbyMode.value)
        val at = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 21); set(java.util.Calendar.MINUTE, 5) }
        assertEquals("21:05", r.clockText(at, true)); assertEquals("9:05", r.clockText(at, false))
        assertTrue(r.night(23)); assertTrue(r.night(3)); assertFalse(r.night(12))
        assertTrue(r.dim(23) < .5f); assertEquals(1f, r.dim(12), 0f)
        val drifts = (0L..12L).map(r::drift)
        drifts.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        drifts.forEach { assertTrue(kotlin.math.abs(it.x) <= r.DRIFT_DP && kotlin.math.abs(it.y) <= r.DRIFT_DP) }
        assertFalse("no Canvas in the performance mode", r.canvasAllowed(true, false, false, 12))
        assertFalse("nor on battery saver", r.canvasAllowed(false, true, false, 12))
        assertFalse("nor at night", r.canvasAllowed(false, false, false, 23))
        val plugged = android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED).putExtra(android.os.BatteryManager.EXTRA_PLUGGED, 2)
        assertTrue(com.ketvoice.app.ui.standby.isCharging(plugged))
        assertFalse(com.ketvoice.app.ui.standby.isCharging(null))
    }
}
