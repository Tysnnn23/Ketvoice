package com.ketvoice.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.desktop.ketmix.LiveMix
import com.ketvoice.desktop.ui.theme.LocalKet

/**
 * The live mix view (Windows 2.0), simple version: the two decks of the transition in progress, one above the other,
 * their waveforms scrolling under a fixed playhead, beat and bar lines, how loud each is and the crossing between
 * them. Data from [LiveMix.frame]; draws nothing when no transition is on. For the interface to place where it fits.
 */
@Composable
fun LiveMixView(modifier: Modifier = Modifier) {
    val frame by LiveMix.frame.collectAsState()
    val f = frame ?: return
    val ket = LocalKet.current
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(f.label, color = ket.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DeckLane(f.outgoing, ket.label, ket.fill)
        Canvas(Modifier.fillMaxWidth().height(6.dp).padding(vertical = 2.dp)) {
            drawRoundRect(ket.fill, cornerRadius = CornerRadius(size.height / 2))
            drawRoundRect(ket.accent, size = Size(size.width * f.crossfade, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        DeckLane(f.incoming, ket.accent, ket.fill)
    }
}

@Composable
private fun DeckLane(deck: LiveMix.Deck, ink: Color, grid: Color) {
    val ket = LocalKet.current
    Text(deck.title + if (deck.bpm > 0f) "  ·  ${"%.1f".format(deck.bpm)} BPM" else "", color = ket.label, fontSize = 13.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    Canvas(Modifier.fillMaxWidth().height(56.dp)) { lane(deck, ink, grid) }
}

private fun DrawScope.lane(deck: LiveMix.Deck, ink: Color, grid: Color) {
    val w = size.width; val h = size.height
    for (x in deck.bars) drawLine(grid.copy(alpha = .9f), Offset(x * w, 0f), Offset(x * w, h), strokeWidth = 2f)
    for (x in deck.beats) drawLine(grid.copy(alpha = .45f), Offset(x * w, h * .2f), Offset(x * w, h * .8f), strokeWidth = 1f)
    val column = w / deck.peaks.size
    val alpha = .35f + .65f * deck.level.coerceIn(0f, 1f)
    for ((i, p) in deck.peaks.withIndex()) {
        val bar = (p.coerceIn(0f, 1f) * h * .9f).coerceAtLeast(1f)
        drawRect(ink.copy(alpha = alpha), Offset(i * column, (h - bar) / 2), Size((column - 1f).coerceAtLeast(1f), bar))
    }
    // The playhead, and a pulse on it that follows the beat.
    val head = w * LiveMix.BEFORE_MS / (LiveMix.BEFORE_MS + LiveMix.AFTER_MS).toFloat()
    drawLine(ink, Offset(head, 0f), Offset(head, h), strokeWidth = 2f)
    drawCircle(ink.copy(alpha = 1f - deck.phase), radius = 4f, center = Offset(head, 4f))
    // Voice level, a thin bar on the right edge.
    drawRect(ink.copy(alpha = .6f), Offset(w - 3f, h * (1f - deck.voice.coerceIn(0f, 1f))), Size(3f, h * deck.voice.coerceIn(0f, 1f)))
}
