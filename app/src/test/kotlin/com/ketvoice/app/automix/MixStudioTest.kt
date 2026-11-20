package com.ketvoice.app.automix

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Ketmix Studio (8.0): undo/redo, the magnet, the listener's presets and the share code. */
class MixStudioTest {
    @Before fun stores() { MixPresetStore.use(MapPointsBackend()) }
    @After fun forget() { MixPresetStore.use(null) }

    @Test fun undoAndRedoWalkTheStatesAndCoalesceQuickChanges() {
        var now = 0L
        val history = EditHistory<Int>(limit = 3, coalesceMs = 600, clock = { now })
        var state = 0
        fun set(v: Int) { history.record(state); state = v }
        set(1); now += 1_000; set(2); now += 1_000; set(3)
        assertTrue(history.canUndo); assertFalse(history.canRedo)
        state = history.undo(state)!!; assertEquals(2, state)
        state = history.undo(state)!!; assertEquals(1, state)
        state = history.redo(state)!!; assertEquals(2, state)
        // A new change after an undo drops what could be redone.
        now += 1_000; set(9)
        assertFalse(history.canRedo)
        // A slider: many values within the window are one step back.
        now += 1_000; set(10); now += 100; set(11); now += 100; set(12)
        assertEquals(9, history.undo(state))
        // Bounded: at most [limit] steps back.
        val bounded = EditHistory<Int>(limit = 3, coalesceMs = 0, clock = { now++ * 10 })
        var s = 0
        repeat(10) { bounded.record(s); s++ }
        var steps = 0
        while (true) { s = bounded.undo(s) ?: break; steps++ }
        assertEquals(3, steps)
    }

    private val grid = SegmentAnalysis(155_000, 45_000, 120f, 155_000.0, .9f, 0, -11f, 155_000, 196_000, 155_000, 186_000,
        phraseAtMs = 155_000.0, key = 7, keyStrength = .7f)

    @Test fun keyframesSnapToTheOutgoingGridInsideTheBlend() {
        // 120 BPM: a beat every 500 ms; the blend starts at 160 000 (on a beat), 8 s long.
        assertEquals(1_500L, StudioSnap.key(grid, 160_000L, 1_430L, 8_000L, 125L))
        assertEquals(2_000L, StudioSnap.key(grid, 160_000L, 2_100L, 8_000L, 125L))
        // Never out of the blend, and as placed without a grid.
        assertEquals(8_000L, StudioSnap.key(grid, 160_000L, 9_000L, 8_000L, 125L))
        assertEquals(1_234L, StudioSnap.key(null, 160_000L, 1_234L, 8_000L, 125L))
        // A blend that starts off the beat: the keyframe still lands on a beat of the title.
        val rel = StudioSnap.key(grid, 160_210L, 1_000L, 8_000L, 125L)
        assertEquals(0L, (160_210L + rel - 155_000L) % 500L)
    }

    @Test fun presetsRoundTripWithKeyframesRelativeToTheBlend() {
        val keys = listOf(LevelKey(2_000, out = Levels(voice = 20)), LevelKey(6_000, into = Levels(music = 60)))
        val choice = PairChoice(MixStyle.values()[2], 8_000, MixShape.of(5)!!, keys)
        val saved = MixPresetStore.save(MixPreset.of("", "  Mon\tfondu\n ", choice, blendMs = 8_000))!!
        assertEquals("Monfondu", saved.name)
        assertEquals(listOf(250L, 750L), saved.keys.map { it.atMs })
        val back = MixPresetStore.all().single()
        assertEquals(saved, back)
        // Applied to a blend twice as long: the keyframes stand at the same places of it.
        assertEquals(listOf(4_000L, 12_000L), back.keysFor(16_000).map { it.atMs })
        assertEquals(Levels(voice = 20), back.keysFor(16_000)[0].out)
        // Renamed, deleted; a second one gets its own id.
        MixPresetStore.rename(saved.id, "Doux")
        val second = MixPresetStore.save(MixPreset.of("", "Coupe", PairChoice(), 0))!!
        assertNotEquals(saved.id, second.id)
        assertEquals(listOf("Doux", "Coupe"), MixPresetStore.all().map { it.name })
        MixPresetStore.delete(saved.id)
        assertEquals(listOf("Coupe"), MixPresetStore.all().map { it.name })
        // A value of another format version, or broken, is left aside.
        assertNull(MixPresetStore.decode("u:9", "2\tX\t3,8000"))
        assertNull(MixPresetStore.decode("u:9", "1\tX\tnope"))
        assertNull(MixPresetStore.save(MixPreset.of("", "   ", choice, 8_000)))
    }

    @Test fun theMagnetAndTheHintAreRemembered() {
        assertTrue(MixPresetStore.magnet)
        MixPresetStore.magnet = false
        assertFalse(MixPresetStore.magnet)
        assertFalse(MixPresetStore.hintSeen)
        MixPresetStore.hintSeen = true
        assertTrue(MixPresetStore.hintSeen)
        // Not mistaken for presets.
        assertTrue(MixPresetStore.all().isEmpty())
    }

    @Test fun aSharedMixRoundTripsAndIsFoundInAMessage() {
        val keys = listOf(LevelKey(1_000, out = Levels(10, 20, 30), into = Levels(40, 50, 60)))
        val shared = MixShare.Shared(MixShare.End("yt:abc", "Nuit « blanche »", "Lumen"), MixShare.End("dz:42", "Sous la pluie", "Aster"),
            181_500, 12_000, PairChoice(MixStyle.values()[3], 9_000, MixShape.DEFAULT, keys))
        val code = MixShare.encode(shared)
        assertTrue(code.startsWith(MixShare.PREFIX))
        assertTrue(code.length < 600)
        assertEquals(shared, MixShare.find("Mon mix :\n$code\nà bientôt"))
        // Ketmix's own transition with only points chosen.
        val plain = shared.copy(choice = PairChoice())
        assertEquals(plain, MixShare.find(MixShare.encode(plain)))
        // Not a code, or tampered with.
        assertNull(MixShare.find("bonjour"))
        assertNull(MixShare.find(MixShare.PREFIX + "AAAAAAAAAAAA"))
        val forged = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"v":1,"from":{"id":"a"},"to":{"id":"b"},"exit":-1,"entry":-1,"mix":"99,1"}""".toByteArray())
        assertNull(MixShare.find(MixShare.PREFIX + forged))
    }

    @Test fun pairsSavedBeforeStayReadable() {
        // 6.1, 6.9 and 7.6 formats are untouched by the Studio.
        assertEquals(PairChoice(MixStyle.values()[1], 8_000), MixPointStore.decodePair("1,8000"))
        assertEquals(MixShape.of(5), MixPointStore.decodePair("1,8000,5")?.shape)
        assertEquals(1, MixPointStore.decodePair("1,8000|v2|1000,10,20,30,40,50,60")?.keys?.size)
    }
}
