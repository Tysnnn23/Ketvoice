package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

/** A Canvas given as a link is kept on the device once loaded (6.2.2): the kept file is the same choice as the link. */
class CanvasCacheTest {
    private val link = "https://cdn.example.org/clip.mp4"

    @Test fun theKeptFileFollowsTheLink() {
        assertEquals(CanvasRepo.linkName(link), CanvasRepo.linkName(link))
        assertNotEquals(CanvasRepo.linkName(link), CanvasRepo.linkName("https://cdn.example.org/other.mp4"))
        assertTrue(CanvasRepo.linkName(link).startsWith("kvlink-"))
    }

    @Test fun theKeptFileIsNotReplacedWhileItsLinkIsTheChoice() {
        val kept = "/data/canvas/${CanvasRepo.linkName(link)}.mp4"
        assertFalse(CanvasRepo.givesWay(kept, link, "kvov-ignored"))
        assertFalse(CanvasRepo.givesWay(link, link, "kvov-ignored"))
    }

    @Test fun aNewLinkOrNoChoiceReplacesTheKeptFile() {
        val kept = "/data/canvas/${CanvasRepo.linkName(link)}.mp4"
        assertTrue(CanvasRepo.givesWay(kept, "https://cdn.example.org/new.mp4", "kvov-ignored"))
        assertTrue(CanvasRepo.givesWay(kept, null, null))
        assertTrue(CanvasRepo.givesWay(kept, "https://xodklrunqlzyeiqtovgx.supabase.co/storage/v1/object/public/ketvoice-public-media/a.mp4", "kvov-abc"))
    }
}
