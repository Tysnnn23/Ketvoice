package com.ketvoice.desktop.audio

import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** « Mode exclusif »: when the output may go exclusive, why it does not, and the shared output taking over. */
class ExclusiveRulesTest {
    private val neutral = ExclusiveRules.Inputs(wanted = true, windows = true, dllLoaded = true, volume = 1f, equalizer = false,
        headphoneEq = false, ketmix = false, voiceLevel = false, mixing = false)

    @Test fun exclusiveOnlyWhenNothingChangesTheSound() {
        assertEquals(ExclusiveRules.Decision(true), ExclusiveRules.decide(neutral))
        assertEquals(ExclusiveRules.Decision(false), ExclusiveRules.decide(neutral.copy(wanted = false)), "off: no reason to give")
        val reasons = mapOf(
            neutral.copy(windows = false) to "Le mode exclusif n'existe que sur Windows",
            neutral.copy(dllLoaded = false) to "Le composant audio exclusif est absent de cette installation",
            neutral.copy(ketmix = true) to "Ketmix est activé",
            neutral.copy(mixing = true) to "Ketmix est activé",
            neutral.copy(equalizer = true) to "L'égaliseur est activé",
            neutral.copy(headphoneEq = true) to "L'égaliseur par casque est activé",
            neutral.copy(voiceLevel = true) to "Le niveau de la voix n'est pas à 100 %",
            neutral.copy(volume = .8f) to "Le volume n'est pas à 100 %",
        )
        reasons.forEach { (inputs, reason) ->
            val d = ExclusiveRules.decide(inputs)
            assertFalse(d.exclusive, reason)
            assertEquals(reason, d.reason)
        }
        // Several at once: the first one a listener would fix is said.
        assertEquals("Ketmix est activé", ExclusiveRules.decide(neutral.copy(ketmix = true, equalizer = true, volume = .5f)).reason)
    }

    @Test fun theDllIsOnlyLookedForOnWindows() {
        if (WasapiNative.isWindows) return
        assertNull(WasapiNative.library())
        assertFalse(WasapiNative.available)
        val lines = ArrayList<String>()
        assertTrue(WasapiNative.smoke { lines += it })
        assertEquals(listOf("wasapi skipped: not Windows"), lines)
    }

    @Test fun aRefusedExclusiveOpenFallsBackToTheSharedOutput() {
        val shared = CaptureSink()
        val refusing = object : PcmSink by NullSink() {
            override fun openFormat(sampleRate: Int, channels: Int, bits: Int) = throw IOException("exclusive: 0x8889000A")
        }
        val sink = SwitchingSink(shared, refusing)
        sink.exclusiveWanted = true
        sink.openFormat(44_100, 2, 24)
        assertEquals(44_100, shared.sampleRate, "the shared output opens at the title's rate")
        assertEquals(2, sink.bytesPerSample)
        assertEquals("exclusive: 0x8889000A", sink.refusal)
        // Taken: the exclusive sink's format and 32-bit samples.
        val wide = object : PcmSink by NullSink() {
            override val bytesPerSample = 4
            override val format = PcmSink.Format(96_000, 24, exclusive = true)
            override fun openFormat(sampleRate: Int, channels: Int, bits: Int) {}
        }
        val ok = SwitchingSink(CaptureSink(), wide).apply { exclusiveWanted = true }
        ok.openFormat(96_000, 2, 24)
        assertEquals(4, ok.bytesPerSample)
        assertEquals(PcmSink.Format(96_000, 24, exclusive = true), ok.format)
        assertNull(ok.refusal)
    }

    @Test fun deviceErrorsAreSaidInWords() {
        assertEquals("Une autre application utilise le périphérique en mode exclusif", WasapiNative.reason(0x8889000A.toInt()))
        assertEquals("Le mode exclusif n'est pas autorisé pour ce périphérique", WasapiNative.reason(0x80070005.toInt()))
        assertEquals("Le périphérique refuse le mode exclusif", WasapiNative.reason(-1))
    }
}
