package com.ketvoice.desktop.audio

import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.Biquad
import com.ketvoice.app.player.EqFilter
import com.ketvoice.app.player.EqFilterType
import com.ketvoice.app.player.HeadphoneLinks
import com.ketvoice.app.player.HeadphoneProfile
import com.ketvoice.app.player.HeadphoneProfiles
import com.ketvoice.app.player.HeadphoneStore
import com.ketvoice.desktop.audio.AudioFixtures.Recorder
import com.ketvoice.desktop.audio.AudioFixtures.left
import com.ketvoice.desktop.ui.HeadphoneEqLogic
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * « Égaliseur par casque » and « Sortie audio » on Windows: the shared biquads and profile files, the links keyed by a
 * Windows device's name (in a form the phone keeps), the stage in the engine's PCM path with its safe preamp and 60 ms
 * crossfade, the remembered output with its fallback to the system's default, and the live switch of the sink.
 */
class HeadphoneEqDesktopTest {
    private val rate = FfmpegAudioEngine.RATE

    private val apo = """
        Preamp: -6,4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
        Filter 2: ON PK Fc 220 Hz Gain -2.1 dB Q 1.40
        Filter 3: OFF PK Fc 3000 Hz Gain 9 dB Q 2
        Filter 4: ON PK Fc 3000 Hz Gain -3.0 dB Q 2.00
        Filter 5: ON HSC Fc 10000 Hz Gain 2.0 dB
        Filter 6: ON LP Fc 18000 Hz
    """.trimIndent()

    @Before fun setUp() { clear() }
    @After fun tearDown() { clear(); DesktopHeadphoneEq.resetForTest(); DesktopOutput.resetForTest() }

    private fun clear() {
        AppSettings.headphoneEq.value = true
        AppSettings.headphoneProfiles.value = ""
        AppSettings.headphoneLinks.value = ""
    }

    // ----------------------------------------------------------------- biquads

    @Test fun theBiquadsHaveTheCookbookResponse() {
        val peak = Biquad.of(EqFilter(EqFilterType.Peak, 1_000f, 6f, 1.4f), rate)
        assertEquals(6.0, peak.magnitudeDb(1_000.0, rate), 1e-6, "a peak has its gain at its centre")
        assertTrue(abs(peak.magnitudeDb(50.0, rate)) < .05, "and none far from it")
        assertTrue(abs(peak.magnitudeDb(18_000.0, rate)) < .1)
        val low = Biquad.of(EqFilter(EqFilterType.LowShelf, 100f, -4f, .71f), rate)
        assertEquals(-4.0, low.magnitudeDb(20.0, rate), .1, "a low shelf lowers what is under its corner")
        assertEquals(-2.0, low.magnitudeDb(100.0, rate), .05, "half its gain at its corner")
        assertTrue(abs(low.magnitudeDb(5_000.0, rate)) < .02)
        val high = Biquad.of(EqFilter(EqFilterType.HighShelf, 8_000f, 3f, .71f), rate)
        assertEquals(3.0, high.magnitudeDb(20_000.0, rate), .2)
        assertTrue(abs(high.magnitudeDb(200.0, rate)) < .02)
        assertEquals(Biquad.IDENTITY, Biquad.of(EqFilter(EqFilterType.Peak, 1_000f, 0f, 1f), rate))
    }

    // ----------------------------------------------------------------- profile files

    @Test fun anEqualizerApoFileIsReadAsThePhoneReadsIt() {
        val p = HeadphoneProfiles.parse(apo, "Sennheiser HD 600 ParametricEQ.txt", id = "hd600")
        assertEquals("Sennheiser HD 600", p.name)
        assertEquals(-6.4f, p.preampDb, 1e-6f, "a decimal comma is read")
        assertEquals(listOf(EqFilterType.LowShelf, EqFilterType.Peak, EqFilterType.Peak, EqFilterType.HighShelf), p.filters.map { it.type },
            "filters turned off and kinds Ketvoice does not play are left out")
        assertEquals(.71f, p.filters.last().q, 1e-6f, "a shelf without a Q")
        val stored = HeadphoneProfiles.encode(listOf(p))
        assertTrue(HeadphoneProfiles.valid(stored))
        assertEquals(listOf(p), HeadphoneProfiles.decode(stored))
        assertFailsWith<IllegalArgumentException> { HeadphoneProfiles.parse("Preamp: -3 dB", "x") }
        assertTrue(p.safePreampDb() <= -6.4f)
    }

    @Test fun aPickedFileIsImportedAndLinkedToTheDevicePlaying() {
        val file = java.io.File.createTempFile("Sony WH-1000XM4 ParametricEQ", ".txt").apply { writeText(apo); deleteOnExit() }
        DesktopHeadphoneEq.follow("Casque (WH-1000XM4 Stereo)")
        val kept = DesktopHeadphoneEq.import(HeadphoneEqLogic.read(file))
        assertEquals(kept, DesktopHeadphoneEq.status.value.profile, "a first profile goes to the device playing")
        assertTrue(DesktopHeadphoneEq.status.value.applied)
        val big = java.io.File.createTempFile("big", ".txt").apply { writeBytes(ByteArray(70_000) { 'a'.code.toByte() }); deleteOnExit() }
        assertEquals("Fichier trop volumineux", assertFailsWith<IllegalArgumentException> { HeadphoneEqLogic.read(big) }.message)
    }

    // ----------------------------------------------------------------- links

    @Test fun windowsDevicesAreLinkedByADigestThePhoneKeeps() {
        val key = DesktopHeadphoneEq.keyOf("Haut-parleurs (USB Audio DAC)")!!
        assertTrue(key.matches(Regex("btn:[0-9a-f]{16}")), key)
        assertFalse("USB" in key, "the name itself is never kept")
        assertEquals(key, DesktopHeadphoneEq.keyOf(" Haut-parleurs (USB Audio DAC) "), "the same device, the same key")
        assertNull(DesktopHeadphoneEq.keyOf(null), "the system's default cannot be linked")
        val profile = HeadphoneStore.add(HeadphoneProfiles.parse(apo, "DAC"))
        DesktopHeadphoneEq.link("Haut-parleurs (USB Audio DAC)", profile)
        val stored = AppSettings.headphoneLinks.value
        // The phone's own check (backups, sync, its edits) reads the PC's links back whole.
        assertTrue(HeadphoneLinks.valid(stored))
        assertEquals("Haut-parleurs (USB Audio DAC)", HeadphoneLinks.decode(stored).getValue(key).name)
        assertEquals(stored, com.ketvoice.app.data.PersonalBackup.setting("headphoneLinks", stored))
        assertEquals(AppSettings.headphoneProfiles.value, com.ketvoice.app.data.PersonalBackup.setting("headphoneProfiles", AppSettings.headphoneProfiles.value))
        assertEquals(false, com.ketvoice.app.data.PersonalBackup.setting("headphoneEq", false))
        assertNull(com.ketvoice.app.data.PersonalBackup.setting("headphoneLinks", "{\"pc:1\":{\"p\":\"a\",\"n\":\"x\"}}"))
    }

    @Test fun theCorrectionFollowsTheDeviceAndFallsBackWithIt() {
        val profile = HeadphoneStore.add(HeadphoneProfiles.parse(apo, "HD 600"))
        val headset = "Casque (HD 600)"
        DesktopHeadphoneEq.link(headset, profile)
        val links = HeadphoneStore.links(); val profiles = HeadphoneStore.profiles()
        assertEquals(profile, DesktopHeadphoneEq.resolve(headset, true, false, profiles, links).profile)
        assertTrue(DesktopHeadphoneEq.resolve(headset, true, false, profiles, links).applied)
        assertFalse(DesktopHeadphoneEq.resolve(headset, false, false, profiles, links).applied, "switched off")
        assertFalse(DesktopHeadphoneEq.resolve(headset, true, true, profiles, links).applied, "« Écouter sans correction »")
        assertNull(DesktopHeadphoneEq.resolve("Haut-parleurs (Realtek)", true, false, profiles, links).profile, "another device")
        // The headset unplugged: the music goes to the system's default, and the correction with it is gone.
        val devices = listOf("Haut-parleurs (Realtek)", headset)
        assertEquals(headset, DesktopOutput.target(devices, headset))
        assertNull(DesktopOutput.target(listOf("Haut-parleurs (Realtek)"), headset))
        assertNull(DesktopHeadphoneEq.resolve(DesktopOutput.target(listOf("Haut-parleurs (Realtek)"), headset), true, false, profiles, links).profile)
        assertEquals(headset, DesktopOutput.target(devices, headset), "plugged back: the choice holds")
        HeadphoneStore.remove(profile.id)
        assertTrue(HeadphoneStore.links().isEmpty(), "removing a profile removes its links")
    }

    @Test fun theOutputListLeavesTheDefaultsAliasesOut() {
        val names = OutputDevices.names(listOf("Primary Sound Driver" to true, "Haut-parleurs (Realtek(R) Audio)" to true,
            "Port Haut-parleurs (Realtek(R) Audio)" to false, "Casque (WH-1000XM4 Stereo)" to true, "default [default]" to true,
            "Casque (WH-1000XM4 Stereo)" to true, "Micro (USB)" to false))
        assertEquals(listOf("Haut-parleurs (Realtek(R) Audio)", "Casque (WH-1000XM4 Stereo)"), names)
        val items = HeadphoneEqLogic.outputItems(names, "USB DAC") {}
        assertEquals(listOf("output-default", "output:Haut-parleurs (Realtek(R) Audio)", "output:Casque (WH-1000XM4 Stereo)", "output-absent"), items.map { it.tag })
        assertNotNull(items.last().icon, "an unplugged choice stays marked")
        assertNotNull(HeadphoneEqLogic.outputItems(names, null) {}.first().icon)
    }

    @Test fun theRememberedChoiceMovesTheEngineAndComesBackWithItsDevice() {
        val asked = mutableListOf<String?>()
        val sink = SwitchSink()
        val engine = FfmpegAudioEngine(sink)
        try {
            DesktopOutput.attachForTest(engine)
            DesktopOutput.refresh(listOf("A", "B"))
            DesktopOutput.choose("B")
            waitFor { sink.opened.lastOrNull() == "B" }
            DesktopOutput.refresh(listOf("A"))
            waitFor { sink.opened.lastOrNull() == null && sink.opened.size >= 3 }
            assertEquals("B", DesktopOutput.chosen.value, "the choice is remembered while its device is away")
            DesktopOutput.refresh(listOf("A", "B"))
            waitFor { sink.opened.lastOrNull() == "B" }
            waitFor { engine.output.value == "B" }
            asked += sink.opened
        } finally { engine.release(); DesktopOutput.choose(null) }
        assertEquals(listOf(null, "B", null, "B"), asked)
    }

    // ----------------------------------------------------------------- the PCM path

    @Test fun theStagePassesThroughAppliesTheSafePreampAndCrossfades() {
        val stage = HeadphoneStage(rate)
        val block = FloatArray(480 * 2)
        fun run(blocks: Int, hz: Double = 1_000.0, at: IntArray = intArrayOf(0)): FloatArray {
            val out = FloatArray(blocks * 480 * 2)
            repeat(blocks) { b ->
                for (f in 0 until 480) { val v = (.5 * sin(2 * PI * hz * at[0]++ / rate)).toFloat(); block[f * 2] = v; block[f * 2 + 1] = v }
                stage.process(block, 480)
                System.arraycopy(block, 0, out, b * 960, 960)
            }
            return out
        }
        val clock = intArrayOf(0)
        val dry = run(10, at = clock)
        assertEquals((.5 * sin(2 * PI * 1_000 * 100 / rate)).toFloat(), dry[200], "no correction: the sound is untouched")
        // A 9 dB boost at 1 kHz: the preamp lowers it so that nothing passes full scale, and the tone keeps its level.
        stage.profile = HeadphoneProfile("x", "x", 0f, listOf(EqFilter(EqFilterType.Peak, 1_000f, 9f, 1.5f)))
        val fading = run(10, at = clock)
        val wet = run(100, at = clock)
        assertTrue(wet.all { abs(it) <= .5005f }, "peak ${wet.maxOf { abs(it) }}")
        assertEquals(.5 / sqrt(2.0), rms(wet, 48_000), .005)
        // The change crossfades over 60 ms: half-way through, between the dry and the corrected sound, never a step.
        val steps = (1 until fading.size / 2).maxOf { abs(fading[it * 2] - fading[it * 2 - 2]) }
        assertTrue(steps < .1f, "largest step $steps (the tone itself steps 0.065; a cut to the new filters would step 0.2 or more)")
        // A 6 dB cut at 1 kHz plus a 2 dB preamp: -8 dB.
        stage.profile = HeadphoneProfile("y", "y", -2f, listOf(EqFilter(EqFilterType.Peak, 1_000f, -6f, 1f)))
        run(20, at = clock)
        val cut = run(100, at = clock)
        assertEquals(-8.0, 20 * log10(rms(cut, 48_000) / (.5 / sqrt(2.0))), .1)
        stage.profile = null
        run(20, at = clock)
        val back = run(2, at = clock)
        val i = clock[0] - 960
        assertEquals((.5 * sin(2 * PI * 1_000 * i / rate)).toFloat(), back[0], "off again: untouched")
    }

    @Test fun theEnginePlaysTheCorrectionOnTheMixedDecks() {
        val file = AudioFixtures.encode("hp-1k", "flac", AudioFixtures.wav("hp-1k", rate, AudioFixtures.pcm(rate, 1_500, 1_000.0)))
        val sink = CaptureSink()
        val engine = FfmpegAudioEngine(sink)
        val rec = Recorder(engine)
        try {
            engine.setHeadphoneCorrection(HeadphoneProfile("y", "y", -6f, listOf(EqFilter(EqFilterType.Peak, 100f, -3f, 1f))))
            engine.load(AudioSource.LocalFile(file.path))
            rec.await { it is AudioEvent.Ended || it is AudioEvent.Error }
        } finally { rec.stop(); engine.release() }
        val x = left(sink.samples())
        val level = 20 * log10(rmsShort(x, rate / 2, rate) / (.5 * 32768 / sqrt(2.0)))
        assertEquals(-6.0, level, .2, "the preamp is heard (the 100 Hz cut hardly touches 1 kHz)")
    }

    private fun rms(x: FloatArray, from: Int): Double {
        var s = 0.0; var n = 0
        for (i in from until x.size step 2) { s += x[i] * x[i]; n++ }
        return sqrt(s / n)
    }

    private fun rmsShort(x: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun waitFor(timeoutMs: Long = 5_000, test: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) { if (test()) return; Thread.sleep(5) }
        error("not reached in $timeoutMs ms")
    }

    /** A sink with named devices: records what it opened. */
    private class SwitchSink : PcmSink {
        val opened: MutableList<String?> = java.util.Collections.synchronizedList(ArrayList())
        @Volatile private var wanted: String? = null
        @Volatile override var device: String? = null; private set
        override val realtime = false
        override fun select(name: String?): Boolean { wanted = name; return true }
        override fun open(sampleRate: Int, channels: Int) { device = wanted; opened += wanted }
        override fun write(pcm: ByteArray, length: Int) {}
        override fun queuedFrames() = 0
        override fun close() { device = null }
    }
}
