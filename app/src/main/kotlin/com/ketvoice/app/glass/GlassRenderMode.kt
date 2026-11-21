package com.ketvoice.app.glass

/** No AGSL without API 33; reduced motion and low-memory devices use the cheaper blur path. */
internal enum class GlassRenderMode { Static, Blur, Optical }

internal fun glassRenderMode(api: Int, hasBackdrop: Boolean, enabled: Boolean, captured: Boolean,
                             opaque: Boolean, reduceBlur: Boolean, highPerformance: Boolean,
                             lowMemory: Boolean, reducedMotion: Boolean): GlassRenderMode = when {
    !hasBackdrop || !enabled || captured || opaque || reduceBlur -> GlassRenderMode.Static
    // 8.0 (« Vrai verre réfractif »): the performance mode keeps the refractive material on Android 13+, in its
    // cheaper form ([RefractionSpec.forPath]: no dispersion, one backdrop tap, a narrower lens). 5.9.2 dropped it.
    api < 33 || lowMemory || reducedMotion -> GlassRenderMode.Blur
    else -> GlassRenderMode.Optical
}

/**
 * Which glass material a surface gets (8.0), one answer for the tab bar, the mini-player, the glass buttons and
 * the menus and sheets:
 *  - [Refractive]: the shared AGSL material (Android 13+): convex lens at the rim, a faint dispersion on the rim only;
 *  - [RefractiveLite]: the same shader in the performance mode, without dispersion and with a cheaper lens;
 *  - [Opaque]: reduced transparency (the intensity slider near its end, or Android's high-contrast text): mostly
 *    opaque, no lens and no dispersion, whatever the Android version;
 *  - [Blur]: Android 12 (or reduced motion / little memory): the RenderEffect blur glass of 7.x;
 *  - [SoftBlur]: Android 11 (Ketvoice Lite): [SoftBlur], never a flat panel;
 *  - [Static]: no live backdrop (captured, glass off, « Réduire le flou »).
 */
internal enum class GlassMaterialPath { Refractive, RefractiveLite, Opaque, Blur, SoftBlur, Static }

internal fun glassMaterialPath(api: Int, mode: GlassRenderMode, highPerformance: Boolean,
                               reducedTransparency: Boolean): GlassMaterialPath = when {
    mode == GlassRenderMode.Static -> GlassMaterialPath.Static
    reducedTransparency -> GlassMaterialPath.Opaque
    mode == GlassRenderMode.Optical -> if (highPerformance) GlassMaterialPath.RefractiveLite else GlassMaterialPath.Refractive
    SoftBlur.needed(api) -> GlassMaterialPath.SoftBlur
    else -> GlassMaterialPath.Blur
}

/**
 * How the refractive shader runs for a [GlassMaterialPath]: [dispersion] and [lens] scale the rim's colour split
 * and its bend, [quality] 1 samples the backdrop's luminance in five taps, 0 in one.
 */
internal data class RefractionSpec(val dispersion: Float, val lens: Float, val quality: Float) {
    companion object {
        private val FULL = RefractionSpec(1f, 1f, 1f)
        private val LITE = RefractionSpec(0f, .7f, 0f)
        private val OPAQUE = RefractionSpec(0f, 0f, 0f)
        fun forPath(path: GlassMaterialPath): RefractionSpec = when (path) {
            GlassMaterialPath.Refractive -> FULL
            GlassMaterialPath.RefractiveLite -> LITE
            else -> OPAQUE
        }
    }
}

/** Reduced transparency: the intensity slider from « Teinté » on, or Android's high-contrast text. */
internal fun glassReducedTransparency(intensity: Float, highContrast: Boolean): Boolean =
    highContrast || GlassClarity.sanitize(intensity) >= GlassClarity.TINTED

/**
 * The blur radius of a glass panel. Reduced motion keeps the cheaper, lighter blur it always had; a phone with
 * little memory too, except in Ketvoice Lite, which keeps the visible blur and makes it cheap instead
 * (owner, 2026-11-18: « qui garde les mêmes effets »; Android 11 draws it with [SoftBlur]).
 */
internal fun glassBlurRadius(blurPx: Float, mode: GlassRenderMode, lowMemory: Boolean, reducedMotion: Boolean,
                             lite: Boolean): Float =
    if (mode == GlassRenderMode.Blur && (reducedMotion || lowMemory && !lite))
        minOf(blurPx, com.ketvoice.app.theme.DesignTokens.Glass.LOW_COST_BLUR_PX) else blurPx

/** The colour effects of the blurred glass (vibrancy and the panel's colour controls) as one filter, for [SoftBlur]. */
internal fun glassSoftFilter(params: GlassParams): androidx.compose.ui.graphics.ColorFilter =
    androidx.compose.ui.graphics.ColorFilter.colorMatrix(SoftBlur.colorMatrix(
        saturation = VIBRANCY * params.saturation, brightness = params.brightness - 1f,
        contrast = params.contrast, exposure = params.exposure))

/** The backdrop library's vibrancy: 1.5 x saturation. */
internal const val VIBRANCY = 1.5f
