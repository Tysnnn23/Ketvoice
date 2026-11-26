package com.ketvoice.app.automix

import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * « Ketmix apprend de vos avis » on the phone (optional, off by default, as on Windows 2.0): the listener's 👍/👎 on
 * the last transition move [KetmixTaste]'s weights, kept in this phone's settings only, and the kinds turned down
 * are left out of Ketmix's automatic style ([avoid], read into [MixContext.avoid]). A style picked by hand is never
 * overridden: the planner only honours [MixContext.avoid] for [MixStyle.Auto]. A rating given while the setting is
 * off counts for nothing.
 */
internal object KetmixLearning {
    /** A transition heard: [from] and [to] are « title — artist »; [rating] true 👍, false 👎, null not rated yet. */
    data class Transition(val id: Long, val from: String, val to: String, val kind: MixKind, val label: String,
                          val rating: Boolean? = null)

    /** How long 👍/👎 stays on the player after a transition. */
    const val ASK_MS = 9_000L
    /** How long the thanks stays once the listener rated. */
    const val THANKS_MS = 1_600L

    private val _last = MutableStateFlow<Transition?>(null)
    /** The transition playing or the last one played in this run; null before the first one. */
    val last: StateFlow<Transition?> = _last
    private var next = 1L
    /** Where the weights are written once changed (the settings file; a test seam). */
    internal var persist: () -> Unit = { SettingsStore.save() }

    /** Whether 👍/👎 are shown and counted at all. */
    val enabled: Boolean get() = AppSettings.ketmixLearns.value

    /** The kinds Ketmix's automatic style leaves out now; empty while the setting is off. */
    fun avoid(): Set<MixKind> = if (enabled) KetmixTaste.avoid(weights()) else emptySet()

    /** 👍 ([up]) or 👎 on [last]. Changing one's mind first undoes the earlier rating's direction. */
    @Synchronized fun rate(up: Boolean) {
        val t = _last.value ?: return
        if (!enabled || t.rating == up) return
        var w = weights()
        if (t.rating != null) w = KetmixTaste.rate(w, t.kind, up)
        w = KetmixTaste.rate(w, t.kind, up)
        AppSettings.ketmixTaste.value = KetmixTaste.encode(w)
        _last.value = t.copy(rating = up)
        runCatching { persist() }
    }

    /** Forgets everything learnt (Settings). */
    @Synchronized fun reset() {
        AppSettings.ketmixTaste.value = ""
        _last.value = _last.value?.copy(rating = null)
        runCatching { persist() }
    }

    /** A transition starts to be heard (from [Automix]); a title simply following another is not one. */
    internal fun played(plan: MixPlan, from: String, to: String) {
        if (!plan.mixes) return
        _last.value = Transition(next++, from, to, plan.kind, MixWords.label(plan))
    }

    private fun weights(): Map<MixKind, KetmixTaste.Weight> = KetmixTaste.decode(AppSettings.ketmixTaste.value)

    /** Test seam. */
    internal fun resetForTest() { _last.value = null }
}
