package com.ketvoice.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.chrome.FloatingChrome
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 8.0 « Barre d'onglets qui se transforme » on the host: the bar expanded, folded into one capsule, with the
 * search field stretched out, and the mini-player half-way while it detaches from the folded row. The shader
 * cannot run here, so the renders show the blurred fallback (flat on the host); the real refraction is a
 * device-only result. The folding is also counted with the Compose tracer, as a page scroll drives it.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChromeMorphHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val collapsed = mutableStateOf(false)
    private val searching = mutableStateOf(false)
    /** Off: no live backdrop, the static glass (a host bitmap cannot draw a live backdrop between two frames). */
    private val live = mutableStateOf(true)

    private object Runs : CompositionTracer {
        @Volatile var on = false
        val counts = ConcurrentHashMap<String, Int>()
        override fun isTraceInProgress() = on
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            counts.merge(info.substringBefore(' ').substringAfterLast('.'), 1, Int::plus)
        }
        override fun traceEventEnd() {}
    }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.liquidGlass.value = true
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        AppUi.selectTab(AppUi.HOME)
        Composer.setTracer(Runs)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState(
            queue = listOf(Track("qa:morph", "Titre en cours", "Artiste", "Album", durationMs = 200_000)), index = 0)
    }

    @After fun reset() {
        Runs.on = false; Runs.counts.clear()
        com.ketvoice.app.glass.GlassCompat.api = android.os.Build.VERSION.SDK_INT
        AppUi.closeOverlays()
        AppUi.selectTab(AppUi.HOME)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState()
    }

    private fun content(dark: Boolean = false, fallback: Boolean = true) {
        // The shader cannot run on the host: the renders draw the Android 12 blurred fallback (separate panels).
        if (fallback) com.ketvoice.app.glass.GlassCompat.api = 32
        compose.setContent { KetvoiceTheme(useDark = dark) {
            val backdrop = rememberGlassBackdrop(Color.White)
            Box(Modifier.fillMaxSize()) {
                // Bands of colour behind the bar, so that what the glass does to its backdrop can be seen.
                val bands = @androidx.compose.runtime.Composable {
                    Column(Modifier.fillMaxSize()) {
                        listOf(0xFFE8D5C4, 0xFF2F6F8F, 0xFFF2C14E, 0xFF8E3B46, 0xFF3C3C3C, 0xFFB8D8BA).forEach {
                            Box(Modifier.fillMaxWidth().weight(1f).background(Color(it)))
                        }
                    }
                }
                if (live.value) BackdropCapture(backdrop, Modifier.fillMaxSize()) { bands() } else bands()
                CompositionLocalProvider(LocalBackdropLayer provides backdrop.takeIf { live.value }) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        FloatingChrome(collapsed.value, {}, searching = searching.value)
                    }
                }
            }
        } }
    }

    private fun render(name: String) {
        // A software draw can meet a layer the host has not recorded yet; one more frame records it.
        var bitmap: Bitmap? = null
        for (attempt in 0 until 4) {
            bitmap = runCatching { compose.runOnUiThread<Bitmap> {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            } }.getOrNull()
            if (bitmap != null) break
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        }
        checkNotNull(bitmap) { "could not draw $name" }
        run {
            // Only the lower part, where the chrome lives.
            val top = (bitmap.height * .78f).toInt()
            val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/glass80-$name.png")
            file.parentFile?.mkdirs(); file.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // Android 12: the backdrop library's own highlight needs a GPU canvas on 13+, which a host bitmap is not.
    @Config(sdk = [32])
    @Test fun barExpandedFoldedSearchingAndDetaching() {
        // A host bitmap cannot draw a live backdrop that changes between frames: the static glass, drawn as on a phone
        // without a live backdrop, shows the shapes; the blur is flat on the host anyway.
        live.value = false
        content()
        compose.waitForIdle()
        render("expanded")
        compose.onNodeWithTag("search-bubble").assertExists()
        // Folding: caught part-way, the tabs flow to the middle and melt into one capsule.
        compose.mainClock.autoAdvance = false
        collapsed.value = true
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(8) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        render("melting")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        render("collapsed")
        compose.onNodeWithTag("folded-tab-pill").assertExists()
        // Unfolding: caught part-way, the mini-player leaves the row and rises back above the bar.
        compose.mainClock.autoAdvance = false
        collapsed.value = false
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(9) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        render("detaching")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        render("detached")
        searching.value = true
        compose.waitForIdle()
        render("search")
        compose.onNodeWithTag("floating-search-field").assertExists()
    }

    @Config(sdk = [32])
    @Test fun darkFoldedBar() {
        collapsed.value = true
        live.value = false
        content(dark = true)
        compose.waitForIdle()
        render("collapsed-dark")
    }

    @Test fun foldingWhileScrollingStaysBounded() {
        // Counted on the Android 13+ path (the fused glass), as the 7.6 figure was.
        content(fallback = false)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        Runs.on = true; Runs.counts.clear()
        // Four folds and unfolds, as a page scrolled down and back up twice, each one played out frame by frame.
        repeat(4) {
            collapsed.value = !collapsed.value
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            repeat(44) { compose.mainClock.advanceTimeByFrame() }
        }
        compose.waitForIdle()
        Runs.on = false
        val runs = Runs.counts.toMap()
        val total = runs.values.sum()
        val out = File("build/glass80").apply { mkdirs() }.resolve("recompositions.txt")
        out.writeText("chrome folds x4, composable runs: $total\n")
        runs.entries.sortedByDescending { it.value }.take(12).forEach { out.appendText("  ${it.key}: ${it.value}\n") }
        println(out.readText())
        assertTrue("the folding bar composes a bounded number of times ($total)", total < LIMIT)
    }

    // 7.6 measured 28 634 runs for the same four folds (before the tabs melted and the width moved to layout).
    private companion object { const val LIMIT = 28_634 }
}
