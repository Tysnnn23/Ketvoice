package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.onboarding.Onboarding
import com.ketvoice.app.ui.onboarding.OnboardingChoices
import org.junit.After
import org.junit.Assert.*
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
 * The first-launch welcome (6.2) drawn on the host in software, not on a device: its three pages on a phone, in a
 * tablet's landscape window (also Quest's window shape) and with large text, and what it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var finished: OnboardingChoices? = null

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.reduceDynamicBlur.value = true
        AppSettings.automix.value = false
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        com.ketvoice.app.ui.onboarding.PlaylistImportEntry.open = null
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/onboarding/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun show(dark: Boolean = false, importAvailable: Boolean = false) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            Onboarding(onFinish = { finished = it }, importAvailable = importAvailable)
        } }
        compose.waitForIdle()
    }

    private fun next() { compose.onNodeWithTag("onboarding-next").performClick(); compose.waitForIdle() }

    private fun allPages(prefix: String, dark: Boolean = false) {
        show(dark)
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Bienvenue sur Ketvoice")
        compose.onNodeWithTag("onboarding-languages").assertExists()
        image("$prefix-1-language")
        next()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Retrouvez vos playlists")
        image("$prefix-2-playlists")
        next()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Ketmix et voix")
        image("$prefix-3-features")
    }

    @Test fun threePagesOnAPhone() = allPages("phone")

    @Test fun threePagesOnAPhoneAtNight() = allPages("phone-dark", dark = true)

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun threePagesInALandscapeWindow() {
        allPages("tablet-landscape")
        val art = compose.onNodeWithTag("onboarding-art", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("onboarding-title").fetchSemanticsNode().boundsInRoot
        assertTrue("the picture stands left of the page", art.right <= title.left)
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun threePagesOnAPortraitTablet() = allPages("tablet-portrait")

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 1.6f)
    fun largeTextScrolls() {
        show()
        compose.onNodeWithTag("onboarding-next").assertIsDisplayed()
        next(); next()
        compose.onNodeWithTag("onboarding-voice").performScrollTo().assertIsDisplayed()
        image("phone-large-text-3")
    }

    @Test fun theChoicesAreCarriedOut() {
        show()
        next(); next()
        compose.onNodeWithTag("onboarding-ketmix").performClick()
        compose.onNodeWithTag("onboarding-voice").performClick()
        compose.onNodeWithTag("onboarding-voice").performClick()
        next()
        compose.runOnIdle { assertEquals(OnboardingChoices(ketmix = true, voice = false, importPlaylists = false), finished) }
    }

    @Test fun itCanBeSkippedFromTheFirstPage() {
        show()
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.runOnIdle { assertEquals(OnboardingChoices(ketmix = false, voice = false, importPlaylists = false), finished) }
    }

    @Test fun withoutTheImportItSaysSoon() {
        show(importAvailable = false)
        next()
        compose.onNodeWithTag("onboarding-import-soon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("onboarding-import-switch").assertDoesNotExist()
    }

    @Test fun withTheImportItIsOfferedAndOpenedAtTheEnd() {
        show(importAvailable = true)
        next()
        compose.onNodeWithTag("onboarding-import-switch").assertIsDisplayed().performClick()
        image("phone-2-playlists-import")
        next(); next()
        compose.runOnIdle { assertTrue(finished!!.importPlaylists) }
    }

    @Test fun theImportHookIsFoundWhenSet() {
        assertNull("no AppUi.openPlaylistImport in this branch", com.ketvoice.app.ui.onboarding.PlaylistImportEntry.resolve()
            .takeIf { com.ketvoice.app.ui.onboarding.PlaylistImportEntry.open == null && !hasAppUiImport() })
        var opened = 0
        com.ketvoice.app.ui.onboarding.PlaylistImportEntry.open = { opened++ }
        com.ketvoice.app.ui.onboarding.PlaylistImportEntry.resolve()!!.invoke()
        assertEquals(1, opened)
    }

    private fun hasAppUiImport() = runCatching { AppUi::class.java.getMethod("openPlaylistImport") }.isSuccess

    @Test fun theBackGestureGoesToThePreviousPage() {
        show()
        next()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Bienvenue sur Ketvoice")
    }
}
