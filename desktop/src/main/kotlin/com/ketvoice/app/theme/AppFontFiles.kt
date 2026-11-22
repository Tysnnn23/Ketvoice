package com.ketvoice.app.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * Desktop counterpart of the app's `theme/AppFontFiles.kt`: the same font files (the app's `res/font`, copied to
 * `font/` on the classpath by the build), read from the classpath. Inter keeps the weights the desktop theme always
 * had (300–800), so the default look is unchanged; the other fonts get every weight, drawn at the nearest end of
 * their axis. A missing file falls back to the system's sans serif.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal fun appFontFamily(choice: AppFonts.Choice): FontFamily {
    val bytes = AppFonts::class.java.classLoader.getResourceAsStream("font/${choice.file}.ttf")?.use { it.readBytes() }
        ?: return FontFamily.SansSerif
    val weights = if (choice.id == AppFonts.INTER) listOf(300, 400, 500, 600, 700, 800) else (100..900 step 100).toList()
    val prefix = if (choice.id == AppFonts.INTER) "Inter" else choice.name.replace(" ", "")
    return FontFamily(weights.map { w ->
        val axis = w.coerceIn(choice.weights.first, choice.weights.last)
        Font("$prefix-$w", bytes, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(axis)))
    })
}
