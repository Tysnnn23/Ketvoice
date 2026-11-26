package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ketmix.KetmixFeedback
import com.ketvoice.desktop.ui.glass.GlassBackdrop
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * « Ketmix apprend de vos avis » on Windows (2.0), through the shell's registries only: the « Ketmix » tab of the right
 * panel opens with the last transition's 👍/👎 (« Dernière transition »), and a small glass toast above the island asks
 * for one after each transition, both only while learning is on. The live mix view of Windows 2.0 was removed in
 * 2.0.1 (owner: « retire le truc mix en direct »).
 */
object KetmixRating {
    const val TOAST_MS = 9_000L
    const val THANKS_MS = 1_600L

    fun register() {
        PanelTabs.register(PanelTabs.Tab("ketmix", "Ketmix", DeskGlyphs.Ketmix, 40) { KetmixTab() })
        ShellOverlays.register(ShellOverlays.Overlay("ketmix-rate", order = 30) { backdrop -> RateToast(backdrop) })
    }

    /**
     * Which transition the toast asks about: one that was playing and has just ended, unrated, while learning is on.
     * Pure, for the tests: [last] is the id of the transition last seen playing.
     */
    internal fun ended(t: KetmixFeedback.Transition?, last: Long?): Long? =
        if (t != null && !t.playing && t.id == last && t.rating == null) t.id else null

    /** « calé sur le tempo » → « Calé sur le tempo », in the listener's language. */
    fun title(label: String): String = AppText.t(label).replaceFirstChar { it.uppercaseChar() }
}

/** The « Ketmix » tab: the last transition's 👍/👎 while learning, then the tab's usual content. */
@Composable
private fun KetmixTab() {
    Column(Modifier.fillMaxSize()) {
        if (KetmixFeedback.enabled) LastTransition()
        Box(Modifier.weight(1f)) { KetmixPanel() }
    }
}

/** The last transition played, for a 👍/👎 given later. */
@Composable
private fun LastTransition() {
    val t by KetmixFeedback.transition.collectAsState()
    val last = t?.takeIf { !it.playing } ?: return
    val ket = LocalKet.current
    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu))
        .background(ket.card).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("ketmix-last"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            KText("Dernière transition", Ket27Desk.Type.caption, color = ket.secondary)
            KText(KetmixRating.title(last.label), Ket27Desk.Type.subheadline, color = ket.label, weight = FontWeight.SemiBold, translate = false)
        }
        RateButtons(last.rating, ket.label, "ketmix-last")
    }
}

/** After each transition, a glass capsule above the island (refracting the pages as the island does): « Cette transition vous a plu ? » 👍 👎. */
@Composable
private fun BoxScope.RateToast(backdrop: GlassBackdrop?) {
    val shell = LocalShell.current
    val ket = LocalKet.current
    val t by KetmixFeedback.transition.collectAsState()
    var playingId by remember { mutableStateOf<Long?>(null) }
    var asking by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(t?.id, t?.playing) {
        val now = t ?: return@LaunchedEffect
        KetmixRating.ended(now, playingId)?.let { asking = it }
        playingId = if (now.playing) now.id else null
    }
    LaunchedEffect(asking) { if (asking != null) { delay(KetmixRating.TOAST_MS); asking = null } }
    LaunchedEffect(t?.rating) { if (t?.rating != null && asking == t?.id) { delay(KetmixRating.THANKS_MS); asking = null } }
    val shown = asking != null && asking == t?.id && KetmixFeedback.enabled && !shell.fullPlayer
    var size by remember { mutableStateOf(IntSize.Zero) }
    val island = shell.islandBounds
    val place = if (island.isEmpty) Modifier.align(Alignment.BottomCenter).padding(bottom = ShellMetrics.ISLAND_HEIGHT + ShellMetrics.ISLAND_FOOT * 2 + 8.dp)
        else Modifier.offset { IntOffset((island.center.x - size.width / 2f).roundToInt(), (island.top - size.height - 10.dp.toPx()).roundToInt()) }
    Box(place.onSizeChanged { size = it }) {
        AnimatedVisibility(shown, enter = if (Motion.reduced) fadeIn(snap()) else fadeIn() + scaleIn(initialScale = .9f),
            exit = if (Motion.reduced) fadeOut(snap()) else fadeOut() + scaleOut(targetScale = .94f)) {
            val rating = t?.rating
            Row(Modifier.widthIn(max = 460.dp).glassShadow(22.dp, 12.dp).glass(backdrop, 22.dp, ket.glassTint, GlassStyle.Island, rim = ket.glassRim)
                .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp).testTag("ketmix-rate-toast"), verticalAlignment = Alignment.CenterVertically) {
                Icon(DeskGlyphs.Ketmix, null, Modifier.size(14.dp), tint = ket.accent)
                Column(Modifier.padding(start = 10.dp, end = 8.dp).weight(1f, fill = false)) {
                    KText(if (rating == null) "Cette transition vous a plu ?" else "Merci, Ketmix s'en souviendra", Ket27Desk.Type.footnote,
                        color = ket.label, weight = FontWeight.SemiBold)
                    t?.label?.let { KText(KetmixRating.title(it), Ket27Desk.Type.caption, color = ket.secondary, translate = false) }
                }
                Spacer(Modifier.height(1.dp))
                RateButtons(rating, ket.label, "ketmix-rate")
            }
        }
    }
}

/** 👍/👎 on a transition, as « Ketmix apprend de vos avis » counts them. */
@Composable
private fun RateButtons(rating: Boolean?, ink: Color, tagPrefix: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        KetIconButton(DeskGlyphs.ThumbUp, "Elle m'a plu", { KetmixFeedback.rate(true) }, Modifier.testTag("$tagPrefix-up"),
            size = 30.dp, glyph = 16.dp, active = rating == true, tint = ink.copy(alpha = .8f))
        KetIconButton(DeskGlyphs.ThumbDown, "Elle ne m'a pas plu", { KetmixFeedback.rate(false) }, Modifier.testTag("$tagPrefix-down"),
            size = 30.dp, glyph = 16.dp, active = rating == false, tint = ink.copy(alpha = .8f))
    }
}
