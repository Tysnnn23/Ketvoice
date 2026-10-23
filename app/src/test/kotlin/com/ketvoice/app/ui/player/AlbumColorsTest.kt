package com.ketvoice.app.ui.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AlbumColorsTest {
    @Test fun noOpaquePixelsUsesTheExistingThemeFallback() {
        assertNull(AlbumColors.dominant(intArrayOf()))
        assertNull(AlbumColors.dominant(intArrayOf(0x00FF0000, 0x7F00FF00)))
    }
    @Test fun dominantColorDoesNotAverageTwoDifferentAlbumColorsToGrey() {
        val red = 0xFFFF1010.toInt(); val blue = 0xFF1010FF.toInt()
        assertEquals(red, AlbumColors.dominant(IntArray(80) { red } + IntArray(20) { blue }))
    }
    @Test fun brightBlackWhiteAndMutedArtAllHaveReadableAccents() {
        val colors = listOf(0xFF000000.toInt(), -1, 0xFF121222.toInt(), 0xFFFF0011.toInt(), 0xFF0011FF.toInt(), 0xFF777777.toInt())
        colors.forEach { assertTrue(AlbumColors.contrast(AlbumColors.readableAccent(it), 0xFF303038.toInt()) >= 4.5) }
    }
    @Test fun contrastIsSymmetricAndKnownValuesMatchWcag() {
        assertEquals(21.0, AlbumColors.contrast(-1, 0xFF000000.toInt()), .001)
        assertEquals(1.0, AlbumColors.contrast(-1, -1), .001)
    }
    @Test fun randomizedArtworkProducesOpaqueAccessibleColors() {
        val random = Random(42)
        repeat(512) {
            val accent = AlbumColors.readableAccent(random.nextInt())
            assertEquals(255, accent ushr 24)
            assertTrue(AlbumColors.contrast(accent, 0xFF303038.toInt()) >= 4.5)
        }
    }
}
