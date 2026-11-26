package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.KetmixTaste
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
