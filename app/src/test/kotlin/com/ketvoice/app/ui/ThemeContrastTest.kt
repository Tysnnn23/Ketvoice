package com.ketvoice.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ketvoice.app.theme.Palette
import org.junit.Assert.*
import org.junit.Test

class ThemeContrastTest {
    private fun contrast(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + .05f) /
        (minOf(a.luminance(), b.luminance()) + .05f)
    @Test fun darkPrimaryAndSecondaryTextAreReadableOnBothSurfaces() {
        listOf(Palette.BackgroundDark, Palette.CardDark).forEach { surface ->
            assertTrue(contrast(Color.White, surface) >= 7f)
            assertTrue(contrast(Palette.SecondaryTextDark, surface) >= 4.5f)
        }
    }
}
