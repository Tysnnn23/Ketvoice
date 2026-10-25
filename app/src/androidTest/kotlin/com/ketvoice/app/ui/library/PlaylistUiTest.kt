package com.ketvoice.app.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.AppUi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlaylistUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val file get() = File(context.filesDir, "playlists-v1.json")
    private var oldLanguage = ""

    @Before fun prepare() {
        oldLanguage = AppSettings.language.value
        AppSettings.language.value = "Français"
        file.delete()
        File(file.path + ".bak").delete()
        PlaylistStore.init(context)
        PlaylistStore.resetForTest()
        AppUi.playlistId.value = null
        AppUi.showCreatePlaylist.value = false
        AppUi.showSpotifyImport.value = false
        AppUi.addToPlaylist.value = null
        AppUi.selectTab(AppUi.LIBRARY)
    }

    @After fun cleanUp() {
        AppSettings.language.value = oldLanguage
        AppUi.showCreatePlaylist.value = false
        AppUi.showSpotifyImport.value = false
        AppUi.addToPlaylist.value = null
        AppUi.playlistId.value = null
        AppUi.selectTab(AppUi.LIBRARY)
        file.delete()
        File(file.path + ".bak").delete()
    }

    @Test fun bothCreationEntryPointsAreVisibleInTheLibrary() {
        compose.setContent { KetvoiceTheme(useDark = true) { LibraryAndPlaylist() } }
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("create-playlist"))
        compose.onNodeWithText("Créer une playlist").assertIsDisplayed()
        compose.onNodeWithText("Importer depuis Spotify").assertIsDisplayed()
        compose.onNodeWithTag("create-playlist").assertIsEnabled()
        compose.onNodeWithTag("import-spotify").assertIsEnabled()
    }

    @Test fun aPlaylistWithARealNameIsSavedAndOpens() {
        compose.setContent { KetvoiceTheme(useDark = true) { LibraryAndPlaylist() } }
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("create-playlist"))
        compose.onNodeWithTag("create-playlist").performClick()
        compose.onNodeWithTag("create-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("playlist-name").performTextInput("QA soirée")
        compose.onNodeWithTag("create-confirm").assertIsEnabled()
        compose.onNodeWithTag("create-confirm").performClick()
        compose.waitUntil(5000) { AppUi.playlistId.value != null }
        compose.onNodeWithText("Playlist").assertExists()
        compose.onNodeWithText("QA soirée").assertExists()
        compose.onNodeWithText("Votre playlist est vide").assertExists()
        assertTrue(file.exists())
        assertFalse(PlaylistStore.lists.value.isEmpty())
        assertEquals("QA soirée", PlaylistStore.lists.value.first().name)
    }

    @Test fun spotifyImportRefusesAnUnsupportedLinkWithoutNetworkAccess() {
        compose.setContent { KetvoiceTheme(useDark = true) { LibraryAndPlaylist() } }
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("import-spotify"))
        compose.onNodeWithTag("import-spotify").performClick()
        compose.onNodeWithTag("spotify-link").assertExists()
        compose.onNodeWithText("Aucun fichier audio Spotify n'est téléchargé.", substring = true).assertExists()
        compose.onNodeWithText("Charger l'aperçu").assertIsNotEnabled()
        compose.onNodeWithTag("spotify-link").performTextInput("https://open.spotify.com/track/abc")
        compose.onNodeWithText("Charger l'aperçu").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Collez un lien de playlist Spotify publique").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Créer avec les titres affichés").assertIsNotEnabled()
    }
}

@androidx.compose.runtime.Composable
private fun LibraryAndPlaylist() {
    if (AppUi.overlay.intValue == AppUi.PLAYLIST) PlaylistScreen()
    else { LibraryScreen(); PlaylistDialogs() }
}
