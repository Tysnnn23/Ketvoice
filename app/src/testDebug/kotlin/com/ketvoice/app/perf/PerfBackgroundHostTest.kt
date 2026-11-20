package com.ketvoice.app.perf

import com.ketvoice.app.data.AppText
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.player.PositionTicks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 7.6: the position clock's wake-ups while paused, and the translations read before the first screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PerfBackgroundHostTest {
    private fun note(line: String) {
        File("build/perf76").apply { mkdirs() }.resolve("background.txt").appendText("$line\n")
        println("perf76 $line")
    }

    /** The loop of 7.5, kept here only to count its wake-ups beside the new one. */
    private suspend fun oldLoop(playing: () -> Boolean, inFront: Boolean, tick: () -> Unit) {
        while (true) { if (playing()) tick(); delay(if (inFront) 200 else 1_000) }
    }

    private fun wakeUps(state: PlayerUiState, inFront: Boolean, old: Boolean): Pair<Int, Int> {
        var wakes = 0; var ticks = 0
        runTest {
            val flow = MutableStateFlow(state)
            val playing = { wakes++; flow.value.isPlaying }
            val job = launch {
                if (old) oldLoop(playing, inFront) { ticks++ } else PositionTicks.run(flow, playing, { inFront }) { ticks++ }
            }
            advanceTimeBy(60_000)
            job.cancel()
        }
        return wakes to ticks
    }

    @Test fun aPausedPlayerNoLongerWakesFiveTimesASecond() {
        val paused = PlayerUiState()
        val playing = PlayerUiState(isPlaying = true, playWhenReady = true)
        for (front in listOf(true, false)) {
            val (before, _) = wakeUps(paused, front, old = true)
            val (after, _) = wakeUps(paused, front, old = false)
            note("paused, app ${if (front) "shown" else "behind"}: wake-ups per minute $before -> $after")
            assertTrue("paused wake-ups ($after)", after <= 7)
            // While a title plays, the ticks are the same as before.
            assertEquals(wakeUps(playing, front, old = true).second, wakeUps(playing, front, old = false).second)
        }
    }

    @Test fun theLanguageIsReadBeforeTheFirstScreenAsksForIt() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        AppText.clearForTests(); AppText.init(app)
        val language = com.ketvoice.app.data.AppSettings.language
        val saved = language.value
        try {
            language.value = com.ketvoice.app.data.AppLanguage.entries.first { it.code == "en" }.label
            val cold = timed { AppText.t("Accueil") }
            AppText.clearForTests(); AppText.init(app)
            val warm = Thread { AppText.preload("en") }.apply { start() }
            warm.join()
            val after = timed { AppText.t("Accueil") }
            note("first translated text on the main thread: ${cold}ms cold -> ${after}ms after the warm-up")
            assertTrue("the warmed dictionary answers at once ($after ms)", after < cold)
        } finally { language.value = saved; AppText.clearForTests(); AppText.init(app) }
    }

    /** Scrolling past 60 covers and back: how many accents must be fetched, decoded and analysed again. */
    @Test fun coverAccentsSurviveAScrollBack() {
        fun misses(cached: (String) -> Boolean, store: (String) -> Unit): Int {
            val urls = (1..60).map { "https://perf.test/cover/$it.jpg" }
            urls.forEach(store)
            return urls.count { url -> (!cached(url)).also { if (it) store(url) } }
        }
        val old = object : LinkedHashMap<String, Int>(24, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 32
        }
        val pair = androidx.compose.ui.graphics.Color.Red to androidx.compose.ui.graphics.Color.Blue
        val before = misses({ old[it] != null }, { old[it] = 1 })
        val after = misses({ com.ketvoice.app.theme.CoverPalette.cached(it) != null }, { com.ketvoice.app.theme.CoverPalette.store(it, pair) })
        note("cover accents analysed again after scrolling back over 60 covers: $before -> $after")
        assertEquals(0, after)
        com.ketvoice.app.theme.CoverPalette.markColourless("https://perf.test/grey.jpg")
        assertTrue(com.ketvoice.app.theme.CoverPalette.isColourless("https://perf.test/grey.jpg"))
    }

    private fun timed(block: () -> Unit): Long { val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1_000_000 }
}
