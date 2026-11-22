package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.ui.player.LyricsEditor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** « Éditeur de paroles » (9.0): what the editor saves is the title's own lyrics file, read before any online source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsEditorSaveTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val track = Track("qa:lyrics-editor", "Titre QA", "Artiste QA", "Album QA")

    @After fun forget() { CustomLyrics.forget(track.id); LyricsRepo.clear() }

    @Test fun savedLyricsBecomeTheTitlesOwnAndComeFirst() {
        CustomLyrics.init(context)
        var draft = LyricsDraft.fromText("first line here\nsecond line").withMode(TapMode.Word)
        listOf(1_000L, 1_400L, 1_800L).forEach { draft = draft.tap(it) }
        draft = draft.withMode(TapMode.Line).tap(4_000)
        val text = LyricsEditor.fileText(draft, track)
        assertTrue("word times are kept as enhanced LRC", "<00:01.40>" in text)
        val entry = CustomLyrics.saveText(context, track.id, "Titre QA.lrc", text)
        assertEquals("lrc", entry.format)
        assertTrue(entry.active)
        val (kept, lines) = CustomLyrics.lines(track.id)!!
        assertEquals("Titre QA.lrc", kept.name)
        assertEquals(listOf(1_000L, 4_000L), lines.map { it.timeMs })
        assertEquals(listOf(1_000L, 1_400L, 1_800L), lines[0].words.map { it.startMs })

        LyricsRepo.load(track, force = true)
        val state = LyricsRepo.state.value
        assertEquals(LyricsRepo.SOURCE_CUSTOM, state.sourceKey)
        assertTrue(state.wordTimed)
        assertEquals("first line here", state.lines[0].text)

        // Saved again: the new file replaces the old one.
        CustomLyrics.saveText(context, track.id, "Titre QA.lrc", LyricsExport.lrc(draft.nudge(100)))
        assertEquals(listOf(1_000L, 4_100L), CustomLyrics.lines(track.id)!!.second.map { it.timeMs })
        assertEquals(1, CustomLyrics.folder(context).listFiles().orEmpty().count { it.name.startsWith(track.id.hashCode().toUInt().toString(16)) })
    }

    @Test fun nothingTimedIsNotSaved() {
        CustomLyrics.init(context)
        val error = runCatching { CustomLyrics.saveText(context, track.id, "x.lrc", LyricsExport.lrc(LyricsDraft.fromText("untimed"))) }.exceptionOrNull()
        assertEquals("Aucune parole synchronisée à enregistrer", error?.message)
        assertNull(CustomLyrics.entry(track.id))
    }
}
