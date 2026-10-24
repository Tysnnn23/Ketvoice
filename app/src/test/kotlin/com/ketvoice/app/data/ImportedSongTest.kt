package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class ImportedSongTest {
    @Test fun onlyCanonicalImportedIdentitiesAreEditable() {
        val id = "11111111-1111-4111-8111-111111111111"
        fun song(value: String) = Track(value,"Owned","QA","")
        assertEquals(id, ImportedSong.id(song("kv:upload:$id")))
        assertNull(ImportedSong.id(song("yt:abcdefghijk")))
        assertNull(ImportedSong.id(song("kv:um:12345")))
        assertNull(ImportedSong.id(song("kv:upload:../../private")))
        assertNull(ImportedSong.id(null))
    }
    @Test fun replacementAudioDoesNotReuseTheOldStreamingCache() {
        val old = ImportedSong.cacheTag("https://example.invalid/track/first/audio.mp3")
        val replacement = ImportedSong.cacheTag("https://example.invalid/track/second/audio.mp3")
        assertNotEquals(old,replacement)
        assertEquals(old,ImportedSong.cacheTag("https://example.invalid/track/first/audio.mp3"))
    }
}
