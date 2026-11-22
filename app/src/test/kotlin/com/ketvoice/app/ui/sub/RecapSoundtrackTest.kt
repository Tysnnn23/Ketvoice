package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.player.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** « Bande-son du récap annuel » (8.4): which clip each page plays, from where, the crossfade, and what it never touches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecapSoundtrackTest {
    private fun t(n: Int, artist: String = "Artist $n", album: String = "Album $n") = Track("yt:clip0000000$n", "Song $n", artist, album, 200_000)
    private val recap = Wrapped.Recap("2026", 1200, 300, 40,
        listOf(Wrapped.Artist("Nova", 90, null)),
        listOf(t(1) to 50, t(2, artist = "Nova") to 40, t(3, album = "Sunrise") to 30, t(4) to 20, t(5) to 10),
        albums = listOf(Wrapped.Album("Sunrise", "Artist 3", 30, null), Wrapped.Album("Other", "X", 5, null)),
        newArtists = listOf("Artist 4"), ketmixMixes = 3)

    @Test fun eachPageHasItsTitleAndTheStoryIsAMedley() {
        val pages = listOf(WrappedPage.Intro, WrappedPage.Minutes, WrappedPage.Artist, WrappedPage.Tracks, WrappedPage.Albums,
            WrappedPage.Discoveries, WrappedPage.Ketmix, WrappedPage.Summary)
        val clips = RecapClips.forPages(pages, recap).map { it?.id }
        assertEquals(t(1).id, clips[0])                 // the most played title opens…
        assertEquals(t(2).id, clips[1])                 // …the next ones follow in turn
        assertEquals(t(2).id, clips[2])                 // the top artist's most played title
        assertEquals(t(1).id, clips[3])
        assertEquals(t(3).id, clips[4])                 // the top album's title
        assertEquals(t(4).id, clips[5])                 // a discovery
        assertEquals(t(1).id, clips[7])                 // …and closes the story
        assertTrue(RecapClips.forPages(pages, recap.copy(tracks = emptyList())).all { it == null })
    }

    private fun line(ms: Long, text: String) = MusicApi.LyricLine(ms, text)

    @Test fun theBestPartIsTheChorusNearTheMiddleOrAThirdIn() {
        val lines = listOf(line(10_000, "First verse goes here"), line(40_000, "This is the chorus line"),
            line(60_000, "Second verse goes on"), line(85_000, "This is the chorus line"), line(150_000, "This is the chorus line"))
        // 40 % of 200 s is 80 s: the chorus at 85 s, a second before it.
        assertEquals(84_000L, RecapClips.startMs(200_000, lines))
        assertEquals(66_000L, RecapClips.startMs(200_000, null))
        assertEquals(66_000L, RecapClips.startMs(200_000, lines.take(2)))
        // A whole clip always fits before the end; a very short title starts at its beginning.
        assertEquals(0L, RecapClips.startMs(5_000, null))
        assertTrue(RecapClips.startMs(20_000, null) + RecapClips.CLIP_MS <= 20_000)
        assertEquals(45_000L, RecapClips.startMs(0, null))
    }

    @Test fun theCrossfadeKeepsTheLoudness() {
        assertEquals(1f to 0f, RecapClips.crossfade(0))
        val (out, into) = RecapClips.crossfade(RecapClips.FADE_MS / 2)
        assertEquals(1f, out * out + into * into, 1e-4f)
        assertEquals(0f, RecapClips.crossfade(RecapClips.FADE_MS).first, 1e-4f)
        assertEquals(1f, RecapClips.crossfade(99_999).second, 1e-4f)
    }

    private class FakeDeck(val name: String, val log: MutableList<String>) : ClipDeck {
        var volume = 0f
        var playing: String? = null
        override fun play(track: Track, startMs: Long, volume: Float) { playing = track.id; this.volume = volume; log += "$name play ${track.id} @$startMs v$volume" }
        override fun volume(value: Float) { volume = value }
        override fun stop() { if (playing != null) log += "$name stop"; playing = null }
        override fun release() { log += "$name release" }
    }

    private class FakeListener(override var playing: Boolean) : ListenerPlayback {
        var pauses = 0; var resumes = 0
        override fun pause() { pauses++; playing = false }
        override fun resume() { resumes++; playing = true }
    }

    @Test fun clipsCrossfadePageToPageAndTheListenersMusicComesBack() = runBlocking {
        val log = mutableListOf<String>()
        val decks = mutableListOf<FakeDeck>()
        val listener = FakeListener(playing = true)
        val historyBefore = ListeningHistory.playCounts.value
        val queueBefore = Player.state.value.queue
        val clips = listOf(t(1), t(2), t(2), t(3))
        val sound = RecapSoundtrack(this, clips, listener, { FakeDeck("d${decks.size}", log).also { decks += it } }, { 30_000L }, step = 5, fadeMs = 40)
        sound.open(0, muted = false)
        delay(30)
        assertEquals(1, listener.pauses)
        assertEquals(t(1).id, decks[0].playing)
        assertEquals(1f, decks[0].volume)
        sound.show(1)
        delay(120)
        // The next page's clip came in on the other deck and the first one left.
        assertEquals(t(2).id, decks[1].playing)
        assertEquals(1f, decks[1].volume)
        assertNull(decks[0].playing)
        sound.show(2)          // the same title: it goes on
        delay(30)
        assertEquals(2, log.count { "play" in it })
        sound.mute(true)
        assertTrue(decks.all { it.playing == null })
        assertEquals(1, listener.resumes)
        sound.mute(false)
        delay(30)
        assertEquals(2, listener.pauses)
        assertEquals(t(2).id, decks.first { it.playing != null }.playing)
        sound.close()
        assertEquals(2, listener.resumes)
        assertEquals(2, log.count { "release" in it })
        // Nothing reached the listening history or the queue.
        assertEquals(historyBefore, ListeningHistory.playCounts.value)
        assertEquals(queueBefore, Player.state.value.queue)
    }

    @Test fun musicThatWasNotPlayingIsNotStartedAndAMutedStoryStaysSilent() = runBlocking {
        val decks = mutableListOf<FakeDeck>()
        val listener = FakeListener(playing = false)
        val sound = RecapSoundtrack(this, listOf(t(1), t(2)), listener, { FakeDeck("d", mutableListOf()).also { decks += it } }, { 0L }, step = 5, fadeMs = 20)
        sound.open(0, muted = true)
        sound.show(1)
        delay(30)
        assertTrue(decks.isEmpty())
        sound.mute(false)
        delay(30)
        assertEquals(t(2).id, decks.single().playing)
        sound.close()
        assertEquals(0, listener.resumes)
        assertEquals(0, listener.pauses)
    }
}
