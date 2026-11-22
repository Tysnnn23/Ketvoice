package com.ketvoice.app.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** 8.4 « Pont en boucle » ([LoopBridge], [TempoGlide], [GlideDriver]) on made-up analyses. */
class KetmixLoopBridgeTest {
    private val duration = 240_000L

    /** An ending at [bpm] with bars, sung until 200 s, 36 s of instrumental after it, ringing out at 239 s. */
    private fun outro(bpm: Float = 128f, confidence: Float = .9f, bar: Int = 0, key: Int = MusicalKey.UNREAD, fall: Long = 236_000) =
        SegmentAnalysis(195_000, 45_000, bpm, 195_000.0, confidence, bar, -14f, 195_000, 239_000, 195_000, fall,
            key = key, keyStrength = if (key >= 0) .8f else 0f)

    /** An intro at [bpm] with bars from 0.5 s. */
    private fun intro(bpm: Float = 108f, bar: Int = 0, key: Int = MusicalKey.UNREAD) =
        SegmentAnalysis(0, 40_000, bpm, 500.0, .9f, bar, -14f, 300, 40_000, 8_000, 40_000, key = key, keyStrength = if (key >= 0) .8f else 0f)

    private val lyricsOut = listOf(180_000L..190_000L, 192_000L..200_000L)
    private val maskOut = listOf(191_500L..200_300L)

    private fun outFacts(a: SegmentAnalysis = outro(), lyrics: List<LongRange> = lyricsOut, mask: List<LongRange>? = maskOut,
                         end: Long? = 200_300, exit: Long = -1) =
        MixFacts(duration, a, vocalEndMs = end, sungLines = LyricTimes.merge(lyrics, mask), voiceLines = mask, userExitMs = exit)

    private fun inFacts(b: SegmentAnalysis = intro(), from: Long? = 32_000, lines: List<LongRange> = listOf(32_000L..36_000L),
                        mask: List<LongRange>? = listOf(31_900L..36_100L)) =
        MixFacts(duration, b, vocalStartMs = from, sungLines = LyricTimes.merge(lines, mask), voiceLines = mask)

    private fun plan(out: MixFacts = outFacts(), incoming: MixFacts = inFacts(), context: MixContext = MixContext(),
                     settings: MixSettings = MixSettings(1)) = TransitionPlanner.plan(out, incoming, context, settings)

    // ----------------------------------------------------------------- the plan

    @Test fun tempiTooFarApartForABlendGetALoopBridge() {
        val p = plan()
        assertEquals(MixKind.Beatmatch, p.kind)
        assertNotNull(p.reason, p.glide)
        val glide = p.glide!!
        assertEquals("pont en boucle", MixWords.label(p))
        assertEquals("loop bridge", p.reason)
        assertEquals(128f / 108f, p.rate, 1e-5f)
        // The loop: four bars that nobody sings in, after the last sung part, leaving the whole bridge inside the title.
        assertEquals(223_125L, p.outMs)
        assertEquals(223_125_000L, p.loopFromUs)
        assertEquals(7_500_000L, p.loopUs)
        assertEquals(BarLoop(223_125_000L, 7_500_000L, 238_125_000L), p.barLoop())
        assertTrue(p.outMs >= 200_300)
        // Eight bars: 20 BPM at 2.5 BPM a bar.
        assertEquals(15_000L, p.overlapMs)
        assertTrue(p.outMs + p.overlapMs <= duration)
        // The incoming title enters on its first bar, four bars before anybody sings.
        assertEquals(500L, p.inMs)
        assertEquals(0, BlendFit.barIndex(intro(), p.inMs.toDouble()))
        // The glide spans the bridge, from the outgoing tempo to the incoming one: 128 → 108 BPM.
        assertEquals(223_125L, glide.fromMs); assertEquals(238_125L, glide.untilMs)
        assertEquals(1f, glide.fromSpeed, 0f); assertEquals(108f / 128f, glide.toSpeed, 1e-6f)
        assertEquals(2.5f, glide.bpmPerBar(128f, 1_875.0), 1e-3f)
        assertEquals("the incoming title ends on its own tempo", 1f, glide.toSpeed * p.rate, 1e-5f)
        // The bass changes hands half-way through the glide, on a bar; the loop leaves rising (high-pass).
        assertEquals(230_625L, p.swapMs)
        assertEquals(0, p.variant)
        assertEquals(-1L, p.sungUntilMs)
        assertFalse("no voice over an instrumental at this stretch", Mashup.fits(p.copy(voiceFromMs = 32_000)))
    }

    @Test fun theDecksFollowTheBridge() {
        val p = plan()
        val out = DeckAutomation.outgoing(p)
        val into = DeckAutomation.incoming(p)
        fun inAt(outMs: Long) = ((p.inMs + (outMs - p.outMs) * p.rate.toDouble()) * 1000).toLong()
        assertEquals("the outgoing bass plays until the swap", 0f, out.highPassHz.at((p.swapMs - 2_000) * 1000), 0f)
        val leaving = out.highPassHz.at((p.outMs + p.overlapMs - 200) * 1000)
        assertTrue("the loop leaves through a rising high-pass ($leaving Hz)", leaving > DeckAutomation.BASS_HZ * 2)
        assertTrue("the incoming title enters without its bass", into.highPassHz.at(inAt(p.outMs + 500)) >= DeckAutomation.BASS_HZ)
        assertEquals(DeckAutomation.BASS_HZ, into.highPassHz.at(inAt(p.swapMs) - 300_000), .5f)
        assertEquals(0f, into.highPassHz.at(inAt(p.outMs + p.overlapMs - 500)), 0f)
    }

    @Test fun withinTheTempoRangeNothingChanges() {
        val p = plan(incoming = inFacts(intro(124f)))
        assertTrue(p.mixes)
        assertNull("a plain blend in time", p.glide)
        assertNotEquals("pont en boucle", MixWords.label(p))
    }

    private fun assertNotEquals(a: Any, b: Any) = assertFalse("$a == $b", a == b)

    @Test fun theVoiceOfBothTitlesIsKnownOrThereIsNoBridge() {
        assertNotNull("lyrics and a separated voice", plan().glide)
        assertNotNull("a separated voice only", plan(outFacts(lyrics = emptyList()), inFacts(lines = emptyList())).glide)
        assertNotNull("lyrics only", plan(outFacts(mask = null), inFacts(mask = null)).glide)
        assertNull("nothing says where the outgoing title sings", plan(outFacts(lyrics = emptyList(), mask = null, end = null)).glide)
        assertNull("nothing says where the incoming title sings", plan(incoming = inFacts(from = null, lines = emptyList(), mask = null)).glide)
        assertNotNull("a separation that hears nobody is an instrumental", plan(outFacts(lyrics = emptyList(), mask = emptyList(), end = 195_000)).glide)
    }

    @Test fun aLoopHoldsThreePercentOfSingingAtMost() {
        val a = outro()
        fun start(lines: List<LongRange>) = LoopBridge.loopStart(a, MixFacts(duration, a, vocalEndMs = lines.last().last, sungLines = lines), 7_500.0, 15_000)
        assertEquals(223_125.0, start(listOf(190_000L..200_000L))!!, 1e-6)
        assertEquals("the tail of a word (2.7 %)", 223_125.0, start(listOf(190_000L..200_000L, 222_900L..223_325L))!!, 1e-6)
        assertNull("4 %: and no earlier bar after the last sung part", start(listOf(190_000L..200_000L, 222_900L..223_425L)))
        assertEquals(.04f, LoopBridge.sungShare(0.0, 1_000.0, listOf(100L..120L, 500L..520L)), 1e-6f)
        assertEquals(0f, LoopBridge.sungShare(0.0, 1_000.0, emptyList()), 0f)
    }

    @Test fun theIncomingTitleEntersOnItsEarliestInstrumentalBars() {
        val b = intro()
        fun entry(lines: List<LongRange>, from: Long? = lines.firstOrNull()?.first) =
            LoopBridge.entry(b, MixFacts(duration, b, vocalStartMs = from, sungLines = lines), 8_889.0)
        assertEquals(500L, entry(listOf(32_000L..36_000L)))
        // A shout in the first bar: the next bar.
        assertEquals(2_722L, entry(listOf(1_000L..1_600L, 32_000L..36_000L), from = 32_000))
        assertNull("it sings from its first bar", entry(listOf(600L..20_000L)))
    }

    @Test fun onlyWhereItCannotGoWrong() {
        fun bridged(out: MixFacts = outFacts(), incoming: MixFacts = inFacts(), context: MixContext = MixContext(),
                    settings: MixSettings = MixSettings(1)) = plan(out, incoming, context, settings).glide != null
        assertTrue(bridged())
        assertTrue("DJ setting", bridged(settings = MixSettings(2)))
        assertFalse("discreet leaves nothing out", bridged(settings = MixSettings(0)))
        assertFalse("tempo match switched off in the DJ editor", bridged(settings = MixSettings(1, tempoMatch = false)))
        assertFalse("two loops in a row", bridged(context = MixContext(loopedLast = true)))
        assertFalse("a title on repeat", bridged(context = MixContext(loop = true, repeatOne = true)))
        assertFalse("no effects", bridged(context = MixContext(effects = false)))
        assertFalse("too late for the beats", bridged(context = MixContext(beats = false)))
        assertFalse("beats not sure enough to loop", bridged(outFacts(outro(confidence = .6f))))
        assertFalse("bars not told apart", bridged(outFacts(outro(bar = -1))))
        assertFalse("incoming bars not told apart", bridged(incoming = inFacts(intro(bar = -1))))
        assertFalse("keys that clash", bridged(outFacts(outro(key = 0)), inFacts(intro(key = 6))))
        assertFalse("more than 25 % apart", bridged(incoming = inFacts(intro(100f))))
        assertFalse("the incoming voice would sing stretched", bridged(incoming = inFacts(from = 12_000, lines = listOf(12_000L..16_000L), mask = null)))
        assertFalse("the level falls before there is room for a loop", bridged(outFacts(outro(fall = 205_000))))
        assertFalse("sung to the end", bridged(outFacts(lyrics = lyricsOut + listOf(201_000L..236_000L), end = 236_000)))
        assertFalse("never chosen for Ketmix's other styles", bridged(settings = MixSettings(1, MixStyle.Tempo)))
    }

    @Test fun askedForItIsPlayedEvenWithinRangeElseTheTempoStyle() {
        val near = plan(incoming = inFacts(intro(124f)), settings = MixSettings(1, MixStyle.Bridge))
        assertNotNull("chosen, it bridges a small gap too", near.glide)
        assertTrue(near.glide!!.bpmPerBar(128f, 1_875.0) <= LoopBridge.MAX_BPM_PER_BAR)
        val far = plan(incoming = inFacts(intro(100f)), settings = MixSettings(1, MixStyle.Bridge))
        assertEquals(plan(incoming = inFacts(intro(100f)), settings = MixSettings(1, MixStyle.Tempo)), far)
        assertNull(far.glide)
        // A point the listener moved: a blend in time or a crossing, never a bridge.
        val moved = plan(outFacts(exit = 221_000), settings = MixSettings(1, MixStyle.Bridge))
        assertTrue(moved.mixes)
        assertNull(moved.glide)
        assertEquals("listener", moved.reason)
    }

    @Test fun halfAndDoubleTemposAreFolded() {
        // 170 BPM into 90: the incoming title in double time, 180 against 170 (a pulse of 85 against 90).
        val p = plan(outFacts(outro(170f)), inFacts(intro(90f)), settings = MixSettings(1, MixStyle.Bridge))
        val glide = p.glide!!
        assertEquals(170f / 180f, p.rate, 1e-5f)
        assertEquals(180f / 170f, glide.toSpeed, 1e-5f)
        val bar = CutBudget.barMs(outro(170f))
        assertEquals(240_000.0 / 85, bar, 1e-3)
        assertEquals(8 * bar, p.overlapMs.toDouble(), 1.0)
        assertTrue(glide.bpmPerBar(85f, bar) <= .63f)
    }

    @Test fun everyBridgeKeepsToTheGlideRules() {
        var bridges = 0
        for (from in 84..172 step 8) for (to in 78..178 step 6) for (intensity in 1..2) {
            val a = outro(from.toFloat())
            val b = intro(to.toFloat())
            val settings = MixSettings(intensity)
            val p = LoopBridge.plan(a, b, outFacts(a), inFacts(b), 1f, settings, 1f) ?: continue
            bridges++
            val g = p.glide!!
            val bar = CutBudget.barMs(a)
            assertTrue("$from → $to", g.bpmPerBar((240_000.0 / bar).toFloat(), bar) <= LoopBridge.MAX_BPM_PER_BAR + 1e-3f)
            assertTrue("$from → $to ends within the tempo range", abs(g.toSpeed * p.rate - 1f) <= settings.tempoRange + 1e-4f)
            assertTrue(abs(1f - p.rate) <= LoopBridge.MAX_GAP + 1e-4f)
            assertEquals(p.outMs, g.fromMs); assertEquals(p.outMs + p.overlapMs, g.untilMs)
            assertTrue("$from → $to: at least two turns of the loop", p.overlapMs >= LoopBridge.MIN_BARS * bar - 1)
            assertEquals(LoopBridge.LOOP_BARS * bar * 1000, p.loopUs.toDouble(), 1.0)
            assertTrue("$from → $to: swap half-way", abs(p.swapMs - (p.outMs + p.overlapMs / 2.0)) <= 2 * a.beatMs + 1)
            assertTrue(p.outMs + p.overlapMs <= duration - 500)
        }
        assertTrue("bridges planned: $bridges", bridges > 30)
    }

    // ----------------------------------------------------------------- the glide

    @Test fun theGlideIsAStraightLineFromOneTempoToTheOther() {
        val g = TempoGlide(10_000, 25_000, 1f, .84375f)
        assertEquals(1f, g.speedAt(5_000), 0f)
        assertEquals(1f, g.speedAt(10_000), 0f)
        assertEquals((1f + .84375f) / 2, g.speedAt(17_500), 1e-6f)
        assertEquals(.84375f, g.speedAt(25_000), 0f)
        assertEquals(.84375f, g.speedAt(40_000), 0f)
        assertEquals("from the deck's own speed when the mix starts", 1.01f, g.speedAt(10_000, from = 1.01f), 0f)
        assertEquals(2.5f, g.bpmPerBar(128f, 1_875.0), 1e-4f)
        assertEquals(8, LoopBridge.glideBars(128f, 108f))
        assertEquals(1, LoopBridge.glideBars(120f, 121f))
        assertEquals(0, LoopBridge.glideBars(120f, 120f))
    }

    @Test fun theIncomingVoiceIsCheckedAgainstTheGlide() {
        val p = plan()
        assertEquals(p.rate, LoopBridge.incomingSpeedAt(p, p.inMs, 1f), 1e-6f)
        assertEquals(1f, LoopBridge.incomingSpeedAt(p, p.inEndMs, 1f), 1e-4f)
        val voiceLate = MixFacts(duration, intro(), vocalStartMs = 17_500)
        assertFalse(CutBudget.stretchedUnderVoice(p, voiceLate, 1f))
        val voiceEarly = MixFacts(duration, intro(), vocalStartMs = 9_000)
        assertTrue(CutBudget.stretchedUnderVoice(p, voiceEarly, 1f))
    }

    /** Two decks whose speed changes reach their sound [outLatencyMs] and [inLatencyMs] after they are set. */
    private class Simulation(val plan: MixPlan, val outLatencyMs: Long, val inLatencyMs: Long) {
        val glide = plan.glide!!
        val driver = GlideDriver(plan, glide, 1f, plan.rate)
        val outChanges = ArrayList<Pair<Long, Float>>().apply { add(0L to 1f) }
        val inChanges = ArrayList<Pair<Long, Float>>().apply { add(0L to plan.rate) }
        var outPos = plan.outMs - 2_000.0
        var inPos = plan.inMs + (outPos - plan.outMs) * plan.rate
        var worstHeardMs = 0.0
        var worstBpmPerBar = 0.0
        val changeTimes = ArrayList<Long>()

        fun speed(changes: List<Pair<Long, Float>>, latency: Long, now: Long) = changes.last { it.first + latency <= now || it.first == 0L }.second

        fun run() {
            var smoothed = Double.NaN
            var now = 0L
            var barStart = plan.outMs.toDouble(); var barSpeed = 1f
            val bar = CutBudget.barMs(SegmentAnalysis(0, 1, 60_000f / plan.beatMs, 0.0, .9f, 0))
            while (outPos < plan.outMs + plan.overlapMs + 1_000) {
                now += 10
                val so = speed(outChanges, outLatencyMs, now); val si = speed(inChanges, inLatencyMs, now)
                outPos += 10 * so; inPos += 10 * si
                if (outPos >= barStart + bar) {
                    worstBpmPerBar = maxOf(worstBpmPerBar, abs(so - barSpeed) * 240_000.0 / bar)
                    barStart += bar; barSpeed = so
                }
                if (now % 40L != 0L) continue
                val error = DeckSync.errorMs(plan, outPos.toLong(), inPos.toLong())
                smoothed = if (smoothed.isNaN()) error else smoothed * .6 + error * .4
                if (DeckSync.heard(plan, inPos.toLong()) && outPos < plan.outMs + plan.overlapMs) worstHeardMs = maxOf(worstHeardMs, abs(error))
                if (driver.step(now, outPos.toLong(), smoothed, DeckSync.settled(plan, inPos.toLong()))) {
                    outChanges += now to driver.outSpeed; inChanges += now to driver.inSpeed; changeTimes += now
                }
            }
        }
    }

    @Test fun bothDecksStayOnTheSameBeatsThroughTheGlide() {
        val p = plan()
        for ((outLatency, inLatency) in listOf(400L to 400L, 300L to 520L, 650L to 250L, 900L to 300L)) {
            val s = Simulation(p, outLatency, inLatency).apply { run() }
            println("loop bridge 128 → 108 BPM, latencies $outLatency/$inLatency ms: worst sync %.1f ms, worst %.2f BPM per bar, %d speed changes"
                .format(s.worstHeardMs, s.worstBpmPerBar, s.changeTimes.size))
            // Two decks of one sink type differ by far less than 0.6 s of buffer; even then they stay within 16 ms.
            val bound = if (abs(outLatency - inLatency) <= 400) 12.0 else 16.0
            assertTrue("latencies $outLatency/$inLatency: ${s.worstHeardMs} ms off", s.worstHeardMs < bound)
            // Measured bar to bar on the speeds the deck really played: a quarter-second step (0.33 BPM) at most beside the line.
            assertTrue("${s.worstBpmPerBar} BPM per bar", s.worstBpmPerBar <= LoopBridge.MAX_BPM_PER_BAR + .35)
            assertEquals(p.glide!!.toSpeed, s.driver.outSpeed, 1e-6f)
            assertEquals("the incoming deck ends on its own speed", 1f, s.driver.inSpeed, .006f)
            assertTrue("changes at least ${GlideDriver.EVERY_MS} ms apart", s.changeTimes.zipWithNext { x, y -> y - x }.all { it >= GlideDriver.EVERY_MS })
            // Never a jump: each change of the outgoing speed is a small step.
            assertTrue(s.outChanges.zipWithNext { x, y -> abs(y.second - x.second) }.all { it <= .004f })
        }
    }

    @Test fun nudgesWhileHeardAreGentle() {
        assertEquals(0.0, GlideDriver.correction(5.0, audible = true), 0.0)
        assertEquals(.005, GlideDriver.correction(10.0, audible = true), 1e-9)
        assertEquals(GlideDriver.MAX_NUDGE, GlideDriver.correction(500.0, audible = true), 0.0)
        assertEquals(DeckSync.correction(30.0, audible = false), GlideDriver.correction(30.0, audible = false), 0.0)
        // DeckSync.speed is unchanged by the split: 30 ms heard is caught up at 1.05 %, on its 0.2 % grid.
        assertEquals(.99f, DeckSync.speed(1f, 30.0, audible = true), 1e-6f)
        assertEquals(1.013f, DeckSync.speed(1.013f, 5.0, audible = true), 0f)
    }

    // ----------------------------------------------------------------- stored choices, words

    @Test fun theNewStyleIsAppendedAndRoundTrips() {
        assertEquals(9, MixStyle.Bridge.ordinal)
        assertEquals(8, MixStyle.Voices.ordinal)
        assertEquals(MixStyle.Bridge, MixStyle.of(9))
        val choice = PairChoice(MixStyle.Bridge, 15_000)
        val text = MixPointStore.encodePair(choice)
        assertEquals("9,15000", text)
        assertEquals(choice, MixPointStore.decodePair(text))
        assertEquals("Pont en boucle", com.ketvoice.app.ui.player.presetOf(MixStyle.Bridge).label)
        assertTrue(com.ketvoice.app.ui.player.presets().any { it.style == MixStyle.Bridge })
    }

    @Test fun itsWordsAreTranslated() {
        val file = File("src/main/assets/translations.json").takeIf { it.isFile } ?: File("app/src/main/assets/translations.json")
        val catalog = org.json.JSONObject(file.readText())
        val languages = listOf("en", "hi", "ja", "zh", "ru", "it", "es", "de", "pt", "ko")
        for (text in listOf("Pont en boucle", "pont en boucle",
                "Le titre tourne sur quatre mesures sans chant pendant que le tempo glisse jusqu'à celui du suivant, même très éloigné")) {
            for (language in languages) assertTrue("$language misses « $text »", catalog.getJSONObject(language).has(text))
        }
        val settings = (File("src/main/kotlin").takeIf { it.isDirectory } ?: File("app/src/main/kotlin"))
            .resolve("com/ketvoice/app/ui/settings/SettingsScreen.kt").readText()
        assertTrue(settings.contains("ChoiceOption(\"9\", \"Pont en boucle\""))
        assertTrue(settings.contains("coerceIn(0, 9)"))
    }
}
