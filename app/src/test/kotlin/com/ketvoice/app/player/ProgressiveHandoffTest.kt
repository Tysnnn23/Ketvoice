package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** 7.2: the progressive handoff really switches, and the first sound does not share the network. */
class ProgressiveHandoffTest {
    private data class S(val name: String, val rank: Int)

    @Test fun theBestStreamFoundWaitsAndLowerOnesAreIgnored() {
        val better = QualityHandoff.BetterStream<S>({ it.rank }, S("yt", 160))
        assertFalse(better.offer(S("same", 160)))
        assertTrue(better.offer(S("aac", 256)))
        assertTrue(better.offer(S("flac", 10_000)))
        // A stream answering later but worse than the one waiting never replaces it.
        assertFalse(better.offer(S("aac2", 256)))
        assertEquals("flac", better.next()?.name)
        better.heard(S("flac", 10_000))
        assertNull(better.next())
        assertFalse(better.offer(S("aac", 256)))
    }

    @Test fun aHandoffThatKeepsFailingIsGivenUpAfterThreeTries() {
        val better = QualityHandoff.BetterStream<S>({ it.rank }, S("yt", 160))
        val flac = S("flac", 10_000)
        better.offer(flac)
        assertTrue(better.failed(flac))
        assertTrue(better.failed(flac))
        assertFalse(better.failed(flac))
        assertNull(better.next())
    }

    @Test fun aHeardStreamClearsOnlyWhatIsNotBetter() {
        val better = QualityHandoff.BetterStream<S>({ it.rank }, S("yt", 160))
        better.offer(S("aac", 256)); better.offer(S("flac", 10_000))
        better.heard(S("aac", 256))
        assertEquals("flac", better.next()?.name)
    }

    @Test fun stepsCloseLargeGapsBySeekingAndSmallOnesBySpeed() {
        assertEquals(QualityHandoff.Step.Done, QualityHandoff.step(4.0))
        assertEquals(QualityHandoff.Step.Seek(-900), QualityHandoff.step(-900.0))
        val ahead = QualityHandoff.step(120.0) as QualityHandoff.Step.Nudge
        assertTrue(ahead.factor > 1f)
        // factor - 1 times the time played gives back the gap.
        assertEquals(120.0, (ahead.factor - 1.0) * ahead.forMs, 2.0)
        val behind = QualityHandoff.step(-15.0) as QualityHandoff.Step.Nudge
        assertTrue(behind.factor < 1f)
        assertTrue(behind.forMs in 500..1_000)
        assertTrue(QualityHandoff.acceptable(-18.0))
        assertFalse(QualityHandoff.acceptable(30.0))
        assertEquals(96L, QualityHandoff.crossfadeMs(3.0))
        assertEquals(240L, QualityHandoff.crossfadeMs(15.0))
    }

    @Test fun skewIsTheMedianOfNoisyReadings() {
        assertEquals(40.0, QualityHandoff.skew(listOf(0L to 40L, 10L to 49L, 20L to 61L, 30L to 300L, 40L to 70L)), 0.1)
    }

    /**
     * Two decks: [skew] = next position − old position (ms). A seek of the muted deck lands where asked but it
     * then waits [seekLatency] while the heard one goes on; a speed change gains a little more or less than asked.
     */
    private class Decks(var skew: Double, val rnd: Random) {
        var elapsedMs = 0.0
        fun read(): Double = skew + rnd.nextDouble(-3.0, 3.0)
        fun seek(byMs: Long) { val latency = rnd.nextDouble(30.0, 250.0); skew += byMs - latency; elapsedMs += latency + 120 }
        fun nudge(n: QualityHandoff.Step.Nudge) {
            skew += (n.factor - 1.0) * n.forMs * rnd.nextDouble(.9, 1.1)
            elapsedMs += n.forMs + QualityHandoff.SETTLE_MS
        }
    }

    /** The 7.2 loop of PlaybackService.bringTogether, on simulated decks. */
    private fun align(lagMs: Double, decks: Decks): Double {
        repeat(QualityHandoff.MAX_STEPS) {
            when (val step = QualityHandoff.step(lagMs - decks.read())) {
                QualityHandoff.Step.Done -> return lagMs - decks.skew
                is QualityHandoff.Step.Seek -> decks.seek(step.byMs)
                is QualityHandoff.Step.Nudge -> decks.nudge(step)
            }
        }
        return lagMs - decks.skew
    }

    @Test fun beforeTheFixRenditionsOfTwoEncodersNeverSwitched() {
        // 6.0.0 Beta 4 loop: measure the lag by sound (in each title's own time, so always the same), seek by it,
        // measure again, three times; any lag above 6 ms made it give up.
        var switched = 0
        for (lag in listOf(12.0, 25.0, 48.0, -80.0, 210.0)) {
            val decks = Decks(0.0, Random(1))
            var heardAligned = false
            for (attempt in 0..2) {
                val measured = lag // the sound measure: the same however the decks moved
                if (abs(measured) <= QualityHandoff.HEARD_ALIGNED_MS) { heardAligned = true; break }
                decks.seek(Math.round(measured))
            }
            if (heardAligned) switched++
        }
        assertEquals(0, switched)
        // The same lags with the 7.2 loop all switch.
        assertTrue(listOf(12.0, 25.0, 48.0, -80.0, 210.0).all { abs(align(it, Decks(-100.0, Random(2)))) <= QualityHandoff.ACCEPT_MS })
    }

    @Test fun nowTheDecksAreBroughtTogetherWithinTheCrossfadeTolerance() {
        val rnd = Random(72)
        var worstMs = 0.0
        var longestMs = 0.0
        var heardAsOne = 0
        val runs = 400
        repeat(runs) {
            val lag = rnd.nextDouble(-450.0, 450.0)
            // After the first seek to the heard position the better deck is behind by its own start time.
            val decks = Decks(-rnd.nextDouble(20.0, 300.0), rnd)
            val error = align(lag, decks)
            worstMs = maxOf(worstMs, abs(error))
            longestMs = maxOf(longestMs, decks.elapsedMs)
            if (abs(error) <= QualityHandoff.HEARD_ALIGNED_MS + 3) heardAsOne++
        }
        println("handoff alignment: worst ${"%.1f".format(worstMs)} ms, longest ${"%.1f".format(longestMs / 1000)} s, " +
            "heard as one $heardAsOne/$runs")
        assertTrue("worst error $worstMs ms", worstMs <= QualityHandoff.ACCEPT_MS)
        assertTrue(heardAsOne > runs * 9 / 10)
        // Within the service's 45 s budget with preparing (~3 s) and one sound measure (~5 s).
        assertTrue("longest ${longestMs} ms", longestMs < 30_000)
    }

    @Test fun aCachedTitleLooksForBetterOnlyOnAFreeNetwork() {
        assertTrue(QualityHandoff.keepLookingFromCache(true, online = true, metered = false, lossless = true, cachedRank = 256))
        assertFalse(QualityHandoff.keepLookingFromCache(true, online = true, metered = false, lossless = true, cachedRank = 10_000))
        assertFalse(QualityHandoff.keepLookingFromCache(true, online = true, metered = true, lossless = true, cachedRank = 128))
        assertFalse(QualityHandoff.keepLookingFromCache(true, online = false, metered = false, lossless = false, cachedRank = 128))
        assertFalse(QualityHandoff.keepLookingFromCache(false, online = true, metered = false, lossless = false, cachedRank = 128))
        assertTrue(QualityHandoff.keepLookingFromCache(true, online = true, metered = false, lossless = false, cachedRank = 160))
        assertFalse(QualityHandoff.keepLookingFromCache(true, online = true, metered = false, lossless = false, cachedRank = 256))
    }

    @Test fun onlySmallAnswersAndFilesOnTheDeviceGoBeforeTheFirstSound() {
        assertTrue(StartupOrder.mayLoadBeforeSound(StartupOrder.Load.Lyrics, onDevice = false))
        assertTrue(StartupOrder.mayLoadBeforeSound(StartupOrder.Load.Artwork, onDevice = false))
        assertTrue(StartupOrder.mayLoadBeforeSound(StartupOrder.Load.Canvas, onDevice = true))
        assertFalse(StartupOrder.mayLoadBeforeSound(StartupOrder.Load.Canvas, onDevice = false))
        assertFalse(StartupOrder.mayLoadBeforeSound(StartupOrder.Load.WholeFile, onDevice = false))
        assertFalse(StartupOrder.mayLoadBeforeSound(StartupOrder.Load.NextTitles, onDevice = false))
    }

    /**
     * Time for the first sound's bytes on a link shared fairly by the downloads running at once (a model, not a
     * device measure): the player needs [firstBytes]; the others are large files that last longer than that.
     */
    private fun firstBytesMs(linkBytesPerS: Double, firstBytes: Double, concurrent: Int): Double =
        firstBytes / (linkBytesPerS / (1 + concurrent)) * 1000

    @Test fun theFirstSoundNoLongerSharesTheLinkWithTheCacheCopyAndTheCanvas() {
        // FLAC: header + half a second ≈ 150 KB. Before 7.2 the whole-file cache copy of the same file started with
        // the first byte and a missing Canvas video downloaded beside it: three downloads at once.
        val first = 150_000.0
        for ((name, link) in listOf("4G 2 Mb/s" to 250_000.0, "Wi-Fi 20 Mb/s" to 2_500_000.0)) {
            val before = firstBytesMs(link, first, concurrent = 2)
            val after = firstBytesMs(link, first, concurrent = 0)
            println("first sound bytes on $name: before ${before.toInt()} ms, after ${after.toInt()} ms")
            assertEquals(3.0, before / after, 1e-9)
        }
        assertFalse(StartupOrder.mayLoadBeforeSound(StartupOrder.Load.WholeFile, false) ||
            StartupOrder.mayLoadBeforeSound(StartupOrder.Load.Canvas, false))
    }
}
