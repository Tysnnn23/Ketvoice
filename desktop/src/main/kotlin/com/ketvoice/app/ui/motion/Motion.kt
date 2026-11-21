package com.ketvoice.app.ui.motion

// Desktop counterpart of the app's ui/motion/Motion.kt: the same tokens, without the Android
// system-animation observer (reduced motion comes from the in-app setting on the desktop).
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.DesignTokens

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
    const val FAST = DesignTokens.Motion.FAST_MS
    const val NORMAL = DesignTokens.Motion.NORMAL_MS
    const val MEDIUM = DesignTokens.Motion.MEDIUM_MS
    const val SLOW = DesignTokens.Motion.SLOW_MS
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
        pressed -> spring(dampingRatio = DesignTokens.Motion.PRESS_DAMPING, stiffness = DesignTokens.Motion.PRESS_STIFFNESS)
        else -> spring(dampingRatio = DesignTokens.Motion.RELEASE_DAMPING, stiffness = DesignTokens.Motion.RELEASE_STIFFNESS)
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

    /**
     * 7.0: the one set of springs every shared surface moves with. Things arrive with a trace of
     * settle and leave without any, quicker than they came; a pushed page never overshoots.
     * Each caller still snaps under [reduced].
     */
    data class Tuning(val damping: Float, val stiffness: Float) {
        fun <T> spec(visibilityThreshold: T? = null): androidx.compose.animation.core.SpringSpec<T> =
            spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = visibilityThreshold)

        /** [spec], or a snap under reduced motion: what a call site that moves something on a tap wants. */
        fun <T> motion(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = if (reduced) snap() else spec(visibilityThreshold)

        companion object {
            /**
             * 8.0: a spring written the way iOS 27 writes one, `spring(duration:bounce:)`: [duration] is the
             * perceived time in seconds, [bounce] 0 (none) .. 1. Stiffness (2π / duration)², damping 1 − bounce.
             */
            fun of(duration: Float, bounce: Float = 0f): Tuning {
                val omega = 2f * Math.PI.toFloat() / duration.coerceAtLeast(.05f)
                return Tuning((1f - bounce).coerceIn(.05f, 1f), omega * omega)
            }
        }
    }
    object Springs {
        /** A page pushed or popped: critically damped, no overshoot. */
        val PUSH = Tuning(1f, 420f)
        /** A page or card grown out of its cover, and folded back into it. */
        val ZOOM = Tuning(.92f, 430f)
        /** A sheet rising to a detent, a floating sheet or a menu opening (was .72–.82 / 340–420). */
        val SHEET = Tuning(.84f, 400f)
        /** Anything leaving: a sheet or a menu closing (was 1 / 520–600). */
        val DISMISS = Tuning(1f, 600f)
        /** Content that appears once: an empty state, a card filling in. */
        val APPEAR = Tuning(.88f, 380f)

        /*
         * 8.0 (« Ressorts partout »): the timings of iOS 27, four presets for everything that moves on a touch.
         * Every one is interruptible: an Animatable sent elsewhere mid-flight keeps its value and velocity.
         */
        /** A control answering a tap: a toggle, a symbol replaced, a chip, a press released (0.3 s, a trace of bounce). */
        val SNAPPY = Tuning.of(.3f, .15f)
        /** Something that must not overshoot: fades, cross-fades, a page's content, a list row arriving (0.4 s). */
        val SMOOTH = Tuning.of(.4f, 0f)
        /** A symbol that reacts: the heart of a like, the plus of an add, a tab icon selected (0.45 s, visible bounce). */
        val BOUNCY = Tuning.of(.45f, .32f)
        /** A menu, a sheet or an alert growing out of the button that opened it (0.38 s, a slight settle). */
        val GROW = Tuning.of(.38f, .12f)
    }

    /* ── Convenience tween durations that already clamp for reduce-motion ── */
    fun fastMs(): Int = duration(FAST)
    fun normalMs(): Int = duration(NORMAL)
    fun mediumMs(): Int = duration(MEDIUM)
    fun slowMs(): Int = duration(SLOW)

    /* ── Elevation scale (dp) — one ladder for every shadow in the app ── */
    val ELEVATION_FLAT get() = DesignTokens.Elevation.flat
    val ELEVATION_CARD get() = DesignTokens.Elevation.card
    val ELEVATION_RAISED get() = DesignTokens.Elevation.raised
    val ELEVATION_FLOATING get() = DesignTokens.Elevation.floating
    val ELEVATION_OVERLAY get() = DesignTokens.Elevation.overlay
    val ELEVATION_SYSTEM get() = DesignTokens.Elevation.system
}

