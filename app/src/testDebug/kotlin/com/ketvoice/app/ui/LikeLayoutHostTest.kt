package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.PlayerTitle
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A like plays its burst around the heart without moving the title or the buttons (5.9.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LikeLayoutHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun reset() { AppSettings.favoriteIds.value = emptySet() }

    @Test fun likingDoesNotMoveTheTitle() {
        val track = Track("qa:like", "In My Head", "Juice WRLD", "Album")
        AppSettings.favoriteIds.value = emptySet()
        compose.setContent { KetvoiceTheme(useDark = true) { Box(Modifier.fillMaxWidth()) { PlayerTitle(track, compact = false, cover = null, sheetAction = {}) } } }
        val before = compose.onNodeWithText("In My Head").fetchSemanticsNode().boundsInRoot
        // The description follows the app's language, which other tests may have changed.
        compose.onNode(hasContentDescription("Add to favorites") or hasContentDescription("Ajouter aux favoris"), useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        val after = compose.onNodeWithText("In My Head").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, .5f)
        assertEquals(before.left, after.left, .5f)
        assertTrue(track.id in AppSettings.favoriteIds.value)
    }
}
