package com.ketvoice.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixWords
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ketmix.KetmixFeedback
import com.ketvoice.desktop.ketmix.LiveMix
import com.ketvoice.desktop.ui.glass.GlassBackdrop
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/**
 * The live mix view (Windows 2.0): while Ketmix crosses two titles, what it is doing in words, both decks' waveforms
 * scrolling under a fixed playhead with their beat and bar lines, each deck's volume curve as the engine plays it, the
 * crossing between them, and 👍/👎 when « Ketmix apprend de vos avis » is on. Data from [LiveMix.frame]; draws nothing
 * when no transition is on. Placed by `KetmixLive` in the « Ketmix » tab and in the full player ([onDark], over the
 * cover colours, on its own glass when [backdrop] is given); [compact] keeps it short beside the full player's lyrics.
 */
@Composable
fun LiveMixView(modifier: Modifier = Modifier, onDark: Boolean = false, compact: Boolean = false, backdrop: GlassBackdrop? = null) {
    val frame by LiveMix.frame.collectAsState()
    val f = frame ?: return
    val ket = LocalKet.current
    val ink = LiveInk.of(onDark, ket.label, ket.secondary, ket.accent, ket.hairline)
    val surface = if (onDark) modifier.glass(backdrop, Ket27Desk.Radius.card, Color.White.copy(alpha = .07f), GlassStyle.Clear, rim = Color.White.copy(alpha = .24f))
        else modifier.clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card)
    Column(surface.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp).testTag("live-mix")) {
        LiveHeader(f, ink, compact)
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        val lane = if (compact) 26.dp else 50.dp
        DeckLane(f.outgoing, ink.label, ink, lane, "live-mix-out")
        Crossing(f.crossfade, ink)
        DeckLane(f.incoming, ink.accent, ink, lane, "live-mix-in")
        if (KetmixFeedback.enabled) {
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            RateRow(ink)
        }
    }
}

/** The view's colours: the window's on a card, white over the full player's cover colours. */
internal data class LiveInk(val label: Color, val secondary: Color, val accent: Color, val grid: Color, val eyebrow: Color = accent) {
    companion object {
        /** Over the cover colours the accent can sink into the backdrop: the small capitals stay white there. */
        fun of(onDark: Boolean, label: Color, secondary: Color, accent: Color, hairline: Color) =
            if (onDark) LiveInk(Color.White, Color.White.copy(alpha = .68f), accent, Color.White.copy(alpha = .30f), Color.White.copy(alpha = .82f))
            else LiveInk(label, secondary, accent, hairline)
    }
}

/** « MIX EN DIRECT » and the measures, the kind of transition, and what Ketmix is doing now. */
@Composable
private fun LiveHeader(f: LiveMix.Frame, ink: LiveInk, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(DeskGlyphs.Ketmix, null, Modifier.size(13.dp), tint = ink.eyebrow)
        KText(AppText.t("Mix en direct").uppercase(), Ket27Desk.Type.eyebrow, Modifier.padding(start = 6.dp).weight(1f), color = ink.eyebrow, translate = false)
        f.plan?.let { MixWords.measures(it) }?.takeIf { it.isNotBlank() }?.let {
            KText(it, Ket27Desk.Type.caption.copy(fontFeatureSettings = "tnum"), color = ink.secondary, translate = false)
        }
    }
    Spacer(Modifier.height(4.dp))
    KText(LiveMixWords.title(f.label), if (compact) Ket27Desk.Type.subheadline else Ket27Desk.Type.headline, color = ink.label,
        weight = FontWeight.SemiBold, translate = false, modifier = Modifier.testTag("live-mix-title"))
    KText(LiveMixWords.doing(f), Ket27Desk.Type.footnote, color = ink.secondary, translate = false, modifier = Modifier.testTag("live-mix-doing"))
}

/** One deck: its title and tempo, then its waveform, grid and volume curve. */
@Composable
private fun DeckLane(deck: LiveMix.Deck, color: Color, ink: LiveInk, height: Dp, tag: String) {
    Row(Modifier.padding(top = 4.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        KText(deck.title, Ket27Desk.Type.footnote, Modifier.padding(start = 6.dp).weight(1f, fill = false), color = ink.label,
            weight = FontWeight.Medium, translate = false)
        if (deck.bpm > 0f) KText("${"%.1f".format(java.util.Locale.ROOT, deck.bpm)} BPM", Ket27Desk.Type.caption.copy(fontFeatureSettings = "tnum"),
            Modifier.padding(start = 8.dp), color = ink.secondary, translate = false)
    }
    val reduced = Motion.reduced
    Canvas(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp)).background(ink.grid.copy(alpha = ink.grid.alpha * .35f)).testTag(tag)) {
        lane(deck, color, ink.grid, reduced)
    }
}

/** The crossing: how far the next title has taken over, 0 all outgoing .. 1 all incoming. */
@Composable
private fun Crossing(share: Float, ink: LiveInk) {
    Canvas(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).height(4.dp).testTag("live-mix-crossing")) {
        drawRoundRect(ink.grid, cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(ink.accent, size = Size(size.width * share.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

/** 👍/👎 on the transition playing, as « Ketmix apprend de vos avis » counts them. */
@Composable
internal fun RateRow(ink: LiveInk, tagPrefix: String = "live-mix") {
    val t by KetmixFeedback.transition.collectAsState()
    val rating = t?.rating
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KText(if (rating == null) "Cette transition vous a plu ?" else "Merci, Ketmix s'en souviendra", Ket27Desk.Type.footnote,
            Modifier.weight(1f), color = ink.secondary)
        RateButtons(rating, ink, tagPrefix)
    }
}

@Composable
internal fun RateButtons(rating: Boolean?, ink: LiveInk, tagPrefix: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        KetIconButton(DeskGlyphs.ThumbUp, "Elle m'a plu", { KetmixFeedback.rate(true) }, Modifier.testTag("$tagPrefix-up"),
            size = 30.dp, glyph = 16.dp, active = rating == true, tint = ink.label.copy(alpha = .8f))
        KetIconButton(DeskGlyphs.ThumbDown, "Elle ne m'a pas plu", { KetmixFeedback.rate(false) }, Modifier.testTag("$tagPrefix-down"),
            size = 30.dp, glyph = 16.dp, active = rating == false, tint = ink.label.copy(alpha = .8f))
    }
}

private fun DrawScope.lane(deck: LiveMix.Deck, ink: Color, grid: Color, reduced: Boolean) {
    val w = size.width; val h = size.height
    val head = w * LiveMix.BEFORE_MS / (LiveMix.BEFORE_MS + LiveMix.AFTER_MS).toFloat()
    for (x in deck.bars) drawLine(grid.copy(alpha = .9f), Offset(x * w, 0f), Offset(x * w, h), strokeWidth = 1.5f)
    for (x in deck.beats) drawLine(grid.copy(alpha = .5f), Offset(x * w, h * .3f), Offset(x * w, h * .7f), strokeWidth = 1f)
    // The waveform: what has played dimmer than what is coming, all of it fainter as the deck's level falls.
    val column = w / deck.peaks.size
    val level = .4f + .6f * deck.level.coerceIn(0f, 1f)
    for ((i, p) in deck.peaks.withIndex()) {
        val bar = (p.coerceIn(0f, 1f) * h * .8f).coerceAtLeast(1f)
        val x = i * column
        drawRect(ink.copy(alpha = level * if (x < head) .45f else .85f), Offset(x, (h - bar) / 2), Size((column - .6f).coerceAtLeast(.8f), bar))
    }
    gainCurve(deck.gain, ink)
    // The playhead, with a dot that fades over each beat (still under reduced motion).
    drawLine(ink, Offset(head, 0f), Offset(head, h), strokeWidth = 2f)
    drawCircle(ink.copy(alpha = if (reduced) 1f else 1f - deck.phase * .8f), radius = 3.5f, center = Offset(head, 5f))
    // The voice level, a thin bar on the right edge.
    val voice = deck.voice.coerceIn(0f, 1f)
    drawRect(ink.copy(alpha = .6f), Offset(w - 3f, h * (1f - voice)), Size(3f, h * voice))
}

/** The deck's volume over the window, from silent at the bottom to full at the top. */
private fun DrawScope.gainCurve(gain: FloatArray, ink: Color) {
    if (gain.size < 2) return
    val top = size.height * .08f; val bottom = size.height * .92f
    val path = Path()
    for ((i, g) in gain.withIndex()) {
        val p = Offset(size.width * i / (gain.size - 1), bottom - (bottom - top) * g)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, Color.Black.copy(alpha = .25f), style = Stroke(3.2.dp.toPx(), cap = StrokeCap.Round))
    drawPath(path, ink, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
}

/** The view's words (French, translated here: the sentence holds a number). */
internal object LiveMixWords {
    /** « calé sur le tempo » → « Calé sur le tempo », in the listener's language. */
    fun title(label: String): String = AppText.t(label).replaceFirstChar { it.uppercaseChar() }

    /** What Ketmix is doing at this point of the transition. */
    fun doing(f: LiveMix.Frame): String {
        if (f.untilMs > 0L) return AppText.t("Transition dans {0} s").replace("{0}", ((f.untilMs + 999L) / 1000L).toString())
        if (f.crossfade >= 1f) return AppText.t("Transition terminée")
        return AppText.t(sentence(f.plan, f.crossfade))
    }

    /** The sentence of [plan] at [share] (0..1) of its crossing, in French. */
    fun sentence(plan: MixPlan?, share: Float): String = when {
        plan == null -> "Un titre s'efface pendant que l'autre arrive"
        plan.cut -> "Le titre suivant entre sur le temps"
        plan.stems && plan.reason.startsWith("voices") -> "Les voix se passent le relais"
        plan.glide != null -> "Les tempos glissent l'un vers l'autre"
        plan.kind == MixKind.Beatmatch -> if (MixWords.swapShare(plan).let { it >= 0f && share >= it }) "Les basses passent au titre suivant"
            else "Les deux titres jouent en rythme"
        plan.kind == MixKind.Echo -> "Le titre s'éteint dans son écho"
        plan.kind == MixKind.Filter -> "Les filtres se croisent"
        plan.kind == MixKind.Wash -> "Le titre se dissout dans la réverbération"
        plan.kind == MixKind.Rise -> "La tension monte jusqu'au titre suivant"
        plan.kind == MixKind.Crossfade -> "Un titre s'efface pendant que l'autre arrive"
        else -> "Le titre suivant enchaîne sans silence"
    }
}
