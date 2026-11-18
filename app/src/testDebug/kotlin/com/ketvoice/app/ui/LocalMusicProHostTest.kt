package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LocalCorrections
import com.ketvoice.app.data.LocalMatch
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.data.LocalTags
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.sub.LocalMusicScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** « Musique locale pro » (7.0): albums with discs, compilations, folders, streaming twins and the corrections sheet, on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalMusicProHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): MutableStateFlow<T> = owner::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as MutableStateFlow<T>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        AppSettings.localMusicEnrich.value = false
    }

    @After fun clear() {
        field<List<Track>>(LocalMusic, "_tracks").value = emptyList()
        field<Map<String, LocalTags>>(LocalMusic, "_tags").value = emptyMap()
        LocalCorrections.resetForTest()
        AppSettings.localMusicEnrich.value = true
        AppUi.closeOverlays()
    }

    private fun image(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun library() {
        val entries = listOf(
            Triple("local:1", "Digital Love", LocalTags("Digital Love", "Daft Punk", "Discovery", "Daft Punk", trackNo = 3, discNo = 1, year = 2001, durationMs = 301_000, folder = "Music/Daft Punk/Discovery")),
            Triple("local:2", "One More Time", LocalTags("One More Time", "Daft Punk", "Discovery", "Daft Punk", trackNo = 1, discNo = 1, year = 2001, durationMs = 320_000, folder = "Music/Daft Punk/Discovery")),
            Triple("local:3", "Bonus Track", LocalTags("Bonus Track", "Daft Punk feat. Romanthony", "Discovery", "Daft Punk", trackNo = 1, discNo = 2, year = 2001, durationMs = 200_000, folder = "Music/Daft Punk/Discovery")),
            Triple("local:4", "Summer A", LocalTags("Summer A", "Nova", "Été 2024", null, trackNo = 1, durationMs = 180_000, folder = "Music/Compil")),
            Triple("local:5", "Summer B", LocalTags("Summer B", "Orion", "Été 2024", null, trackNo = 2, durationMs = 190_000, folder = "Music/Compil")),
            Triple("local:6", "Halo", LocalTags("Halo", null, null, null, durationMs = 261_000, folder = "Download")))
        field<List<Track>>(LocalMusic, "_tracks").value = entries.map { (id, title, t) ->
            Track(id, title, t.artist ?: "Artiste inconnu", t.album.orEmpty(), durationMs = t.durationMs, streamUrl = "content://fixture/$id")
        }
        field<Map<String, LocalTags>>(LocalMusic, "_tags").value = entries.associate { it.first to it.third }
        val halo = Track("yt:halo", "Halo", "Beyoncé", "I Am... Sasha Fierce", durationMs = 261_000)
        field<Map<String, Track>>(LocalCorrections, "_streaming").value = mapOf("local:6" to halo, "local:2" to halo.copy(id = "yt:omt", title = "One More Time", artist = "Daft Punk"))
        field<List<LocalMatch.Proposal>>(LocalCorrections, "_proposals").value = listOf(
            LocalMatch.Proposal("local:6", halo, 0.97, mapOf(LocalMatch.Field.Artist to "Beyoncé", LocalMatch.Field.Album to "I Am... Sasha Fierce",
                LocalMatch.Field.AlbumArtist to "Beyoncé")),
            LocalMatch.Proposal("local:4", halo.copy(title = "Summer A", artist = "Nova"), 0.9, mapOf(LocalMatch.Field.AlbumArtist to "Nova")))
    }

    private fun open() {
        shadowOf(compose.activity.application).grantPermissions(LocalMusic.permission)
        compose.setContent { KetvoiceTheme(useDark = true) { LocalMusicScreen() } }
        compose.waitUntil(5_000) { !LocalMusic.scanning.value && LocalMusic.scanned.value }
        library()
        compose.waitForIdle()
    }

    private fun view(name: String) {
        compose.onNodeWithTag("local-view").onChildren().filterToOne(hasText(name)).performClick()
        compose.waitForIdle()
    }

    @Test fun albumsHaveTheirArtistDiscsAndCompilationsAndFoldersTheirTitles() {
        open()
        compose.onNodeWithTag("local-corrections").assertIsDisplayed()
        compose.onAllNodesWithTag("local-streaming", useUnmergedTree = true).assertCountEquals(2)
        image("local-pro-titles")
        view("Albums")
        compose.onNodeWithText("Discovery").assertIsDisplayed()
        compose.onNodeWithText("Été 2024").assertIsDisplayed()
        compose.onNodeWithText("Artistes divers").assertIsDisplayed()
        image("local-pro-albums")
        compose.onNodeWithText("Discovery").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("local-album-header").assertIsDisplayed()
        compose.onAllNodesWithTag("local-disc", useUnmergedTree = true).assertCountEquals(2)
        fun order() = listOf("One More Time", "Digital Love", "Bonus Track").sortedBy {
            compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("Disc then track order", listOf("One More Time", "Digital Love", "Bonus Track"), order())
        image("local-pro-album-page")
        view("Dossiers")
        compose.onNodeWithText("Compil").assertIsDisplayed()
        compose.onNodeWithText("Music/Daft Punk").assertIsDisplayed()
        image("local-pro-folders")
    }

    @Test fun theCorrectionsAreAcceptedPerAlbumOrAllAtOnceAndStayInTheApp() {
        // The sheet is the phone's spring sheet; Quest's centred panel is not covered by this render.
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        open()
        compose.onNodeWithTag("local-corrections").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("local-corrections-sheet").assertIsDisplayed()
        compose.onNodeWithText("Écrire dans le fichier").assertIsDisplayed()
        compose.onAllNodesWithTag("local-correction").assertCountEquals(2)
        image("local-pro-corrections")
        compose.onAllNodesWithTag("local-accept-album")[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, LocalCorrections.proposals.value.size)
        assertEquals(1, LocalCorrections.accepted.value.size)
        compose.onAllNodesWithTag("local-accept-album")[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(setOf("local:4", "local:6"), LocalCorrections.accepted.value.keys)
        assertEquals("Beyoncé", LocalCorrections.accepted.value["local:6"]?.get(LocalMatch.Field.Artist))
    }
}
