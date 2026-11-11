package com.ketvoice.app.ui.explorer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryContrastTest {
    @Test fun categorySwatchesAndWhitePhotographsRemainReadableWithWhiteLabels() {
        for (i in 0..100) {
            val background = tintFor("genre:$i", "Genre $i")
            assertTrue("Category $i needs at least 4.5:1 contrast", 1.05f / (background.luminance() + .05f) >= 4.5f)
        }
        val darkestPhotoOverlay = Color.Black.copy(alpha = .62f).compositeOver(Color.White)
        assertTrue(1.05f / (darkestPhotoOverlay.luminance() + .05f) >= 4.5f)
    }
}
