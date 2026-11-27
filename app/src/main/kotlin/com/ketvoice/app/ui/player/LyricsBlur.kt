package com.ketvoice.app.ui.player

/**
 * « Flou des paroles » (8.3; the owner lifts the 6.1 « no blur on lyrics » rule): the lines away from the sung one
 * go a little out of focus, more the further they are. The radius follows the line's dimming (same ~400 ms) and
 * is a property of the line's layer, so a line whose radius has settled is not blurred again on every frame
 * (Android keeps the filtered picture of a layer whose content has not changed).
 */
internal object LyricsBlur {
    /** Radius in dp by distance from the sung line: 0 on it, then 1, 1, 1.7 and 2.4 from four lines away. */
    private val BY_DISTANCE = floatArrayOf(0f, 1f, 1f, 1.7f, 2.4f)
    /** The blur comes and goes with the line's alpha. */
    const val FADE_MS = 400
    /** RenderEffect blur exists from Android 12. */
    const val MIN_API = 31

    fun byDistance(distance: Int): Float = BY_DISTANCE[distance.coerceIn(0, BY_DISTANCE.lastIndex)]

    /** Whether the device and edition draw the blur at all (and offer its switch). */
    fun supported(api: Int, quest: Boolean, liteModest: Boolean): Boolean = api >= MIN_API && !quest && !liteModest

    /**
     * The radius, in dp, line [distance] lines away from the sung one is drawn with. Nothing for the sung line,
     * for lyrics without times, while the listener scrolls them by hand (or picks lines for a Moment), with the
     * setting off, reduced motion or the performance mode (which the automatic economy mode also turns on).
     * [level] (« Paroles à ton goût », 0..1) scales the distances; 0 is no blur.
     */
    fun radiusDp(distance: Int, enabled: Boolean, timed: Boolean, browsing: Boolean,
                 reduced: Boolean, performance: Boolean, supported: Boolean, level: Float = 1f): Float {
        val k = if (level.isFinite()) level.coerceIn(0f, 1f) else 1f
        return if (!enabled || k <= 0f || !timed || browsing || reduced || performance || !supported || distance <= 0) 0f
        else byDistance(distance) * k
    }

    /** Pixels rounded to a quarter, so that a settled radius always gives the same effect. */
    fun quantizedPx(px: Float): Float = if (!px.isFinite() || px <= 0f) 0f else kotlin.math.round(px * 4f) / 4f

    /** This device: Android 12 or later, not Quest, not Ketvoice TV (no live blur there), not a modest phone running Ketvoice Lite. */
    fun deviceSupported(): Boolean = supported(android.os.Build.VERSION.SDK_INT,
        com.ketvoice.app.BuildConfig.IS_QUEST || com.ketvoice.app.BuildConfig.IS_TV,
        com.ketvoice.app.LiteTuning.lite && com.ketvoice.app.LiteTuning.modest)
}

/** One line's blur effect, made again only when its (quantized) radius changes. */
internal class LyricBlurEffect {
    private var px = 0f
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null
    fun at(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        val q = LyricsBlur.quantizedPx(radiusPx)
        if (q <= 0f) return null
        if (q != px || effect == null) {
            px = q
            effect = androidx.compose.ui.graphics.BlurEffect(q, q, androidx.compose.ui.graphics.TileMode.Decal)
        }
        return effect
    }
}
