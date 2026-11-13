package com.ketvoice.app.glass

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.ketvoice.app.data.AppSettings

/**
 * Cover-aware glass: the dominant colour of the playing artwork lightly tints the
 * optical material, so navigation and controls belong to the music instead of being
 * neutral grey. The legibility stage of the shaders runs after this tint, so labels
 * keep their contrast whatever the cover.
 */
object GlassTint {
    /** Current cover colour, or null. Read from draw-phase lambdas only. */
    val cover = mutableStateOf<Color?>(null)
    /** 0..1 presence of the tint, animated when a cover appears or disappears. */
    val strength = androidx.compose.runtime.mutableFloatStateOf(1f)

    private val LIGHT = floatArrayOf(1f, 1f, 1f)
    private val DARK = floatArrayOf(.24f, .24f, .27f)

    /** Liquid 27 milk colour for the current cover. */
    fun milk(dark: Boolean): FloatArray =
        tinted(if (dark) DARK else LIGHT, cover.value, dark, AppSettings.coverTintedGlass.value, strength.floatValue)

    /**
     * Mixes [base] with a normalised cover colour: lifted toward white for the light
     * material, lowered for the dark one, then blended at a small fixed amount.
     */
    fun tinted(base: FloatArray, cover: Color?, dark: Boolean, enabled: Boolean, strength: Float = 1f): FloatArray {
        val presence = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 0f
        if (!enabled || cover == null || presence <= 0f) return base
        val r = cover.red; val g = cover.green; val b = cover.blue
        if (!(r.isFinite() && g.isFinite() && b.isFinite())) return base
        val amount = (if (dark) .34f else .24f) * presence
        fun channel(index: Int, value: Float): Float {
            val normalised = if (dark) value.coerceIn(0f, 1f) * .50f else 1f - (1f - value.coerceIn(0f, 1f)) * .42f
            return base[index] + (normalised - base[index]) * amount
        }
        return floatArrayOf(channel(0, r), channel(1, g), channel(2, b))
    }
}

/**
 * Feeds [GlassTint] from the playing artwork. Colour changes are animated here, in a
 * leaf composable, so a track change never recomposes the screens that own the glass.
 */
@androidx.compose.runtime.Composable
fun ObserveCoverTint(coverUrl: String?) {
    val wanted = AppSettings.coverTintedGlass.value && AppSettings.liquidGlass.value
    val accent = if (wanted) com.ketvoice.app.theme.rememberCoverAccent(coverUrl)?.first else null
    val last = androidx.compose.runtime.remember { mutableStateOf(Color.Gray) }
    if (accent != null) last.value = accent
    val reduced = com.ketvoice.app.ui.motion.Motion.reduced
    val color = androidx.compose.animation.animateColorAsState(last.value,
        androidx.compose.animation.core.tween(if (reduced) 0 else 520), label = "glass-cover-tint")
    val presence = androidx.compose.animation.core.animateFloatAsState(if (accent != null) 1f else 0f,
        androidx.compose.animation.core.tween(if (reduced) 0 else 420), label = "glass-cover-presence")
    androidx.compose.runtime.SideEffect {
        GlassTint.cover.value = if (presence.value > 0f) color.value else null
        GlassTint.strength.floatValue = presence.value
    }
}
