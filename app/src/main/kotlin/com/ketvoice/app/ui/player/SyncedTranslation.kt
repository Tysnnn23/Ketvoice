package com.ketvoice.app.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.ketvoice.app.ui.common.Text
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.player.Player
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

/**
 * Traduction d'une ligne, révélée au rythme du chant.
 *
 * Une traduction ne peut pas être alignée syllabe par syllabe sur l'original :
 * les langues ne découpent pas les mots au même endroit. On répartit donc les
 * caractères traduits proportionnellement à l'avancement temporel de la ligne,
 * ce qui donne la même sensation de suivi sans prétendre à un alignement faux.
 *
 * Fluidité : la position n'est plus fournie par le parent (flow ~4 Hz, ce qui
 * donnait une révélation par paliers visibles). La ligne possède sa propre
 * horloge synchronisée sur vsync via withFrameNanos, comme KaraokeLine —
 * chaque frame recalcule la progression, d'où un balayage continu à 60/90/120
 * fps selon l'écran. En pause ou hors écran, la boucle s'arrête d'elle-même.
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

    // Position lissée : en lecture, elle suit vsync (fluide) ; en pause ou
    // quand la ligne n'est pas active, elle retombe sur la valeur du flow.
    var now by remember(line) { mutableLongStateOf(positionMs) }
    val playing by remember { Player.state.map { it.isPlaying }.distinctUntilChanged() }.collectAsState(false)
    LaunchedEffect(line, playing, active) {
        now = Player.currentPositionMs()
        if (playing && active) {
            while (isActive) {
                withFrameNanos { now = Player.currentPositionMs() }
            }
        }
    }
    LaunchedEffect(positionMs, playing) { if (!playing || !active) now = positionMs }

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

    // Fenêtre temporelle de la ligne, avec un léger hold final (220 ms) pour
    // que la traduction reste révélée jusqu'à la fin de la dernière syllabe.
    val start = line.words.first().startMs
    val end = line.words.lastOrNull()?.let { w ->
        if (w.endMs > w.startMs) w.endMs else w.startMs + 220L
    } ?: (start + 1L)
    val span = (end - start).coerceAtLeast(1L)
    val progress = ((now - start).toFloat() / span).coerceIn(0f, 1f)

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
        style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr),
        modifier = style
    )
}