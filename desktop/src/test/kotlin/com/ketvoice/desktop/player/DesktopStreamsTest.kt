package com.ketvoice.desktop.player

import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.StreamResolver
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.audio.AudioSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DesktopStreamsTest {
    private val track = Track("yt:abc", "Titre", "Artiste", "Album", videoId = "abc")
    private val ua = mapOf("User-Agent" to DesktopStreams.USER_AGENT)
    private val savedCandidate = DesktopStreams.candidate
    private val savedDownloaded = DesktopStreams.downloaded
    private val savedFetch = DesktopStreams.fetchText

    @After fun restore() {
        DesktopStreams.candidate = savedCandidate; DesktopStreams.downloaded = savedDownloaded; DesktopStreams.fetchText = savedFetch
    }

    /** A fake API: the sources in order, each offering one stream; excluded sources are skipped. */
    private fun offers(vararg streams: Pair<String, MusicApi.StreamInfo>) {
        DesktopStreams.downloaded = { null }
        DesktopStreams.candidate = { _, excluded ->
            streams.withIndex().firstOrNull { it.value.first !in excluded }?.let { StreamResolver.Candidate(it.value.first, it.index, it.value.second) }
        }
    }

    @Test fun progressiveStreamPlaysAsItIsWithHeaders() = runBlocking {
        offers("youtube" to MusicApi.StreamInfo("https://rr1.googlevideo.com/videoplayback?itag=140", "aac", "130 kbps", "yt:abc:140"))
        assertEquals(AudioSource.Url("https://rr1.googlevideo.com/videoplayback?itag=140", ua), DesktopStreams.source(track))
    }

    @Test fun downloadedFileComesFirst() = runBlocking {
        val file = File.createTempFile("kv-dl", ".m4a").apply { writeBytes(ByteArray(16)); deleteOnExit() }
        offers("youtube" to MusicApi.StreamInfo("https://x.test/a", "aac", "130 kbps"))
        DesktopStreams.downloaded = { file }
        assertEquals(AudioSource.LocalFile(file.path), DesktopStreams.source(track))
    }

    @Test fun dashTakesTheDirectAudioRendition() = runBlocking {
        val mpd = """<?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>https://cdn.test/media/</BaseURL><Period>
              <AdaptationSet mimeType="video/mp4"><Representation bandwidth="900000"><BaseURL>video.mp4</BaseURL></Representation></AdaptationSet>
              <AdaptationSet mimeType="audio/mp4">
                <Representation bandwidth="96000"><BaseURL>low.m4a</BaseURL></Representation>
                <Representation bandwidth="1411000"><BaseURL>lossless.flac</BaseURL><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period></MPD>"""
        offers("cs:x" to MusicApi.StreamInfo("https://src.test/track.mpd", "dash", "FLAC", mimeType = "application/dash+xml"))
        DesktopStreams.fetchText = { if (it == "https://src.test/track.mpd") mpd else null }
        assertEquals(AudioSource.Url("https://cdn.test/media/lossless.flac", ua), DesktopStreams.source(track))
    }

    @Test fun dashWithADownloadAddressUsesIt() = runBlocking {
        offers("cs:x" to MusicApi.StreamInfo("https://src.test/a.mpd", "dash", "FLAC", downloadUrl = "https://src.test/a.flac"))
        assertEquals(AudioSource.Url("https://src.test/a.flac", ua), DesktopStreams.source(track))
    }

    @Test fun segmentedDashGivesWayToTheNextSource() = runBlocking {
        val segmented = """<MPD><Period><AdaptationSet mimeType="audio/mp4"><SegmentTemplate media="${'$'}Number${'$'}.m4s"/>
            <Representation bandwidth="1"/></AdaptationSet></Period></MPD>"""
        offers("cs:x" to MusicApi.StreamInfo("https://src.test/s.mpd", "dash", "FLAC"),
            "youtube" to MusicApi.StreamInfo("https://rr1.googlevideo.com/v?itag=251", "opus", "136 kbps"))
        DesktopStreams.fetchText = { segmented }
        assertEquals(AudioSource.Url("https://rr1.googlevideo.com/v?itag=251", ua), DesktopStreams.source(track))
    }

    @Test fun nothingPlayableIsNull() = runBlocking {
        offers()
        assertNull(DesktopStreams.source(track))
    }
}
