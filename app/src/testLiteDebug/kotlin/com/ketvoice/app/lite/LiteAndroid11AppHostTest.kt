package com.ketvoice.app.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.LiteTuning
import com.ketvoice.app.MainActivity
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.GlassCompat
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.AppUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

private fun saveDecor(view: android.view.View, name: String) {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    runCatching { ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true) } // absent before Android 13
    view.draw(canvas)
    val dir = File("build/host-ui-preview/lite/android11").apply { mkdirs() }
    File(dir, "$name-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}

/**
 * Ketvoice Lite on Android 11, the whole app: KetvoiceApp, MainActivity and its first frames. Declared for
 * Android 11 (sdk 30) and for Android 12 with the app's own API checks of the glass told it runs on 11 (sdk 31);
 * without the Android 11 host runtime on the machine, `-ProbolectricSdks=31`. Host Robolectric, not a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 31], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiteAndroid11LaunchHostTest {
    @Before fun android11() { GlassCompat.api = 30 }
    @After fun reset() { GlassCompat.api = android.os.Build.VERSION.SDK_INT }

    @Test fun theLiteAppStartsAndDrawsItsFirstScreens() {
        assertTrue(LiteTuning.lite)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, com.ketvoice.app.player.PlaybackService::class.java))
        // Below Android 13, androidx.core guards receivers not exported with this permission of the app's own
        // manifest, which Android grants at install; Robolectric must be told.
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
        // No picture of the whole app here: drawing its page backdrop into a software canvas fails in Compose on the
        // host ('Only add dependencies during a tracking', with or without SoftBlur); Home and the player are drawn below.
        // Android 11 has no exact-alarm permission: the resume after a system stop may always be exact.
        if (android.os.Build.VERSION.SDK_INT < 31) assertTrue(com.ketvoice.app.player.PlaybackRevival.exactAllowed(app))
        controller.pause().stop().destroy()
    }
}

/** Home, the player and Settings → À propos of Ketvoice Lite as Android 11 draws them (see above). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 31], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiteAndroid11ScreensHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    private val device = LiteTuning.device
    private val modest = LiteTuning.modest

    @Before fun setup() {
        GlassCompat.api = 30
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() {
        GlassCompat.api = android.os.Build.VERSION.SDK_INT
        LiteTuning.device = device; LiteTuning.modest = modest
        AppSettings.reduceAnimations.value = false
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
        player.value = PlayerUiState()
    }

    private fun image(name: String) = compose.runOnIdle { saveDecor(compose.activity.window.decorView, name) }

    @Test fun home() {
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.home.HomeScreen() } }
        compose.onNodeWithTag("home-greeting").assertIsDisplayed()
        image("home-dark")
    }

    @Test fun thePlayer() {
        val tracks = (0 until 4).map { Track("qa:lite$it", "Titre $it", "Artiste $it", "Album", durationMs = 200_000) }
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0, durationMs = 200_000, positionMs = 61_000)
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.FullPlayerScreen(onDismiss = {}) } }
        compose.waitForIdle()
        compose.onAllNodesWithText("Titre 0").onFirst().assertIsDisplayed()
        image("player")
    }

    @Test fun aboutTellsWhichModeIsActiveAndWhy() {
        // A phone sold with 2 GB (1.9 GB reported, OpenGL ES 3.2).
        LiteTuning.device = LiteTuning.device(lowRamDevice = false, totalMemBytes = 1_900_000_000L, glEsVersion = LiteTuning.GLES_3_2)
        LiteTuning.modest = LiteTuning.device.modest
        compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.onNodeWithTag("settings-category-About").performScrollTo().performClick()
        compose.onNodeWithText("Ketvoice Lite").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ce téléphone a 2 Go de mémoire : effets allégés, identiques à l'œil", substring = true).assertExists()
        compose.onNodeWithText(LiteTuning.ANDROID_11_LINE, substring = true).assertExists()
        compose.onNodeWithText("Mode allégé").assertExists()
        image("settings-about-lite")
    }
}
