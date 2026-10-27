package com.ketvoice.app.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.ketvoice.app.ui.common.Text
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.MusicApi

/**
 * Traduction d'une ligne, révélée au rythme du chant.
 *
 * Une traduction ne peut pas être alignée syllabe par syllabe sur l'original :
 * les langues ne découpent pas les mots au même endroit. On répartit donc les
 * caractères traduits proportionnellement à l'avancement temporel de la ligne,
 * ce qui donne la même sensation de suivi sans prétendre à un alignement faux.
 *
 * Typographie : identique aux paroles (Black, interlettrage serré), taille 16 sp
 * conservée pour ne pas écraser la ligne originale.
 */
@Composable
internal fun SyncedTranslation(
    line: MusicApi.LyricLine,
    text: String,
    positionMs: Long,
    active: Boolean,
    dimAlpha: Float
) {
    val style = Modifier.fillMaxWidth().padding(top = 7.dp)

    if (!active || line.words.isEmpty() || text.isBlank()) {
        Text(
            text,
            localize = false,
            color = Color.White.copy(alpha = dimAlpha),
            fontSize = 16.sp,
            lineHeight = 22.sp,
            letterSpacing = (-.3).sp,
            fontWeight = FontWeight.Black,
            modifier = style
        )
        return
    }

    val start = line.words.first().startMs
    val end = line.words.lastOrNull()?.let { w ->
        if (w.endMs > w.startMs) w.endMs else w.startMs + 220L
    } ?: (start + 1L)
    val span = (end - start).coerceAtLeast(1L)
    val progress = ((positionMs - start).toFloat() / span).coerceIn(0f, 1f)

    val total = text.length
    val revealed = progress * total
    val sung = Color.White.copy(alpha = .84f)
    val pending = Color.White.copy(alpha = .32f)

    val rendered = buildAnnotatedString {
        var i = 0
        while (i < total) {
            val isSung = i < revealed
            // Regroupe les caractères voisins de même état pour limiter les spans.
            var j = i
            while (j < total && (j < revealed) == isSung) j++
            val slice = text.substring(i, j)
            withStyle(SpanStyle(color = if (isSung) sung else pending)) { append(slice) }
            i = j
        }
    }

    MaterialText(
        text = rendered,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-.3).sp,
        fontWeight = FontWeight.Black,
        modifier = style
    )
}
