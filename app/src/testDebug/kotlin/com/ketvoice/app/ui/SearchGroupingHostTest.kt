package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.overlay.SearchResultRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class SearchGroupingHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun groupedRecordingOffersTheActualTidalAndYoutubeChoices() {
        val yt = Track("yt:1", "Song", "Artist", "Album", durationMs = 180_000, videoId = "one")
        val tidal = yt.copy(id = "cs:source:tidal:42", addon = "cs:source", addonId = "tidal:42", videoId = null)
        var selected: Track? = null
        compose.setContent { KetvoiceTheme(useDark = true) {
            SearchResultRow(yt, listOf(yt, tidal), null, onClick = {}, onVariant = { selected = it })
        } }
        compose.onNodeWithText("Song").assertIsDisplayed()
        compose.onNodeWithText("TIDAL").assertIsDisplayed().performClick()
        assertEquals(tidal, selected)
        compose.onNodeWithText("YouTube").performClick()
        assertEquals(yt, selected)
    }
}
