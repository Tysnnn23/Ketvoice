package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.EmptyStateLook
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.RowHighlight
import com.ketvoice.app.ui.common.TrackRow
import com.ketvoice.app.ui.sub.CatalogSkeletonList
import com.ketvoice.app.ui.sub.SkeletonLook
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Interface polish (Ket 27): the iOS highlight of a pressed row of titles, the empty pages and the
 * placeholder rows of a loading list. Software renders on the host (not a phone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InterfacePolishHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun reset() { AppSettings.reduceAnimations.value = false }

    @Test fun theRowHighlightComesAtOnceAndLeavesSlowly() {
        assertEquals(RowHighlight.SHADE, RowHighlight.target(1), 0f)
        assertEquals(RowHighlight.SHADE, RowHighlight.target(2), 0f)
        assertEquals(0f, RowHighlight.target(0), 0f)
        val inSpec = RowHighlight.spec(pressed = true, reduced = false) as TweenSpec
        val outSpec = RowHighlight.spec(pressed = false, reduced = false) as TweenSpec
        assertTrue(inSpec.durationMillis < outSpec.durationMillis)
        assertTrue(RowHighlight.spec(true, reduced = true) is SnapSpec)
        assertTrue(RowHighlight.spec(false, reduced = true) is SnapSpec)
        assertEquals(RowHighlight(Color.Black), RowHighlight(Color.Black))
    }

    @Test fun aPressedRowOfTitlesDarkensUnderTheFinger() {
        val track = Track("qa:row", "Titre pressé", "Artiste", "Album")
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = false) {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                TrackRow(track, null, active = false, playing = false, onClick = {}, modifier = Modifier.testTag("row"))
            }
        } }
        compose.mainClock.advanceTimeBy(200)
        val bounds = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        // A point of the row with nothing drawn on it: right of the title, left of the menu button.
        val probe = Offset(bounds.right - 160f, bounds.top + 6f)
        val before = pixel(probe)
        compose.onNodeWithTag("row").performTouchInput { down(Offset(width / 2f, height / 2f)) }
        compose.mainClock.advanceTimeBy(150)
        val pressed = pixel(probe)
        image("row-pressed")
        compose.onNodeWithTag("row").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        val after = pixel(probe)
        assertTrue("darker under the finger (${Integer.toHexString(before)} → ${Integer.toHexString(pressed)})",
            luminance(pressed) < luminance(before) - 8)
        assertTrue("back once let go", kotlin.math.abs(luminance(before) - luminance(after)) <= 2)
    }

    @Test fun emptyPagesAndPlaceholderRows() {
        assertTrue(EmptyStateLook.discDp(quest = true) > EmptyStateLook.discDp(quest = false))
        assertEquals(0f, EmptyStateLook.riseDp(reduced = true), 0f)
        for (i in 0..20) assertTrue(SkeletonLook.titleShare(i) in .5f..<.85f)
        assertNotEquals(SkeletonLook.titleShare(0), SkeletonLook.titleShare(1))
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                EmptyState("Aucun téléchargement", "Pour écouter hors ligne, ouvrez le menu d'un titre puis choisissez Télécharger.",
                    "Rechercher des titres", icon = KetIcons.Download, onAction = {})
                CatalogSkeletonList(rows = 5)
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("empty-state").assertIsDisplayed()
        // The title is a heading, the action is there; the placeholders read as one "loading" element.
        compose.onNode(hasText(com.ketvoice.app.data.AppText.t("Aucun téléchargement")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Rechercher des titres")).assertExists()
        val skeleton = compose.onNodeWithTag("skeleton-list").fetchSemanticsNode()
        assertEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate, skeleton.config[SemanticsProperties.ProgressBarRangeInfo])
        assertTrue(skeleton.children.isEmpty())
        image("empty-and-loading")
    }

    @Test fun reducedMotionShowsTheEmptyPageAtOnce() {
        AppSettings.reduceAnimations.value = true
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = false) {
            EmptyState("Aucun résultat", "Essayez un autre titre ou artiste.", "Effacer", Modifier.fillMaxWidth(), icon = KetIcons.Search) {}
        } }
        compose.mainClock.advanceTimeBy(16)
        image("empty-reduced-first-frame")
        compose.onNodeWithTag("empty-state").assertIsDisplayed()
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        return bitmap
    }

    private fun pixel(at: Offset): Int {
        var color = 0
        compose.runOnIdle { val b = bitmap(); color = b.getPixel(at.x.toInt(), at.y.toInt()); b.recycle() }
        return color
    }

    private fun luminance(argb: Int): Int = ((argb shr 16 and 0xFF) * 3 + (argb shr 8 and 0xFF) * 6 + (argb and 0xFF)) / 10

    private fun image(name: String) {
        compose.runOnIdle {
            val bitmap = bitmap()
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/polish/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
