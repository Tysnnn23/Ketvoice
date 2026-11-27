package com.ketvoice.app.tv

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import com.ketvoice.app.player.AutoLibrary
import com.ketvoice.app.player.AutoRequest
import com.ketvoice.app.player.FakeAutoPlayback
import com.ketvoice.app.player.FakeAutoSource
import com.ketvoice.app.player.VoiceAsk
import com.ketvoice.app.player.VoiceMatch
import com.ketvoice.app.player.track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * « Ok Google, joue X sur Ketvoice » on the TV (1.1.0): the assistant's intent and the media session's play/prepare
 * from search both end in [AutoLibrary.voice] with the TV's rule (an artist starts the artist's radio). The phone's
 * rule (the artist's titles) is checked to stay as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvVoiceSearchTest {
    private val source = FakeAutoSource()
    private val playback = FakeAutoPlayback()
    private val tv = AutoLibrary(source, playback, searchTimeoutMs = 300, mixTimeoutMs = 300, artistRadio = true)
    private val phone = AutoLibrary(source, playback, searchTimeoutMs = 300, mixTimeoutMs = 300)

    private val adele = listOf(track("c1", "Someone like you", "Adele"), track("c2", "Hello", "Adele"), track("c3", "Hello", "Lionel Richie"),
        track("c4", "Skyfall", "Adele"))

    private fun focus(kind: String, vararg extras: Pair<String, String>) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, kind)
        extras.forEach { (k, v) -> putString(k, v) }
    }

    @Test fun aTitleIsPlayedBestMatchFirst() = runBlocking {
        source.catalogue = { adele }
        val request = tv.voice("hello adele", null) as AutoRequest.Play
        assertEquals("c2", request.tracks.first().id)
        val titled = tv.voice("hello", focus(VoiceAsk.TITLE, MediaStore.EXTRA_MEDIA_TITLE to "Hello")) as AutoRequest.Play
        assertEquals(1, titled.tracks.size)
    }

    @Test fun anArtistStartsTheArtistsRadioOnTheTv() = runBlocking {
        source.catalogue = { adele }
        val focused = tv.voice("Adele", focus(VoiceAsk.ARTIST, MediaStore.EXTRA_MEDIA_ARTIST to "Adele")) as AutoRequest.ArtistRadio
        assertEquals("Adele", focused.name)
        assertTrue(focused.tracks.all { it.artist == "Adele" })
        // Without the assistant's focus too, when the words are an artist's name.
        assertTrue(tv.voice("adele", null) is AutoRequest.ArtistRadio)
        tv.perform(tv.voice("adele", null))
        assertEquals("Adele", playback.artistRadios.last().first)
        // The phone keeps its rule: the artist's titles.
        val onPhone = phone.voice("Adele", focus(VoiceAsk.ARTIST, MediaStore.EXTRA_MEDIA_ARTIST to "Adele")) as AutoRequest.Play
        assertEquals(setOf("c1", "c2", "c4"), onPhone.tracks.map { it.id }.toSet())
        assertTrue(phone.voice("adele", null) is AutoRequest.Play)
    }

    @Test fun albumGenreEmptyAndTheSessionsSearchPath() = runBlocking {
        source.catalogue = { q -> if (q.contains("pop", true)) adele.take(2) else adele.map { it.copy(album = if (it.id == "c4") "Skyfall" else "25") } }
        val album = tv.voice("25 adele", focus(VoiceAsk.ALBUM, MediaStore.EXTRA_MEDIA_ALBUM to "25")) as AutoRequest.Play
        assertTrue(album.tracks.none { it.id == "c4" })
        assertTrue(tv.voice("pop", focus(VoiceAsk.GENRE, MediaStore.EXTRA_MEDIA_GENRE to "pop")) is AutoRequest.Shuffle)
        // « Joue de la musique sur Ketvoice »: what was playing.
        assertEquals(AutoRequest.Resume, tv.voice("", null))
        assertEquals(AutoRequest.Resume, tv.voice("de la musique", null))
        // The media session's onPlayFromSearch / onPrepareFromSearch: Media3 hands an item without id carrying the query.
        val item = MediaItem.Builder().setMediaId("")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("adele").build()).build()
        assertTrue(tv.request(listOf(item), 0) is AutoRequest.ArtistRadio)
        source.catalogue = { emptyList() }
        assertEquals("Aucun résultat pour « inconnu »", tv.perform(tv.voice("inconnu", null)))
    }

    @Test fun whatCountsAsAskingForAnArtist() {
        val ranked = adele
        assertTrue(VoiceMatch.asksArtist(VoiceAsk.of("adele", null), ranked))
        // A title's exact name, a narrower field, or an artist not among the results is not an artist's request.
        assertFalse(VoiceMatch.asksArtist(VoiceAsk.of("hello", null), ranked))
        assertFalse(VoiceMatch.asksArtist(VoiceAsk.of("hello", focus(VoiceAsk.TITLE, MediaStore.EXTRA_MEDIA_TITLE to "Hello")), ranked))
        assertFalse(VoiceMatch.asksArtist(VoiceAsk.of("adele", focus(VoiceAsk.ALBUM, MediaStore.EXTRA_MEDIA_ALBUM to "25")), ranked))
        assertFalse(VoiceMatch.asksArtist(VoiceAsk.of("Nova Lane", focus(VoiceAsk.ARTIST, MediaStore.EXTRA_MEDIA_ARTIST to "Nova Lane")), ranked))
        assertTrue(VoiceMatch.asksArtist(VoiceAsk.of("", focus(VoiceAsk.ARTIST, MediaStore.EXTRA_MEDIA_ARTIST to "Adele")), ranked))
    }
}
