package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.smartlib.AlbumFacts
import com.ketvoice.app.smartlib.Genre
import com.ketvoice.app.smartlib.SmartFacts
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.stats.StatsScreen
import com.ketvoice.app.ui.stats.StatsWork
import com.ketvoice.app.ui.stats.statsInput
import com.ketvoice.app.util.StatsShareCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.LocalDate

/** « Statistiques » (8.0), rendered on the host (software Skia), not on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun <T> flow(f: kotlinx.coroutines.flow.StateFlow<T>) = ListeningSeam.ownOf(f)
    private val today = LocalDate.now()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        StatsWork.dispatcher = Dispatchers.Unconfined
        clear()
    }

    @After fun clear() {
        coil.Coil.reset()
        SmartFacts.resetForTests()
        flow(ListeningHistory.knownTracks).value = emptyList(); flow(ListeningHistory.playCounts).value = emptyMap()
        flow(ListeningHistory.months).value = emptyMap(); flow(ListeningHistory.days).value = emptyMap()
        flow(ListeningHistory.years).value = emptyMap(); flow(ListeningHistory.listenedMs).value = 0L
        ListeningSeam.publish()
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private val drawn = HashMap<String, Bitmap>()

    private fun cover(name: String, from: Int, to: Int): String {
        drawn["https://covers.example/$name.png"] = coverBitmap(from, to)
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val image = drawn[chain.request.data.toString()]
                if (image != null) coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(compose.activity.resources, image),
                    chain.request, coil.decode.DataSource.MEMORY)
                else coil.request.ErrorResult(null, chain.request, IllegalStateException("no fixture"))
            })
        }.build())
        return "https://covers.example/$name.png"
    }

    private fun coverBitmap(from: Int, to: Int) = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(0f, 0f, 300f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, from, to, Shader.TileMode.CLAMP) })
    }

    private fun save(name: String, bitmap: Bitmap) {
        File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
            parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun image(name: String) {
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

    private fun fixture(): List<Track> {
        val c = listOf(cover("c1", 0xFFE94F37.toInt(), 0xFF6B1F7A.toInt()), cover("c2", 0xFF2FB9A4.toInt(), 0xFF153B69.toInt()),
            cover("c3", 0xFFF5C24C.toInt(), 0xFFB5432F.toInt()), cover("c4", 0xFF6F7BF7.toInt(), 0xFF1C1F4A.toInt()))
        fun t(id: String, title: String, artist: String, album: String, cover: String, albumId: String) =
            Track("fixture:$id", title, artist, album, durationMs = 205_000, coverUrl = cover, albumId = albumId)
        val tracks = listOf(t("a", "Skyline", "Nova", "Horizons", c[0], "1"), t("b", "Nuit blanche", "Orion", "Nocturne", c[1], "2"),
            t("c", "Lumière", "Nova", "Horizons", c[0], "1"), t("d", "Marée", "Lyra", "Océans", c[2], "3"),
            t("e", "Échos", "Vega", "Signal", c[3], "4"), t("f", "Boréale", "Sol", "Nord", c[2], "5"))
        SmartFacts.putAlbumForTests("1", AlbumFacts(setOf(Genre.Pop), 2024)); SmartFacts.putAlbumForTests("2", AlbumFacts(setOf(Genre.Rap), 2023))
        SmartFacts.putAlbumForTests("3", AlbumFacts(setOf(Genre.Electro, Genre.Pop), 2025)); SmartFacts.putAlbumForTests("4", AlbumFacts(setOf(Genre.RnB), 2022))
        var days = emptyMap<String, DayStat>(); var months = emptyMap<String, MonthStat>()
        fun listen(date: LocalDate, minutes: Long, hour: Int) {
            days = ListeningWeek.addTime(days, date, minutes * 60_000L, hour); months = ListeningMonths.addTime(months, date, hour, minutes * 60_000L)
        }
        fun play(date: LocalDate, track: Track, n: Int, newArtist: Boolean = false) = repeat(n) { i ->
            days = ListeningWeek.addPlay(days, date, track.id, newArtist && i == 0)
            months = ListeningMonths.addPlay(months, date, track, if (newArtist && i == 0) track.artist else null)
        }
        val minutes = listOf(42L, 18L, 65L, 30L, 54L, 12L, 80L, 25L, 47L, 38L, 70L, 22L, 15L, 58L)
        (0 until 14).forEach { back ->
            val d = today.minusDays(back.toLong())
            listen(d, minutes[back], listOf(8, 13, 18, 21, 22)[back % 5])
            play(d, tracks[back % tracks.size], 3 + back % 4, newArtist = back == 2)
            play(d, tracks[(back + 2) % tracks.size], 2)
        }
        val previousMonth = today.minusMonths(1)
        (1..previousMonth.lengthOfMonth()).forEach { listen(previousMonth.withDayOfMonth(it), 35, 20) }
        repeat(3) { months = ListeningMonths.addMix(months, today); days = ListeningWeek.addMix(days, today) }
        flow(ListeningHistory.days).value = ListeningWeek.trim(days, today)
        flow(ListeningHistory.months).value = months
        flow(ListeningHistory.years).value = mapOf(today.year.toString() to DayStat(months.values.sumOf { it.ms }))
        flow(ListeningHistory.listenedMs).value = months.values.sumOf { it.ms } + 3_000 * 60_000L
        flow(ListeningHistory.knownTracks).value = tracks
        flow(ListeningHistory.playCounts).value = tracks.associate { it.id to 12 }
        ListeningSeam.publish()
        return tracks
    }

    private fun show(dark: Boolean) = compose.setContent { KetvoiceTheme(useDark = dark) { StatsScreen() } }

    @Test fun weekThenMonthWithTheirSections() {
        val tracks = fixture()
        show(dark = true)
        compose.onNodeWithTag("stats-minutes").assertExists()
        compose.onNodeWithTag("stats-delta").assertExists()
        // The week runs from Monday, so early in the week fewer artists have been played.
        val weekArtists = (0 until today.dayOfWeek.value).flatMap { back ->
            listOf(tracks[back % tracks.size], tracks[(back + 2) % tracks.size]).map { it.artist }
        }.toSet().size
        compose.onAllNodesWithTag("stats-artist").assertCountEquals(minOf(5, weekArtists))
        image("stats-week-dark")
        compose.onNodeWithTag("stats-list").performScrollToIndex(5)
        image("stats-week-dark-lists")
        compose.onNodeWithTag("stats-list").performScrollToIndex(0)
        compose.onAllNodesWithTag("stats-period")[1].performClick()
        image("stats-month-dark")
        // Live: five more seconds of listening reach the page without leaving it.
        fun shown() = compose.onNodeWithTag("stats-minutes").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        val before = shown().toInt()
        compose.runOnIdle { ListeningHistory.addListenedMs(10 * 60_000L) }
        compose.waitForIdle()
        assertEquals(before + 10, shown().toInt())
        compose.onNodeWithTag("stats-share").performClick()
        compose.onNodeWithTag("stats-share-sheet").assertExists()
        image("stats-share-sheet-dark")
    }

    @Test fun lightWeekAndMonthAndHours() {
        fixture()
        show(dark = false)
        image("stats-week-light")
        compose.onAllNodesWithTag("stats-period")[1].performClick()
        image("stats-month-light")
        compose.onNodeWithTag("stats-list").performScrollToIndex(6)
        compose.onNodeWithTag("stats-hours").assertExists()
        image("stats-month-light-hours")
    }

    @Test @Config(qualifiers = "w1000dp-h760dp-mdpi") fun tabletWeek() {
        fixture()
        show(dark = true)
        image("stats-tablet-week-dark")
    }

    @Test fun emptyHistoryAndAnEmptyPeriod() {
        show(dark = false)
        compose.onNodeWithText("Pas encore de statistiques").assertExists()
        image("stats-empty-light")
    }

    @Test fun silentWeekKeepsTheOtherPeriods() {
        val previous = today.minusMonths(2)
        flow(ListeningHistory.months).value = ListeningMonths.addTime(emptyMap(), previous, 20, 90 * 60_000L)
        flow(ListeningHistory.listenedMs).value = 90 * 60_000L
        ListeningSeam.publish()
        show(dark = true)
        compose.onNodeWithTag("stats-empty").assertExists()
        image("stats-week-silent-dark")
    }

    @Test fun shareCards() {
        val tracks = fixture()
        val input = statsInput(today)
        val week = LiveStats.compute(LiveStats.Period.Week, input)
        val covers = listOf(coverBitmap(0xFFE94F37.toInt(), 0xFF6B1F7A.toInt()), coverBitmap(0xFF2FB9A4.toInt(), 0xFF153B69.toInt()),
            coverBitmap(0xFFF5C24C.toInt(), 0xFFB5432F.toInt()), coverBitmap(0xFF6F7BF7.toInt(), 0xFF1C1F4A.toInt()), null)
        val top = StatsShareCard.top5("Mon top 5", "Cette semaine", week.tracks.take(5).mapIndexed { i, r ->
            StatsShareCard.Row(r.track.title, r.track.artist, covers[i]) }, 0xFFB0413E.toInt())
        assertEquals(1080, top.width); assertEquals(1920, top.height)
        save("stats-card-top5", top)
        val minutes = StatsShareCard.minutes("Cette semaine", com.ketvoice.app.ui.stats.minutesText(week.ms), "minutes d'écoute",
            com.ketvoice.app.ui.stats.deltaText(week), week.bars.map { it.ms }.toLongArray(), week.bars.indexOfFirst { it.now },
            "Artiste n°1 : ${week.artists.first().name}", covers[0], 0xFFB0413E.toInt())
        save("stats-card-minutes", minutes)
        assertTrue(tracks.isNotEmpty())
    }
}
