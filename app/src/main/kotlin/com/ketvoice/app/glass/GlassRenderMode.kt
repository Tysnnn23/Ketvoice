package com.ketvoice.app.glass

/** No AGSL without API 33; reduced motion and low-memory devices use the cheaper blur path. */
internal enum class GlassRenderMode { Static, Blur, Optical }

internal fun glassRenderMode(api: Int, hasBackdrop: Boolean, enabled: Boolean, captured: Boolean,
                             opaque: Boolean, reduceBlur: Boolean, highPerformance: Boolean,
                             lowMemory: Boolean, reducedMotion: Boolean): GlassRenderMode = when {
    !hasBackdrop || !enabled || captured || opaque || reduceBlur || highPerformance -> GlassRenderMode.Static
    api < 33 || lowMemory || reducedMotion -> GlassRenderMode.Blur
    else -> GlassRenderMode.Optical
}
