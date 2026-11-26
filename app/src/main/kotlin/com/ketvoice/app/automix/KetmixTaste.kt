package com.ketvoice.app.automix

/**
 * « Ketmix apprend de vos avis » (Windows 2.0, Android 9.x; optional, off by default): 👍/👎 on a transition move a weight per kind
 * of transition (blend in time, echo, build, filters…), kept on this device only. A kind the listener has turned down
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
