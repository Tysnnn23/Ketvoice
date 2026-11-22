package com.ketvoice.app.player

import android.media.AudioDeviceInfo
import com.ketvoice.app.data.AppSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** « Égaliseur par casque » (9.0): the filters' coefficients, the profiles read and kept, links, and no clipping. */
class HeadphoneEqTest {
    @After fun reset() {
        AppSettings.headphoneEq.value = true
        AppSettings.headphoneProfiles.value = ""
        AppSettings.headphoneLinks.value = ""
        AppSettings.bitPerfect.value = false
        HeadphoneEq.compare.value = false
        HeadphoneEq.changed()
    }

    private fun assertCoefficients(expected: DoubleArray, actual: Biquad) {
        val got = doubleArrayOf(actual.b0, actual.b1, actual.b2, actual.a1, actual.a2)
        expected.indices.forEach { assertEquals("coefficient $it", expected[it], got[it], 1e-7) }
    }

    // ---- Coefficients (RBJ Audio EQ Cookbook, a0 normalised to 1) ----

    @Test fun aPeakHasTheCookbookCoefficientsAtEachSampleRate() {
        assertCoefficients(doubleArrayOf(1.0439530870, -1.8953207239, 0.8677222848, -1.8953207239, 0.9116753718),
            Biquad.of(EqFilter(EqFilterType.Peak, 1000f, 6f, 1f), 48_000))
        assertCoefficients(doubleArrayOf(1.0476300262, -1.8849912525, 0.8566564609, -1.8849912525, 0.9042864871),
            Biquad.of(EqFilter(EqFilterType.Peak, 1000f, 6f, 1f), 44_100))
    }

    @Test fun shelvesHaveTheCookbookCoefficients() {
        assertCoefficients(doubleArrayOf(1.0034127416, -1.9834162328, 0.9802681475, -1.9834822398, 0.9836148822),
            Biquad.of(EqFilter(EqFilterType.LowShelf, 105f, 6f, .7f), 48_000))
        assertCoefficients(doubleArrayOf(0.7789104534, -0.0285581633, 0.1300053922, -0.3027192463, 0.1830769286),
            Biquad.of(EqFilter(EqFilterType.HighShelf, 10_000f, -4f, .7f), 44_100))
    }

    @Test fun theResponseIsTheGainWhereEachFilterActs() {
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            val peak = Biquad.of(EqFilter(EqFilterType.Peak, 2_000f, -5f, 2f), rate)
            assertEquals(-5.0, peak.magnitudeDb(2_000.0, rate), 1e-6)
            assertEquals(0.0, peak.magnitudeDb(20.0, rate), .01)
            val low = Biquad.of(EqFilter(EqFilterType.LowShelf, 100f, 6f, .71f), rate)
            assertEquals(6.0, low.magnitudeDb(5.0, rate), .05)
            assertEquals(3.0, low.magnitudeDb(100.0, rate), .05)
            assertEquals(0.0, low.magnitudeDb(15_000.0, rate), .05)
            val high = Biquad.of(EqFilter(EqFilterType.HighShelf, 8_000f, 4f, .71f), rate)
            assertEquals(4.0, high.magnitudeDb(rate * .49, rate), .3)
            assertEquals(0.0, high.magnitudeDb(50.0, rate), .01)
        }
        assertEquals(Biquad.IDENTITY, Biquad.of(EqFilter(EqFilterType.Peak, 1000f, 0f, 1f), 48_000))
    }

    @Test fun aFilterAboveHalfTheRateIsBroughtBackUnderIt() {
        val b = Biquad.of(EqFilter(EqFilterType.Peak, 23_000f, 3f, 1f), 32_000)
        assertTrue(b.magnitudeDb(14_400.0, 32_000) > 2.9)
        assertTrue(listOf(b.b0, b.b1, b.b2, b.a1, b.a2).all { it.isFinite() })
    }

    // ---- Profiles ----

    private val autoEqText = """Preamp: -6.2 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
        Filter 2: ON PK Fc 162 Hz Gain -2,1 dB Q 0.53
        Filter 3: OFF PK Fc 2000 Hz Gain 9.0 dB Q 1.00
        Filter 4: ON PK Fc 3488 Hz Gain 3.5 dB Q 2.75
        Filter 5: ON HSC 12 dB Fc 10000 Hz Gain -2.6 dB Q 0.70
        Filter 6: ON HS Fc 12000 Hz Gain 1.0 dB
        Filter 7: ON LP Fc 18000 Hz
        Filter 8: ON PK Fc 5 Hz Gain 3 dB Q 1""".trimIndent()

    @Test fun aParametricTextIsRead() {
        val p = HeadphoneProfiles.parse(autoEqText, "Sennheiser HD 600 ParametricEQ.txt", id = "abc")
        assertEquals("Sennheiser HD 600", p.name)
        assertEquals(-6.2f, p.preampDb, 1e-6f)
        assertEquals("OFF, LP and a filter under 10 Hz are left out", 5, p.filters.size)
        assertEquals(EqFilter(EqFilterType.LowShelf, 105f, 6.4f, .7f), p.filters[0])
        assertEquals("a decimal comma is read", -2.1f, p.filters[1].gainDb, 1e-6f)
        assertEquals(EqFilter(EqFilterType.HighShelf, 10_000f, -2.6f, .7f), p.filters[3])
        assertEquals("a shelf without Q takes 0.71", .71f, p.filters[4].q, 1e-6f)
    }

    @Test fun textWithoutFiltersIsRefused() {
        val error = runCatching { HeadphoneProfiles.parse("Preamp: -3 dB\nhello", "x") }.exceptionOrNull()
        assertEquals("Aucun filtre paramétrique trouvé dans ce texte", error?.message)
        val many = (1..21).joinToString("\n") { "Filter $it: ON PK Fc ${100 * it} Hz Gain 1 dB Q 1" }
        assertNotNull(runCatching { HeadphoneProfiles.parse(many, "x") }.exceptionOrNull())
    }

    @Test fun profilesAreKeptAndReadBackWhole() {
        val a = HeadphoneProfiles.parse(autoEqText, "Sennheiser HD 600", id = "a1")
        val b = HeadphoneProfiles.parse("Filter: ON PK Fc 1000 Hz Gain -3 dB Q 1.4", "Sony WH-1000XM4", id = "b2")
        val text = HeadphoneProfiles.encode(listOf(a, b))
        assertEquals(listOf(a, b), HeadphoneProfiles.decode(text))
        assertTrue(HeadphoneProfiles.valid(text))
        assertTrue(HeadphoneProfiles.valid(""))
        assertTrue(HeadphoneProfiles.valid("[{\"id\":\"a1\",\"n\":\"x\",\"p\":-1,\"f\":[[\"PK\",1000,-3,1]]}]"))
        assertFalse("a gain out of range is refused", HeadphoneProfiles.valid("[{\"id\":\"a1\",\"n\":\"x\",\"p\":-1,\"f\":[[\"PK\",1000,-99,1]]}]"))
        assertFalse(HeadphoneProfiles.valid("[{\"id\":\"x\"}]"))
        assertFalse(HeadphoneProfiles.valid("not json"))
        assertEquals(emptyList<HeadphoneProfile>(), HeadphoneProfiles.decode("{]"))
    }

    @Test fun theSearchFindsEveryWordWhateverTheCaseAndAccents() {
        val list = listOf("Sennheiser HD 600", "Sony WH-1000XM4", "Bose QuietComfort Écouteurs").mapIndexed { i, n ->
            HeadphoneProfile("p$i", n, 0f, listOf(EqFilter(EqFilterType.Peak, 1000f, 1f, 1f)))
        }
        assertEquals(listOf("Sony WH-1000XM4"), HeadphoneProfiles.search(list, "sony 1000").map { it.name })
        assertEquals(listOf("Bose QuietComfort Écouteurs"), HeadphoneProfiles.search(list, "ecouteurs bose").map { it.name })
        assertEquals(3, HeadphoneProfiles.search(list, " ").size)
    }

    // ---- Preamp and clipping ----

    @Test fun theSafePreampLeavesNoFrequencyAboveFullScale() {
        val boosted = HeadphoneProfile("x", "x", -1f, listOf(EqFilter(EqFilterType.Peak, 3000f, 8f, 1f), EqFilter(EqFilterType.LowShelf, 100f, 5f, .7f)))
        val preamp = boosted.safePreampDb()
        assertTrue("preamp $preamp", preamp <= -7.99f)
        var hz = 20.0
        while (hz < 20_000) { assertTrue(boosted.responseDb(hz) + preamp <= 1e-3); hz *= 1.01 }
        val cut = HeadphoneProfile("y", "y", -3f, listOf(EqFilter(EqFilterType.Peak, 3000f, -6f, 1f)))
        assertEquals("a profile that only cuts keeps its own preamp", -3f, cut.safePreampDb(), 1e-6f)
    }

    @Test fun aFullScaleSineAtTheBoostedFrequencyDoesNotClip() {
        val profile = HeadphoneProfile("x", "x", 0f, listOf(EqFilter(EqFilterType.Peak, 1000f, 9f, 1.5f)))
        val rate = 48_000
        val eq = EqCascade(profile, rate, 1)
        var peak = 0.0
        var sumIn = 0.0; var sumOut = 0.0
        for (i in 0 until rate) {
            val x = sin(2 * PI * 1000 * i / rate)
            val y = eq.sample(x, 0)
            if (i > rate / 2) { peak = maxOf(peak, abs(y)); sumIn += x * x; sumOut += y * y }
        }
        assertTrue("peak $peak stays under full scale", peak <= 1.0005)
        // At the filter's own frequency the boost and the preamp cancel out: the level is the same.
        assertEquals(0.0, 10 * log10(sumOut / sumIn), .05)
    }

    @Test fun theCascadeFiltersAsTheResponseSays() {
        val profile = HeadphoneProfile("x", "x", -2f, listOf(EqFilter(EqFilterType.Peak, 200f, -6f, 1f)))
        val rate = 44_100
        val eq = EqCascade(profile, rate, 2)
        var sumIn = 0.0; var sumOut = 0.0
        for (i in 0 until rate) {
            val x = .5 * sin(2 * PI * 200 * i / rate)
            val left = eq.sample(x, 0); eq.sample(0.0, 1)
            if (i > rate / 4) { sumIn += x * x; sumOut += left * left }
        }
        assertEquals("-6 dB filter and -2 dB preamp", -8.0, 10 * log10(sumOut / sumIn), .05)
    }

    // ---- Links ----

    private fun output(kind: OutputKind, name: String, address: String = "", id: Int = 7) =
        AudioOutput(id, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, kind, name, address)

    @Test fun outputsAreLinkedByDigestsAndOnlyHeadphonesCanBe() {
        val bt = output(OutputKind.Bluetooth, "WH-1000XM4", "aa:bb:cc:dd:ee:ff")
        val key = HeadphoneEq.keyOf(bt)!!
        assertTrue(key.matches(Regex("bt:[0-9a-f]{16}")))
        assertFalse("the address itself is never kept", "aa:bb" in key.lowercase())
        assertEquals("the same headset, however Android writes its address", key, HeadphoneEq.keyOf(bt.copy(address = "AA:BB:CC:DD:EE:FF", id = 99)))
        assertNotEquals(key, HeadphoneEq.keyOf(bt.copy(address = "11:22:33:44:55:66")))
        assertTrue(HeadphoneEq.keyOf(bt.copy(address = ""))!!.startsWith("btn:"))
        assertEquals("wired", HeadphoneEq.keyOf(output(OutputKind.Wired, "")))
        assertTrue(HeadphoneEq.keyOf(output(OutputKind.Usb, "DAC"))!!.startsWith("usb:"))
        assertNull(HeadphoneEq.keyOf(output(OutputKind.Phone, "Phone")))
        assertNull(HeadphoneEq.keyOf(output(OutputKind.Hdmi, "TV")))
    }

    @Test fun theLinkedProfilePlaysOnlyOnItsOutputWhileOnAndNotComparing() {
        val profile = HeadphoneEq.add(HeadphoneProfiles.parse("Filter: ON PK Fc 1000 Hz Gain -3 dB Q 1.4", "Sony WH-1000XM4"))
        val headset = output(OutputKind.Bluetooth, "WH-1000XM4", "aa:bb:cc:dd:ee:ff", id = 11)
        val speaker = AudioOutput(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, OutputKind.Phone, "Phone")
        HeadphoneEq.link(headset, profile)
        assertEquals(profile.id, HeadphoneEq.links().values.single().profileId)
        assertEquals("WH-1000XM4", HeadphoneEq.links().values.single().name)

        HeadphoneEq.follow(listOf(speaker, headset), null)
        assertEquals("Android plays to the headset: its correction is heard", profile, HeadphoneEq.playing)
        assertTrue(HeadphoneEq.status.value.applied)
        HeadphoneEq.follow(listOf(speaker, headset), speaker.id)
        assertNull("the speaker chosen: no correction", HeadphoneEq.playing)
        HeadphoneEq.follow(listOf(speaker), null)
        assertNull("the headset gone", HeadphoneEq.playing)
        // Back again under another id: the same headset, the same correction.
        HeadphoneEq.follow(listOf(speaker, headset.copy(id = 42)), null)
        assertEquals(profile, HeadphoneEq.playing)
        HeadphoneEq.compare.value = true
        HeadphoneEq.follow(listOf(speaker, headset), null)
        assertNull("« Écouter sans correction »", HeadphoneEq.playing)
        assertEquals(profile, HeadphoneEq.status.value.profile)
        HeadphoneEq.compare.value = false
        AppSettings.headphoneEq.value = false
        HeadphoneEq.follow(listOf(speaker, headset), null)
        assertNull("switched off", HeadphoneEq.playing)
        AppSettings.headphoneEq.value = true
        HeadphoneEq.remove(profile.id)
        assertTrue("removing a profile removes its links", HeadphoneEq.links().isEmpty())
        HeadphoneEq.follow(listOf(speaker, headset), null)
        assertNull(HeadphoneEq.playing)
    }

    @Test fun linksAreReadBackWholeAndCheckedForBackups() {
        val text = HeadphoneEq.encodeLinks(mapOf("wired" to HeadphoneEq.Link("abc", "Écouteurs"), "bt:0123456789abcdef" to HeadphoneEq.Link("d4", "XM4")))
        assertTrue(HeadphoneEq.validLinks(text))
        assertEquals(2, HeadphoneEq.decodeLinks(text).size)
        assertFalse("an address in clear is refused", HeadphoneEq.validLinks("{\"bt:AA:BB:CC:DD:EE:FF\":{\"p\":\"a\",\"n\":\"x\"}}"))
        assertTrue(HeadphoneEq.validLinks(""))
        assertEquals(text, com.ketvoice.app.data.PersonalBackup.setting("headphoneLinks", text))
        val profiles = HeadphoneProfiles.encode(listOf(HeadphoneProfiles.parse(autoEqText, "HD 600", id = "a1")))
        assertEquals(profiles, com.ketvoice.app.data.PersonalBackup.setting("headphoneProfiles", profiles))
        assertNull(com.ketvoice.app.data.PersonalBackup.setting("headphoneProfiles", "[1]"))
        assertEquals(false, com.ketvoice.app.data.PersonalBackup.setting("headphoneEq", false))
        assertTrue("synced with the account", "headphoneProfiles" in com.ketvoice.app.sync.SettingsKind.keys)
    }

    @Test fun aChangeOfCorrectionCrossfadesAndOffPassesTheSoundThrough() {
        val rate = 48_000
        var wanted: HeadphoneProfile? = HeadphoneProfile("x", "x", -12f, listOf(EqFilter(EqFilterType.Peak, 1000f, 1f, 1f)))
        val processor = HeadphoneEqProcessor { wanted }
        processor.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(rate, 1, androidx.media3.common.C.ENCODING_PCM_16BIT))
        processor.flush()
        fun run(frames: Int): ShortArray {
            val input = java.nio.ByteBuffer.allocateDirect(frames * 2).order(java.nio.ByteOrder.nativeOrder())
            repeat(frames) { input.putShort(16_000) }
            input.flip()
            processor.queueInput(input)
            val out = processor.output
            return ShortArray(out.remaining() / 2) { out.short }
        }
        val first = run(4800)
        assertTrue("corrected from the first sound (-12 dB): ${first[4000]}", first[4000] in 3_900..4_200)
        wanted = null
        val fading = run(4800)
        val fadeFrames = rate * HeadphoneEqProcessor.FADE_MS / 1000
        assertTrue("half way through the fade, between both: ${fading[fadeFrames / 2]}", fading[fadeFrames / 2] in 6_000..14_000)
        assertEquals("then the sound passes through untouched", 16_000, fading[4000].toInt())
        val off = run(100)
        assertTrue(off.all { it.toInt() == 16_000 })
    }
}
