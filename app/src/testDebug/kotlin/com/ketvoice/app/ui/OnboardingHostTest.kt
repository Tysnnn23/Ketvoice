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
import com.ketvoice.app.ui.onboarding.EnhanceKind
import com.ketvoice.app.ui.onboarding.EnhanceState
import com.ketvoice.app.ui.onboarding.Enhancement
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
 * The first-launch welcome (6.2) drawn on the host in software, not on a device: its pages on a phone, in a
 * tablet's landscape window (also Quest's window shape) and with large text, and what it does. 7.6 adds
 * « Améliorez votre expérience d'écoute » (nothing installed, downloading, all installed) and its skip warning;
 * the rows are given here, the real ones follow the models and the scan.
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
            // An alert has its own window: drawn over the page.
            val windows = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
            @Suppress("UNCHECKED_CAST")
            val views = ReflectionHelpers.getField<java.util.ArrayList<android.view.View>>(windows, "mViews")
            views.filter { it !== view && it.width > 0 }.forEach { window ->
                canvas.drawColor(0x33000000)
                canvas.save()
                canvas.translate((view.width - window.width) / 2f, (view.height - window.height) / 2f)
                window.draw(canvas)
                canvas.restore()
            }
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/onboarding/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private val nothing = listOf(
        Enhancement(EnhanceKind.VoiceQuick, EnhanceState.Available, 39_364_064L),
        Enhancement(EnhanceKind.VoiceHq, EnhanceState.Available, 33_429_300L),
        Enhancement(EnhanceKind.LocalMusic, EnhanceState.Available))
    private val downloading = listOf(
        Enhancement(EnhanceKind.VoiceQuick, EnhanceState.Working(.42f), 39_364_064L),
        Enhancement(EnhanceKind.VoiceHq, EnhanceState.Available, 33_429_300L),
        Enhancement(EnhanceKind.LocalMusic, EnhanceState.Working(null)))
    private val installed = listOf(
        Enhancement(EnhanceKind.VoiceQuick, EnhanceState.Done(), 39_364_064L),
        Enhancement(EnhanceKind.VoiceHq, EnhanceState.Done(), 33_429_300L),
        Enhancement(EnhanceKind.Rhythm, EnhanceState.Done(), 2_100_000L),
        Enhancement(EnhanceKind.LocalMusic, EnhanceState.Done(128)))

    private fun show(dark: Boolean = false, importAvailable: Boolean = false, items: List<Enhancement> = nothing, startPage: Int = 0) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            Onboarding(onFinish = { finished = it }, importAvailable = importAvailable, startPage = startPage,
                enhancements = { items })
        } }
        compose.waitForIdle()
    }

    /** From the last page with nothing chosen: the warning, then « Continuer sans ». */
    private fun leaveWithoutEnhancements() {
        next()
        compose.onNodeWithTag("enhance-skip-continue").performClick()
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
        next()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Améliorez votre expérience d'écoute")
        compose.onNodeWithTag("enhance-list").assertExists()
        image("$prefix-4-enhance")
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
        leaveWithoutEnhancements()
        compose.runOnIdle { assertEquals(OnboardingChoices(ketmix = true, voice = false, importPlaylists = false), finished) }
    }

    @Test fun skippingGoesToTheEnhancementsThenEnds() {
        show()
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Améliorez votre expérience d'écoute")
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("enhance-skip-alert").assertExists()
        compose.onNodeWithTag("enhance-skip-continue").performClick()
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
        leaveWithoutEnhancements()
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

    // ------------------------------------------------------------------ 7.6: « Améliorez votre expérience d'écoute »

    @Test fun enhancementsNothingInstalled() {
        show(startPage = 3)
        compose.onNodeWithTag("enhance-action-VoiceQuick").assertIsDisplayed()
        compose.onNodeWithText("Séparation des voix").assertIsDisplayed()
        image("phone-4-enhance-nothing")
    }

    @Test fun enhancementsDownloadingShowTheirProgress() {
        show(items = downloading, startPage = 3)
        compose.onNodeWithTag("enhance-progress-VoiceQuick").assertExists()
        compose.onNodeWithText("42 %").assertExists()
        image("phone-4-enhance-downloading")
        // Something is on its way: leaving does not warn.
        next()
        compose.onNodeWithTag("enhance-skip-alert").assertDoesNotExist()
        compose.runOnIdle { assertNotNull(finished) }
    }

    @Test fun enhancementsAllInstalled() {
        show(dark = true, items = installed, startPage = 3)
        compose.onNodeWithText("128 titres").assertExists()
        image("phone-4-enhance-installed-dark")
        next()
        compose.runOnIdle { assertNotNull(finished) }
    }

    @Test fun leavingWithNothingAsksFirstAndBackStays() {
        show(startPage = 3)
        next()
        compose.onNodeWithTag("enhance-skip-alert").assertExists()
        compose.onNodeWithText("Continuer sans les améliorations ?").assertExists()
        image("phone-4-enhance-alert")
        compose.onNodeWithTag("enhance-skip-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("enhance-skip-alert").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Améliorez votre expérience d'écoute")
        compose.runOnIdle { assertNull(finished) }
    }

    @Test fun theVoiceSwitchCountsAsAChoice() {
        show()
        next(); next()
        compose.onNodeWithTag("onboarding-voice").performClick()
        next(); next()
        compose.onNodeWithTag("enhance-skip-alert").assertDoesNotExist()
        compose.runOnIdle { assertTrue(finished!!.voice) }
    }

    @Test fun liteAndModestPhonesAreOfferedLess() {
        val full = com.ketvoice.app.ui.onboarding.Enhancements.kinds(lite = false, rhythmBundled = true, localSupported = true)
        assertEquals(listOf(EnhanceKind.VoiceQuick, EnhanceKind.VoiceHq, EnhanceKind.LocalMusic), full)
        val lite = com.ketvoice.app.ui.onboarding.Enhancements.kinds(lite = true, rhythmBundled = false, localSupported = false)
        assertEquals(listOf(EnhanceKind.VoiceQuick, EnhanceKind.Rhythm), lite)
        assertFalse(com.ketvoice.app.ui.onboarding.Enhancements.chosen(nothing))
        assertTrue(com.ketvoice.app.ui.onboarding.Enhancements.chosen(downloading))
    }

    @Test fun settingsOpensTheSameRows() {
        compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-enhance").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("enhance-sheet").assertExists()
        compose.onNodeWithTag("enhance-action-VoiceQuick").assertExists()
        image("settings-enhance-sheet")
    }
}
