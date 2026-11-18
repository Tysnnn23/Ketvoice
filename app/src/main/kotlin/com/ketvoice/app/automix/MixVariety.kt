package com.ketvoice.app.automix

/**
 * Variety (6.9): DJs warn that one transition played on every title soon becomes the only thing anyone hears.
 * Ketmix remembers the kinds of its last transitions in a listening and, when the same kind would be played a
 * third time running, lets another one that fits this pair just as well take a turn (a tie-breaker, never a
 * worse transition: see [TransitionPlanner]). The colours of a kind already alternate ([MixPlan.variant]).
 */
internal object MixVariety {
    /** How many transitions are remembered. */
    const val REMEMBERED = 3
    /** The same kind this many times running is enough. */
    private const val RUN = 2

    /** True when [kind] closed each of the last two transitions. */
    fun tired(recent: List<MixKind>, kind: MixKind): Boolean = recent.size >= RUN && recent.takeLast(RUN).all { it == kind }

    /** [recent] with [plan]'s kind added, the oldest forgotten; a transition that did not mix is not counted. */
    fun remember(recent: List<MixKind>, plan: MixPlan): List<MixKind> =
        if (!plan.mixes) recent else (recent + plan.kind).takeLast(REMEMBERED)
}
