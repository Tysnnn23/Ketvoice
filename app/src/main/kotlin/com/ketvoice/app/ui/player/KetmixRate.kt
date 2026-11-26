package com.ketvoice.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.KetmixLearning
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlinx.coroutines.delay

/** When the player asks for 👍/👎 (« Ketmix apprend de vos avis »). Pure, host-tested. */
internal object KetmixAsk {
    /** The transition to ask about once a mix stops being heard: the one that was playing, if not asked before nor rated. */
    fun ended(mixingId: Long?, last: KetmixLearning.Transition?, askedUpTo: Long): Long? =
        if (mixingId != null && last != null && last.id == mixingId && last.id > askedUpTo && last.rating == null) last.id else null
}

/**
 * The transition to rate on the player: set for [KetmixLearning.ASK_MS] once a mix ends (and [KetmixLearning.THANKS_MS]
 * after a rating), null otherwise, always null while « Ketmix apprend » or Ketmix is off.
 */
@Composable
internal fun rememberKetmixAsk(
    /** Whether a mix is heard now (a test seam; Ketmix's own phase by default). */
    mixing: Boolean = Automix.ui.collectAsState().value.phase == Automix.Phase.Mixing
): State<KetmixLearning.Transition?> {
    val lastState = KetmixLearning.last.collectAsState()
    val last by lastState
    val asking = remember { mutableStateOf<Long?>(null) }
    val mixingId = remember { mutableStateOf<Long?>(null) }
    val askedUpTo = remember { mutableLongStateOf(0L) }
    LaunchedEffect(mixing, last?.id) {
        if (mixing) { mixingId.value = last?.id; return@LaunchedEffect }
        KetmixAsk.ended(mixingId.value, last, askedUpTo.longValue)?.let { asking.value = it; askedUpTo.longValue = it }
        mixingId.value = null
    }
    LaunchedEffect(asking.value) { if (asking.value != null) { delay(KetmixLearning.ASK_MS); asking.value = null } }
    LaunchedEffect(last?.rating) {
        if (last?.rating != null && asking.value == last?.id) { delay(KetmixLearning.THANKS_MS); asking.value = null }
    }
    return remember {
        derivedStateOf {
            lastState.value?.takeIf { asking.value == it.id && AppSettings.ketmixLearns.value && AppSettings.automix.value }
        }
    }
}

/**
 * On the player, in the line above the seek bar where the mix strip was: « Cette transition vous a plu ? » with 👍 👎
 * for about nine seconds after a transition. It fades in (no motion under reduced motion) and never changes the line's height.
 */
@Composable
internal fun KetmixRateLine(t: KetmixLearning.Transition, modifier: Modifier = Modifier) {
    val accent = LocalTheme.current.accent
    val presence = remember(t.id) { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(t.id) { presence.animateTo(1f, spring(dampingRatio = .8f, stiffness = 400f)) }
    // Read in the layer (draw phase), never in composition.
    Row(modifier.graphicsLayer { alpha = presence.value.coerceIn(0f, 1f) }.testTag("ketmix-rate"), verticalAlignment = Alignment.CenterVertically) {
        AutomixGlyph(accent, Modifier.size(18.dp))
        Column(Modifier.padding(horizontal = 8.dp).weight(1f)) {
            Text(if (t.rating == null) "Cette transition vous a plu ?" else "Merci, Ketmix s'en souviendra", color = Color.White,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(capitalised(t.label), localize = false, color = Color.White.copy(alpha = .66f), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        KetmixThumbs(t.rating, Color.White, accent, "ketmix-rate")
    }
}

/** 👍 and 👎 in Ket 27's capsule, felt on a touch; [rating] marks the one chosen. */
@Composable
internal fun KetmixThumbs(rating: Boolean?, ink: Color, accent: Color, tagPrefix: String, modifier: Modifier = Modifier) {
    val haptic = rememberHaptics()
    Row(modifier.clip(RoundedCornerShape(999.dp)).background(ink.copy(alpha = .1f))
        .border(1.dp, ink.copy(alpha = .14f), RoundedCornerShape(999.dp)), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        Thumb(true, rating == true, ink, accent, "$tagPrefix-up") { KetmixLearning.rate(true); haptic(Haptics.Kind.Confirm) }
        Thumb(false, rating == false, ink, accent, "$tagPrefix-down") { KetmixLearning.rate(false); haptic(Haptics.Kind.Confirm) }
    }
}

@Composable
private fun Thumb(up: Boolean, chosen: Boolean, ink: Color, accent: Color, tag: String, onClick: () -> Unit) {
    val label = AppText.t(if (up) "Elle m'a plu" else "Elle ne m'a pas plu")
    Box(Modifier.size(width = 44.dp, height = 32.dp).clip(CircleShape)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label; selected = chosen }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(if (up) Ket27Glyphs.ThumbUp else Ket27Glyphs.ThumbDown, null, Modifier.size(17.dp),
            tint = if (chosen) accent else ink.copy(alpha = .82f))
    }
}

/** « Dernière transition » in Ketmix's settings: the last transition heard, rateable at any time while the setting is on. */
@Composable
internal fun KetmixLastTransition(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val t by KetmixLearning.last.collectAsState()
    Row(modifier.testTag("ketmix-last"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Dernière transition", color = theme.onBackground, fontSize = 15.sp)
            val now = t
            if (now == null) Text("Aucune transition pour l'instant", color = theme.secondary, fontSize = 12.sp)
            else Text("${capitalised(now.label)} · ${now.from} → ${now.to}", localize = false, color = theme.secondary,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        t?.let { KetmixThumbs(it.rating, theme.onBackground, theme.accent, "ketmix-last") }
    }
}

private fun capitalised(label: String): String = AppText.t(label).replaceFirstChar { it.uppercase() }
