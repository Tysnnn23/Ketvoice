package com.ketvoice.desktop.lyrics

import com.ketvoice.app.data.LyricsRank
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsEngineTest {
    private val plain = listOf(MusicApi.LyricLine(-1, "one two"), MusicApi.LyricLine(-1, "three four"))
    private val line = listOf(MusicApi.LyricLine(1_000, "one two"), MusicApi.LyricLine(3_000, "three four"))
    private val word = line.map { it.copy(words = listOf(MusicApi.LyricWord(it.timeMs, "one "), MusicApi.LyricWord(it.timeMs + 400, "two"))) }
    private val syllable = word.map { it.copy(syllableTimed = true) }
    private val track = Track("t1", "Song", "Artist", "")

    @AfterTest fun reset() { LyricsEngine.resetForTest(); AiLyrics.resetForTest() }

    @Test fun richerTimingWins() {
        assertEquals(LyricsRank.WORD, LyricsRank.level(word))
        assertEquals(LyricsRank.SYLLABLE, LyricsRank.level(syllable))
        assertEquals(LyricsRank.AI_WORD, LyricsRank.level(word, ai = true))
        assertEquals(LyricsRank.LINE, LyricsRank.level(line))
        assertEquals(LyricsRank.PLAIN, LyricsRank.level(plain))
        assertTrue(LyricsRank.WORD > LyricsRank.SYLLABLE && LyricsRank.SYLLABLE > LyricsRank.AI_WORD && LyricsRank.AI_WORD > LyricsRank.LINE)
        val c = listOf(LyricsRank.Candidate("a", "A", plain, 0), LyricsRank.Candidate("b", "B", line, 1),
            LyricsRank.Candidate("c", "C", syllable, 2), LyricsRank.Candidate("d", "D", word, 3))
        assertEquals("d", LyricsRank.best(c)?.source)
        assertEquals("b", LyricsRank.best(c, chosen = "b")?.source)
        // A choice that has nothing this time does not leave the lyrics empty.
        assertEquals("d", LyricsRank.best(c + LyricsRank.Candidate("e", "E", emptyList(), 4), chosen = "e")?.source)
    }

    @Test fun atTheSameLevelTheEarlierSourceWinsUnlessItIsAFragment() {
        val long = (0 until 20).map { MusicApi.LyricLine(it * 1_000L, "line $it") }
        val a = LyricsRank.Candidate("a", "A", long.take(15), 0)
        val b = LyricsRank.Candidate("b", "B", long, 1)
        assertEquals("a", LyricsRank.best(listOf(a, b))?.source)
        val fragment = LyricsRank.Candidate("a", "A", long.take(5), 0)
        assertEquals("b", LyricsRank.best(listOf(fragment, b))?.source)
        assertNull(LyricsRank.best(emptyList()))
    }

    @Test fun theChoicePerTitleIsKeptAndReadBack() {
        LyricsEngine.choose("t1", "lrclib")
        LyricsEngine.choose("t2", "netease")
        assertEquals("lrclib", LyricsEngine.pinned("t1"))
        LyricsEngine.choose("t1", null)
        assertNull(LyricsEngine.pinned("t1"))
        val text = LyricsEngine.encode(mapOf("t2" to "netease", "t3" to "x:/bad value"))
        assertEquals(mapOf("t2" to "netease"), LyricsEngine.decode(text))
        assertEquals(emptyMap(), LyricsEngine.decode("not json"))
    }

    @Test fun answersAreCollectedPerTitle() {
        LyricsEngine.answered(track, "lrclib", "LRCLIB", line, 6)
        LyricsEngine.answered(track, "ketvoice", "Ketvoice API", word, 0)
        LyricsEngine.answered(track, "lrclib", "LRCLIB", line, 6)
        assertEquals(listOf("ketvoice", "lrclib"), LyricsEngine.answers.value!!.candidates.map { it.source })
        LyricsEngine.answered(track.copy(id = "t2"), "lrclib", "LRCLIB", line, 6)
        assertEquals("t2", LyricsEngine.answers.value!!.trackId)
        assertEquals(1, LyricsEngine.answers.value!!.candidates.size)
    }

    @Test fun aiTimingReplacesLineTimingOnlyForTheSameLinesAndUntilReverted() {
        AiLyrics.init(Files.createTempDirectory("ai-lyrics").toFile())
        val state = LyricsRepo.State(source = "LRCLIB", lines = line, trackId = "t1")
        assertEquals(state, LyricsEngine.withAi("t1", state))
        val basis = AiLyrics.basis(line)
        AiLyrics.save("t1", basis, word, "Song", "Artist")
        val shown = LyricsEngine.withAi("t1", state)
        assertEquals("LRCLIB · ${AiLyrics.LABEL}", shown.source)
        assertTrue(shown.wordTimed)
        assertEquals(listOf(1_000L, 1_400L), shown.lines[0].words.map { it.startMs })
        // Other lines (another source): not swapped. Word-timed lyrics are never replaced.
        val other = state.copy(lines = line.map { it.copy(timeMs = it.timeMs + 50) })
        assertEquals(other, LyricsEngine.withAi("t1", other))
        assertEquals(state.copy(lines = word), LyricsEngine.withAi("t1", state.copy(lines = word)))
        AiLyrics.revert("t1")
        assertEquals(state, LyricsEngine.withAi("t1", state))
        AiLyrics.revert("t1", back = false)
        assertTrue(LyricsEngine.withAi("t1", state).wordTimed)
        // Kept across restarts (the index and the enhanced LRC on disk).
        val dir = File(Files.createTempDirectory("ai-lyrics-2").toFile(), "x")
        AiLyrics.init(dir); AiLyrics.save("t9", "b", word); AiLyrics.resetForTest(); AiLyrics.init(dir)
        assertEquals(2, AiLyrics.lines("t9", "b")?.size)
        assertTrue(AiLyrics.tried("t9", "b"))
    }

    @Test fun theSyncRunsOnLineTimedLyricsAndRemembersFailures() = runBlocking {
        AiLyrics.init(Files.createTempDirectory("ai-sync").toFile())
        val eligible = AiSync.eligible; val ready = AiSync.modelReady; val open = AiSync.openModel; val voiceOf = AiSync.voiceOf
        try {
            AiSync.eligible = { true }; AiSync.modelReady = { true }
            val loaded = LyricsRepo.State(source = "LRCLIB", lines = line, trackId = "t1")
            assertTrue(AiSync.wanted(track, loaded))
            assertFalse(AiSync.wanted(track, loaded.copy(lines = word)), "already word-timed")
            assertFalse(AiSync.wanted(track, loaded.copy(sourceKey = LyricsRepo.SOURCE_CUSTOM)), "the listener's own lyrics")
            AiSync.eligible = { false }
            assertFalse(AiSync.wanted(track, loaded), "a laptop or Équilibré")
            AiSync.eligible = { true }
            AiSync.voiceOf = { FloatArray(AlignMath.RATE * 6) }
            AiSync.openModel = { null }
            assertFalse(AiSync.run(track, line))
            assertTrue(AiLyrics.tried("t1", AiLyrics.basis(line)), "a failure is not tried again")
            assertFalse(AiSync.wanted(track, loaded))
        } finally { AiSync.eligible = eligible; AiSync.modelReady = ready; AiSync.openModel = open; AiSync.voiceOf = voiceOf }
    }

    @Test fun theModelIsPinnedAsTheWorkflowPublishesIt() {
        val pins = File("../tools/models/expected-align.sha256").readLines().filter { it.isNotBlank() }
            .associate { l -> l.split(Regex("\\s+")).let { it[1] to it[0] } }
        assertEquals(3, AlignModels.pack.parts.size)
        for (part in AlignModels.pack.parts) assertEquals(pins[part.name], part.sha256, part.name)
        val workflow = File("../.github/workflows/publish-ketvoice-align.yml").readText()
        assertTrue("TAG: ${AlignModels.TAG}" in workflow && AlignModels.TAG != "ketmix-pc-v1")
        for (part in AlignModels.pack.parts) assertTrue(part.name in workflow, part.name)
        assertNotNull(File("../docs/licenses/ketvoice-align.md").takeIf { it.isFile })
    }
}
