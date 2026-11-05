package com.ketvoice.app.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings

/**
 * Single source of truth for every duration, curve, spring and elevation.
 *
 * Legacy call sites use [FADE]/[PRESS]/[FOLLOW] + [duration] + [pressSpring];
 * the premium design system adds a named scale on top so new work reads intent
 * (fast / normal / slow) instead of magic numbers. Everything still respects
 * "reduce motion" (system setting OR in-app toggle) via [reduced].
 */
object Motion {
    /* ── Legacy durations (kept for existing call sites) ── */
    const val FADE = 220
    const val PRESS = 140
    const val FOLLOW = 380

    /* ── Premium duration scale (ms) ── */
    const val INSTANT = 0
    const val FAST = 150     // micro-interaction: chips, ripple close
    const val NORMAL = 250   // standard panel / nav swap
    const val MEDIUM = 350   // sheet enter, modal open
    const val SLOW = 500     // full-screen morph, player open
    const val EPILOGUE = 700 // long reveals: lyrics intro, carousel sweep

    /* ── Easing curves ── */
    /** Sharp start, soft landing — panels sliding in, nav push. */
    val EASE_DECELERATE: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    /** Soft start, sharp landing — content leaving, dismissals. */
    val EASE_ACCELERATE: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
    /** Gentle S-curve — hero art, text fades, colour morphs. */
    val EASE_STANDARD: Easing = CubicBezierEasing(0.3f, 0.0f, 0.2f, 1.0f)
    /** Playful overshoot for entrances that should feel alive. */
    val EASE_OVERSHOOT: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

    val reduced: Boolean get() = AppSettings.reduceAnimations.value || AppSettings.systemReduceMotion.value
    fun duration(normal: Int = FADE): Int = if (reduced) 0 else normal

    /**
     * Ressorts façon liquid glass : l'enfoncement est net et sans rebond (le doigt
     * "pousse" le verre), le relâchement rebondit légèrement avant de se stabiliser.
     * Avec "réduire les animations", tout devient instantané.
     */
    fun pressSpring(pressed: Boolean): FiniteAnimationSpec<Float> = when {
        reduced -> snap()
        pressed -> spring(dampingRatio = 0.9f, stiffness = 900f)
        else -> spring(dampingRatio = 0.5f, stiffness = 420f)
    }

    /* ── Named springs ── */
    /** Tight snap — icon toggles, small chips. */
    fun snapSpring(): FiniteAnimationSpec<Float> =
        if (reduced) snap() else spring(dampingRatio = 0.8f, stiffness = 900f)

    /** Smooth overshoot — player open, sheet lift, card press. */
    fun gentleSpring(): FiniteAnimationSpec<Float> =
        if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = 220f)

    /** Controlled wobble — artwork entrance, queue add. */
    fun bouncySpring(): FiniteAnimationSpec<Float> =
        if (reduced) snap() else spring(dampingRatio = 0.5f, stiffness = 380f)

    /* ── Convenience tween durations that already clamp for reduce-motion ── */
    fun fastMs(): Int = duration(FAST)
    fun normalMs(): Int = duration(NORMAL)
    fun mediumMs(): Int = duration(MEDIUM)
    fun slowMs(): Int = duration(SLOW)

    /* ── Elevation scale (dp) — one ladder for every shadow in the app ── */
    val ELEVATION_FLAT get() = 0.dp      // on-surface content
    val ELEVATION_CARD get() = 1.dp      // card resting on surface
    val ELEVATION_RAISED get() = 4.dp    // nav rail, mini-player
    val ELEVATION_FLOATING get() = 8.dp  // FAB pill, bottom sheet
    val ELEVATION_OVERLAY get() = 16.dp  // full player, modal
    val ELEVATION_SYSTEM get() = 24.dp   // system dialog
}

@Composable
fun ObserveSystemMotion() {
    val resolver = LocalContext.current.applicationContext.contentResolver
    DisposableEffect(resolver) {
        fun refresh() {
            AppSettings.systemReduceMotion.value = runCatching {
                Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }.getOrDefault(false)
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        refresh()
        runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
}
