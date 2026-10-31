package com.ketvoice.app.glass

internal data class ControlLens(val heightPx: Float, val amountPx: Float) {
    companion object {
        /** Bound the optical band/displacement to the actual control, including tiny thumbs. */
        fun forControl(minEdgePx: Float, density: Float, heightDp: Float, amountDp: Float): ControlLens {
            if (!minEdgePx.isFinite() || !density.isFinite() || minEdgePx <= 0f || density <= 0f) return ControlLens(0f, 0f)
            fun scaled(value: Float, limit: Float): Float =
                if (value.isFinite() && value > 0f) (value * density).coerceAtMost(minEdgePx * limit) else 0f
            return ControlLens(scaled(heightDp, .45f), scaled(amountDp, .8f))
        }
    }
}
