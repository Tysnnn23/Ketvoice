package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import com.ketvoice.app.ui.common.TrackRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tap on a title row plays it, on a phone and in a large window alike (6.0.0 Beta 3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowTapHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun taps(large: Boolean): Int {
        var played = 0
        val track = Track("qa:tap", "Titre touché", "Artiste", "Album")
        compose.setContent {
            CompositionLocalProvider(LocalLargeScreen provides (if (large) LargeScreen.info(1280f, 800f, false, false) else null)) {
                KetvoiceTheme(useDark = false) {
                    Column(Modifier.fillMaxSize()) { TrackRow(track, null, active = false, playing = false, onClick = { played++ }) }
                }
            }
        }
        compose.onNodeWithText("Titre touché").performClick()
        compose.waitForIdle()
        return played
    }

    @Test fun aTapPlaysOnAPhone() = assertEquals(1, taps(large = false))
    @Test fun aTapPlaysInALargeWindow() = assertEquals(1, taps(large = true))
}
