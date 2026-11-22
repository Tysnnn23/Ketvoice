package com.ketvoice.app.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.ketvoice.app.R

/** The Android resource of each font [AppFonts] offers. */
private fun fontRes(choice: AppFonts.Choice): Int = when (choice.file) {
    "manrope_variable" -> R.font.manrope_variable
    "atkinson_hyperlegible_next_variable" -> R.font.atkinson_hyperlegible_next_variable
    "nunito_variable" -> R.font.nunito_variable
    "lora_variable" -> R.font.lora_variable
    else -> R.font.inter_variable
}

/** [choice] at every weight of the Ket 27 text styles (the desktop build has its own counterpart of this file). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal fun appFontFamily(choice: AppFonts.Choice): FontFamily {
    val res = fontRes(choice)
    return FontFamily(listOf(100, 200, 300, 400, 500, 600, 700, 800, 900).map { weight ->
        // A weight outside the font's axis is drawn at its nearest end (Lora's Black is its Bold).
        val axis = weight.coerceIn(choice.weights.first, choice.weights.last)
        Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(axis)))
    })
}
