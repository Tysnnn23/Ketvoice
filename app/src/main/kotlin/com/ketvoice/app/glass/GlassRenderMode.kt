package com.ketvoice.app.glass

/** No AGSL without API 33; reduced motion and low-memory devices use the cheaper blur path. */
internal enum class GlassRenderMode { Static, Blur, Optical }

internal fun glassRenderMode(api: Int, hasBackdrop: Boolean, enabled: Boolean, captured: Boolean,
                             opaque: Boolean, reduceBlur: Boolean, highPerformance: Boolean,
                             lowMemory: Boolean, reducedMotion: Boolean): GlassRenderMode = when {
    !hasBackdrop || !enabled || captured || opaque || reduceBlur -> GlassRenderMode.Static
    // The performance mode (5.9.2) keeps glass that can be seen — a blur of what is behind — and drops
    // only the refraction and its moving reflections, the costly part one barely notices.
    api < 33 || lowMemory || reducedMotion || highPerformance -> GlassRenderMode.Blur
    else -> GlassRenderMode.Optical
}

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
