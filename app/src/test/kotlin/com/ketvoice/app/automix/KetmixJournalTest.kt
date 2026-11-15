package com.ketvoice.app.automix

import org.junit.Assert.*
import org.junit.Test

class KetmixJournalTest {
    @Test fun theJournalKeepsTheLastTransitionsAndReadsAsText() {
        KetmixJournal.clear()
        repeat(KetmixJournal.LIMIT + 5) { KetmixJournal.add(KetmixJournal.Entry(it.toLong(), "A$it", "B$it", "not mixed: late")) }
        assertEquals(KetmixJournal.LIMIT, KetmixJournal.entries.value.size)
        assertEquals("newest first", "A${KetmixJournal.LIMIT + 4}", KetmixJournal.entries.value.first().from)
        KetmixJournal.clear()
        KetmixJournal.add(KetmixJournal.Entry(0, "Alpha — Werenoi", "Vermine — Werenoi", "mixed", MixKind.Beatmatch, "64 beats",
            outBpm = 140f, outConfidence = .8f, outKey = 0, inBpm = 142f, inConfidence = .7f, inKey = 7, rate = .986f, overlapMs = 27_000,
            onPhrase = true, precise = false, clock = false, sungUntilMs = 170_000, sungFromMs = 4_000, analysis = "balanced"))
        val text = KetmixJournal.text()
        for (part in listOf("Alpha — Werenoi → Vermine — Werenoi", "mixed · Beatmatch (64 beats)", "overlap 27.0 s", "rate 0.986",
                "on a phrase", "140.0 BPM (80 %)", "8B", "9B", "precise false", "clock false", "singing ends 170 s", "next sings at 4 s", "balanced"))
            assertTrue("$part in\n$text", part in text)
        KetmixJournal.clear()
    }
}
