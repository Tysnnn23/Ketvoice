package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class StudioImageLinkTest {
    private fun bytes(vararg values: Int) = ByteArray(16) { values.getOrElse(it) { 0 }.toByte() }
    private fun ascii(text: String, at: Int = 0) = ByteArray(16).also { text.forEachIndexed { i, c -> it[at + i] = c.code.toByte() } }

    @Test fun onlyImagesAreAcceptedAsLinks() {
        assertTrue("JPEG", StudioImageLink.isImage(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertTrue("PNG", StudioImageLink.isImage(bytes(0x89, 'P'.code, 'N'.code, 'G'.code)))
        assertTrue("GIF", StudioImageLink.isImage(ascii("GIF89a")))
        val webp = ascii("RIFF").also { "WEBP".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() } }
        assertTrue("WebP", StudioImageLink.isImage(webp))
        val avif = ascii("ftyp", 4).also { "avif".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() } }
        assertTrue("AVIF", StudioImageLink.isImage(avif))
        val mp4 = ascii("ftyp", 4).also { "isom".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() } }
        assertFalse("a video is not an image", StudioImageLink.isImage(mp4))
        assertFalse("a page is not an image", StudioImageLink.isImage(ascii("<!doctype html>")))
    }
}
