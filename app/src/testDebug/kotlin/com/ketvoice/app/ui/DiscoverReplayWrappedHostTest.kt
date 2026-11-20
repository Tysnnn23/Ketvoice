package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.sub.ReplayScreen
import com.ketvoice.app.ui.sub.WrappedCard
import com.ketvoice.app.ui.sub.WrappedPage
import com.ketvoice.app.ui.sub.WrappedPages
import com.ketvoice.app.ui.sub.WrappedStoryContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.LocalDate

/** Monthly Replay and Wrapped pages, rendered on the host (software Skia), not on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverReplayWrappedHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    private val counts get() = ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>
    private val months get() = ListeningHistory.months as MutableStateFlow<Map<String, MonthStat>>
    private val today = LocalDate.now()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        clear()
    }

    @After fun clear() {
        coil.Coil.reset()
        known.value = emptyList(); counts.value = emptyMap(); months.value = emptyMap()
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME); AppUi.catalogRequest.value = null
    }

    private val drawn = HashMap<String, Bitmap>()

    /** A cover drawn on the host: a gradient of two colours, served by a stand-in image loader (no network, no file). */
    private fun cover(name: String, from: Int, to: Int): String {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(0f, 0f, 300f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, from, to, Shader.TileMode.CLAMP) })
        val url = "https://covers.example/$name.png"
        drawn[url] = bitmap
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val image = drawn[chain.request.data.toString()]
                if (image != null) coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(compose.activity.resources, image),
                    chain.request, coil.decode.DataSource.MEMORY)
                else coil.request.ErrorResult(null, chain.request, IllegalStateException("no fixture"))
            })
        }.build())
        return url
    }

    private fun covers() = listOf(cover("c1", 0xFFE94F37.toInt(), 0xFF6B1F7A.toInt()), cover("c2", 0xFF2FB9A4.toInt(), 0xFF153B69.toInt()),
        cover("c3", 0xFFF5C24C.toInt(), 0xFFB5432F.toInt()), cover("c4", 0xFF6F7BF7.toInt(), 0xFF1C1F4A.toInt()))

    private fun save(name: String, bitmap: Bitmap) {
        File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
            parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun image(name: String) {
        // Coil answers on its own threads: give it a moment, then draw the window.
        repeat(4) { Thread.sleep(100); compose.mainClock.advanceTimeBy(200); compose.waitForIdle() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            save(name, bitmap)
            bitmap.recycle()
        }
    }

    private fun track(id: String, title: String, artist: String, album: String, cover: String?) =
        Track("fixture:$id", title, artist, album, durationMs = 200_000, coverUrl = cover)

    private fun fixtureMonths(): List<Track> {
        val c = covers()
        val tracks = listOf(
            track("a", "Skyline", "Nova", "Horizons", c[0]), track("b", "Nuit blanche", "Orion", "Nocturne", c[1]),
            track("c", "Lumière", "Nova", "Horizons", c[0]), track("d", "Marée", "Lyra", "Océans", c[2]),
            track("e", "Échos", "Vega", "Signal", c[3]))
        val now = today
        val before = today.minusMonths(1)
        var m = emptyMap<String, MonthStat>()
        fun play(date: LocalDate, t: Track, n: Int, newArtist: Boolean = false) =
            repeat(n) { i -> m = ListeningMonths.addPlay(m, date, t, if (newArtist && i == 0) t.artist else null) }
        play(now, tracks[0], 9); play(now, tracks[1], 7, newArtist = true); play(now, tracks[2], 5); play(now, tracks[3], 3, newArtist = true)
        play(now, tracks[4], 2, newArtist = true)
        m = ListeningMonths.addTime(m, now, 21, 95 * 60_000L); m = ListeningMonths.addTime(m, now, 8, 40 * 60_000L)
        m = ListeningMonths.addTime(m, now, 13, 22 * 60_000L)
        play(before, tracks[3], 6); play(before, tracks[0], 2)
        m = ListeningMonths.addTime(m, before, 18, 61 * 60_000L)
        months.value = m
        known.value = tracks
        counts.value = tracks.associate { it.id to 4 }
        return tracks
    }

    @Test fun replayShowsTheMonthUnderWayThenEarlierMonthsAndAllTime() {
        fixtureMonths()
        compose.setContent { KetvoiceTheme(useDark = true) { ReplayScreen() } }
        compose.onNodeWithTag("replay-month").assertIsDisplayed()
        compose.onNodeWithTag("replay-in-progress").assertIsDisplayed()
        compose.onNodeWithText("Ce mois-ci").assertIsDisplayed()
        compose.onNodeWithTag("replay-month-time").assertTextEquals("2 h 37 min")
        compose.onNodeWithTag("replay-month-wrapped").assertIsDisplayed()
        image("replay-month-dark")
        compose.onNodeWithTag("replay-list").performScrollToNode(hasTestTag("replay-month-save"))
        image("replay-month-dark-playlist")
        compose.onAllNodesWithTag("replay-month-track").assertCountEquals(5)
        // The month before: no longer in progress, its own titles.
        val previous = com.ketvoice.app.ui.sub.WrappedPeriod.monthName(ListeningMonths.key(today.minusMonths(1)))
        compose.onNodeWithTag("replay-list").performScrollToIndex(0)
        compose.onNodeWithText(previous).performClick()
        compose.onNodeWithTag("replay-in-progress").assertDoesNotExist()
        compose.onNodeWithTag("replay-month-time").assertTextEquals("1 h 01 min")
        compose.onAllNodesWithTag("replay-month-track").assertCountEquals(2)
        compose.onNodeWithText("Depuis le début").performScrollTo().performClick()
        compose.onNodeWithTag("replay-time").assertIsDisplayed()
    }

    private fun recap() = Wrapped.month(ListeningMonths.key(today), months.value, known.value).copy(
        mood = Wrapped.Mood(118, "Entraînant", 4), ketmixMixes = 37)

    @Test fun wrappedPagesForAMonth() {
        fixtureMonths()
        val recap = recap()
        val pages = WrappedPages.of(recap)
        assertEquals(listOf(WrappedPage.Intro, WrappedPage.Minutes, WrappedPage.Clock, WrappedPage.Artist, WrappedPage.Tracks,
            WrappedPage.Albums, WrappedPage.Mood, WrappedPage.Discoveries, WrappedPage.Ketmix, WrappedPage.Summary), pages)
        val page = mutableListOf(pages.indexOf(WrappedPage.Clock))
        val shown = androidx.compose.runtime.mutableIntStateOf(page.first())
        compose.setContent { KetvoiceTheme(useDark = true) {
            androidx.compose.runtime.key(shown.intValue) {
                WrappedStoryContent(recap, onClose = {}, startPage = shown.intValue, autoAdvance = false)
            }
        } }
        compose.onNodeWithTag("wrapped-clock").assertIsDisplayed()
        compose.onNodeWithText("Vous écoutez surtout le soir").assertIsDisplayed()
        compose.onNodeWithTag("wrapped-share-page").assertIsDisplayed()
        image("wrapped-month-clock")
        compose.runOnIdle { shown.intValue = pages.indexOf(WrappedPage.Discoveries) }
        compose.onNodeWithText("3 nouveaux artistes").assertIsDisplayed()
        compose.onNodeWithText("Orion").assertIsDisplayed()
        image("wrapped-month-discoveries")
        compose.runOnIdle { shown.intValue = pages.indexOf(WrappedPage.Mood) }
        compose.onNodeWithTag("wrapped-mood").assertIsDisplayed()
        image("wrapped-month-mood")
    }

    @Test fun everyPageLeavesAsAStoryPicture() {
        fixtureMonths()
        val recap = recap()
        val context = compose.activity
        for (page in listOf(WrappedPage.Clock, WrappedPage.Tracks, WrappedPage.Ketmix)) {
            val pictures = if (page == WrappedPage.Tracks) recap.tracks.map { drawn[it.first.coverUrl] } else emptyList()
            val bitmap = WrappedCard.draw(context, recap, page, cover = drawn[recap.topTrack?.coverUrl], pictures = pictures)
            assertEquals(WrappedCard.WIDTH, bitmap.width); assertEquals(WrappedCard.HEIGHT, bitmap.height)
            assertEquals(9f / 16f, bitmap.width.toFloat() / bitmap.height, .001f)
            save("wrapped-share-${page.name.lowercase()}", bitmap)
        }
        // The share file itself (no cover here: the image loader would wait for the main thread this test holds).
        val bare = recap.copy(tracks = recap.tracks.map { (t, n) -> t.copy(coverUrl = null) to n })
        val file = runBlocking { WrappedCard.render(context, bare, WrappedPage.Discoveries) }
        assertTrue(file.length() > 10_000)
        assertTrue(file.name.startsWith("wrapped-discoveries"))
    }
}
