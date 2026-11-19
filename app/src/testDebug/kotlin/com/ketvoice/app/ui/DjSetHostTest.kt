package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.DjSet
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.DjSetQueueSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** « Mode DJ set » in the queue (7.0.0), rendered on the host, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h300dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DjSetHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tracks = List(9) { Track("t$it", "Titre $it", "Artiste", "Album") }
    private val queue = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { "e" + it.id }, index = 2)

    private fun save(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theQueueOffersASetThenShowsItsHeader() {
        val energies = listOf(.3f, .4f, .5f, Float.NaN, .7f, .9f, .8f, .6f, .5f)
        DjSet.showForTest(null)
        val state = mutableStateOf(queue)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1E1C24)).padding(16.dp)) { DjSetQueueSection(state.value) }
        } }
        compose.onNodeWithTag("queue-dj-set").assertIsDisplayed()
        save("dj-set-offer")
        DjSet.showForTest(DjSet.Live(tracks.map { it.id }.toSet(), tracks.mapIndexed { i, t -> t.id to energies[i] }.toMap()))
        compose.waitForIdle()
        compose.onNodeWithTag("queue-dj-set-header").assertIsDisplayed()
        compose.onNodeWithTag("dj-set-reorder").assertIsDisplayed()
        compose.onNodeWithTag("dj-set-export").assertIsDisplayed()
        save("dj-set-header")
        DjSet.showForTest(null)
    }
}
