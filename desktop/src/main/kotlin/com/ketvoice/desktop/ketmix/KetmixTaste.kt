package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * « Ketmix apprend de vos avis » (Windows 2.0, optional, off by default): 👍/👎 on a transition move a weight per kind
 * of transition (blend in time, echo, build, filters…), kept on this PC only. A kind the listener has turned down
 * often enough is left out of Ketmix's automatic choices ([MixContext.avoid]); the styles the listener picks by hand
 * are never overridden. Pure: the weights and their text form.
 */
internal object KetmixTaste {
    /** score: −1 (always disliked) .. 1 (always liked), an average that forgets slowly; votes: how many ratings. */
    data class Weight(val score: Float = 0f, val votes: Int = 0)

    const val LEARNING = .3f
    const val MIN_VOTES = 3
    const val AVOID_BELOW = -.35f
    /** Kinds that are never left out: the plain ways across must stay available. */
    private val KEPT = setOf(MixKind.None, MixKind.Trim, MixKind.Crossfade)
    /** At most this many kinds are avoided at once, the most disliked first. */
    private const val MAX_AVOIDED = 3

    fun rate(weights: Map<MixKind, Weight>, kind: MixKind, up: Boolean): Map<MixKind, Weight> {
        val w = weights[kind] ?: Weight()
        val score = (w.score * (1 - LEARNING) + (if (up) 1f else -1f) * LEARNING).coerceIn(-1f, 1f)
        return weights + (kind to Weight(score, w.votes + 1))
    }

    fun avoid(weights: Map<MixKind, Weight>): Set<MixKind> = weights.entries
        .filter { (kind, w) -> kind !in KEPT && w.votes >= MIN_VOTES && w.score <= AVOID_BELOW }
        .sortedBy { it.value.score }.take(MAX_AVOIDED).map { it.key }.toSet()

    fun encode(weights: Map<MixKind, Weight>): String =
        weights.entries.joinToString(";") { (k, w) -> "${k.name}:${"%.4f".format(java.util.Locale.ROOT, w.score)}:${w.votes}" }

    fun decode(text: String?): Map<MixKind, Weight> = text.orEmpty().split(';').mapNotNull { part ->
        val bits = part.split(':')
        val kind = MixKind.values().firstOrNull { it.name == bits.getOrNull(0) } ?: return@mapNotNull null
        val score = bits.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: return@mapNotNull null
        val votes = bits.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
        kind to Weight(score, votes)
    }.toMap()
}

/**
 * The API the interface uses for 👍/👎 (Windows 2.0): [transition] is the transition playing or the last one played,
 * [rate] records the listener's opinion of it. Ratings only count while [KetmixPcSettings.learns] is on.
 */
internal object KetmixFeedback {
    data class Transition(val id: Long, val from: Track?, val to: Track?, val kind: MixKind, val label: String,
                          val playing: Boolean, val rating: Boolean? = null)

    private const val KEY = "tasteWeights"
    private val _transition = MutableStateFlow<Transition?>(null)
    /** The current or last transition; null before the first one of this run. */
    val transition: StateFlow<Transition?> = _transition
    @Volatile private var weights: Map<MixKind, KetmixTaste.Weight>? = null
    private var next = 1L

    /** Whether 👍/👎 should be shown at all. */
    val enabled: Boolean get() = KetmixPcSettings.learns.value

    /** 👍 ([up]) or 👎 on [transition]; a second rating of the same transition replaces the first one's effect only once. */
    @Synchronized fun rate(up: Boolean) {
        val t = _transition.value ?: return
        if (!enabled || t.rating == up) return
        var w = load()
        // Changing one's mind: the opposite vote first undoes the earlier one's direction.
        if (t.rating != null) w = KetmixTaste.rate(w, t.kind, up)
        w = KetmixTaste.rate(w, t.kind, up)
        weights = w
        KetmixPcSettings.putString(KEY, KetmixTaste.encode(w))
        _transition.value = t.copy(rating = up)
    }

    /** The kinds Ketmix leaves out of its automatic choices now. */
    fun avoid(): Set<MixKind> = if (enabled) KetmixTaste.avoid(load()) else emptySet()

    /** Forgets everything learnt (Settings). */
    @Synchronized fun reset() { weights = emptyMap(); KetmixPcSettings.putString(KEY, null) }

    internal fun planned(plan: MixPlan, from: Track?, to: Track?) {
        if (!plan.mixes) return
        _transition.value = Transition(next++, from, to, plan.kind, com.ketvoice.app.automix.MixWords.label(plan), playing = false)
    }

    internal fun playing(on: Boolean) { _transition.value?.let { if (it.playing != on) _transition.value = it.copy(playing = on) } }

    @Synchronized private fun load(): Map<MixKind, KetmixTaste.Weight> = weights ?: KetmixTaste.decode(KetmixPcSettings.string(KEY)).also { weights = it }

    /** Test seam. */
    internal fun resetForTest() { weights = null; _transition.value = null }
}
