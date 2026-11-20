package com.ketvoice.app.ui.library

import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.AudioGrade
import com.ketvoice.app.ui.common.AudioGrades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionCoversTest {
    private fun t(id: String, artist: String, album: String, cover: String?) = Track(id, id, artist, album, coverUrl = cover)

    @Test fun theMosaicSkipsRepeatedPicturesAndAlbums() {
        val tracks = listOf(
            t("1", "Ayla", "Lumen", "https://lh3.googleusercontent.com/abc=w544-h544"),
            t("2", "Ayla", "Lumen", "https://other/cover.jpg"),          // same album
            t("3", "Noé", "Route", "https://lh3.googleusercontent.com/abc=w120-h120"), // same picture, another size
            t("4", "Noé", "Route", null),
            t("5", "Iris", "", "https://x/a.jpg?size=1"),
            t("6", "Oslo", "", "https://x/a.jpg?size=1"),                // same picture
            t("7", "Mira", "Côtes", "https://x/b.jpg"),
            t("8", "Sol", "Ciel", "https://x/c.jpg"),
            t("9", "Lia", "Mer", "https://x/d.jpg"))
        assertEquals(listOf("https://lh3.googleusercontent.com/abc=w544-h544", "https://x/a.jpg?size=1", "https://x/b.jpg", "https://x/c.jpg"),
            CoverMosaic.distinct(tracks, { it.coverUrl }))
        assertEquals(1, CoverMosaic.distinct(tracks.take(3), { it.coverUrl }).size)
        assertEquals(emptyList<String>(), CoverMosaic.distinct(listOf(t("x", "A", "B", " ")), { it.coverUrl }))
    }

    @Test fun gradesComeFromTheDecoderOrTheFlacHeader() {
        assertEquals(AudioGrade.Lossless, AudioGrades.ofDecoded("audio/flac", 44_100))
        assertEquals(AudioGrade.Lossless, AudioGrades.ofDecoded("audio/flac", 48_000))
        assertEquals(AudioGrade.HiRes, AudioGrades.ofDecoded("audio/flac", 96_000))
        assertNull(AudioGrades.ofDecoded("audio/mp4a-latm", 96_000))
        assertNull(AudioGrades.ofDecoded(null, 44_100))
        fun head(rate: Int) = ByteArray(42).also {
            "fLaC".toByteArray().copyInto(it); it[7] = 34
            it[18] = (rate shr 12).toByte(); it[19] = (rate shr 4).toByte(); it[20] = ((rate and 0xF) shl 4).toByte()
        }
        assertEquals(44_100, AudioGrades.flacSampleRate(head(44_100)))
        assertEquals(192_000, AudioGrades.flacSampleRate(head(192_000)))
        assertNull(AudioGrades.flacSampleRate("ID3 not a flac file at all".toByteArray()))
    }

    @Test fun favouritesSortByLikeOrderTitleOrArtist() {
        val a = t("a", "Zed", "", null).copy(title = "Bravo")
        val b = t("b", "Abel", "", null).copy(title = "Alpha")
        val c = t("c", "Moe", "", null).copy(title = "Charlie")
        val liked = listOf("b", "c", "a")
        assertEquals(listOf("a", "c", "b"), LikedOrder.arrange(listOf(a, b, c), liked, "", LikedOrder.default).map { it.id })
        assertEquals(listOf("b", "a", "c"), LikedOrder.arrange(listOf(a, b, c), liked, "", com.ketvoice.app.data.ListOrder(com.ketvoice.app.data.ListSort.Title)).map { it.id })
        assertEquals(listOf("b", "c", "a"), LikedOrder.arrange(listOf(a, b, c), liked, "", com.ketvoice.app.data.ListOrder(com.ketvoice.app.data.ListSort.Artist)).map { it.id })
        assertEquals(listOf("c"), LikedOrder.arrange(listOf(a, b, c), liked, "moe", LikedOrder.default).map { it.id })
    }
}
