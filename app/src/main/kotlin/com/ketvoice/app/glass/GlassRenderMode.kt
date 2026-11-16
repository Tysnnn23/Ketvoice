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
