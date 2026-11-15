package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.overlay.SearchArtistCircle
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The small menu under an artist's circle in the search, rendered on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchArtistHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @org.junit.Before fun french() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        ArtistPrefs.replaceForTest()
    }
    @After fun clear() = ArtistPrefs.replaceForTest()

    @Test fun aLongPressOnTheCircleBlocksUnblocksOrKeepsTheArtist() {
        var opened = 0
        compose.setContent {
            KetvoiceTheme(useDark = true) {
                Box(Modifier.padding(40.dp)) { SearchArtistCircle(MusicArtist("UCmaes", "Maes", null)) { opened++ } }
            }
        }
        // A tap opens the artist, nothing else.
        compose.onNodeWithTag("search-artist-UCmaes").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithTag("search-artist-menu").assertDoesNotExist()
        // A long press opens the menu under the circle.
        compose.onNodeWithTag("search-artist-UCmaes").performTouchInput { longClick() }
        compose.onNodeWithText("Bloquer l'artiste").assertIsDisplayed()
        compose.onNodeWithText("Mettre en favori").assertIsDisplayed()
        compose.onNodeWithTag("artist-block").performClick()
        compose.runOnIdle { assertTrue(ArtistPrefs.isBlocked("Maes")) }
        compose.onNodeWithContentDescription("Artiste bloqué", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("search-artist-UCmaes").performTouchInput { longClick() }
        compose.onNodeWithText("Débloquer l'artiste").assertIsDisplayed()
        compose.onNodeWithTag("artist-favourite").performClick()
        compose.runOnIdle { assertTrue(ArtistPrefs.isFavourite("Maes")); assertFalse("a favourite is unblocked", ArtistPrefs.isBlocked("Maes")) }
        compose.onNodeWithTag("search-artist-UCmaes").performTouchInput { longClick() }
        compose.onNodeWithText("Retirer des favoris").assertIsDisplayed()
        compose.onNodeWithTag("artist-unfavourite").performClick()
        compose.runOnIdle { assertFalse(ArtistPrefs.isFavourite("Maes")); assertEquals("a long press never opens the page", 1, opened) }
    }
}
