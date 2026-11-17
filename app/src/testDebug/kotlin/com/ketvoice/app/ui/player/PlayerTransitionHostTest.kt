package com.ketvoice.app.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
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
 * A change of title in the player (6.2), frame by frame on the host in software, not on a device: the outgoing
 * artwork steps back while the next comes forward from its side of the queue, and the title cross-fades. Covers
 * are not loaded on the host, so each title shows its own colour placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerTransitionHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    private val tracks = listOf(Track("qa:aurore-1", "Premier titre", "Artiste A", "Album"), Track("qa:zenith-77", "Titre suivant", "Artiste B", "Album"))

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0)
    }

    @After fun reset() { player.value = PlayerUiState() }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/player-transition/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun theNextTitleComesInLikeAppleMusic() {
        val current = mutableStateOf(tracks[0])
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF2A1F3D)).padding(24.dp)) {
                ArtworkCanvas(current.value, null, playing = true, modifier = Modifier.fillMaxWidth().height(352.dp), depth = true)
                PlayerTitle(current.value, false, null, sheetAction = {}, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        } }
        compose.mainClock.advanceTimeBy(1_000)
        image("0-before")
        compose.runOnIdle {
            current.value = tracks[1]
            player.value = player.value.copy(index = 1)
        }
        var elapsed = 0L
        for (ms in listOf(120L, 260L, 340L, 420L, 600L)) {
            compose.mainClock.advanceTimeBy(ms - elapsed)
            elapsed = ms
            image("1-at-${ms}ms")
        }
        compose.mainClock.advanceTimeBy(1_500)
        image("2-after")
    }

    @Test fun reducedMotionOnlyCrossFades() {
        AppSettings.reduceAnimations.value = true
        val current = mutableStateOf(tracks[0])
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF2A1F3D)).padding(24.dp)) {
                ArtworkCanvas(current.value, null, playing = true, modifier = Modifier.fillMaxWidth().height(352.dp), depth = true)
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { current.value = tracks[1] }
        compose.mainClock.advanceTimeBy(80)
        image("reduced-at-80ms")
    }
}
