package com.ketvoice.app.automix

/**
 * « Mixer cette playlist » (Ketvoice TV 1.1): a list played as one continuous mix. Ketmix measures its first titles
 * ahead ([budget], set by the analysis setting), then the order is changed gently so tempo and key flow: from the
 * first title, each next one is the best match among the next [WINDOW] titles of the list's own order, taken only when
 * it is clearly better than the one already next (SmartQueue's margins), so a title moves a few places at most. Titles
 * the listener pinned (the one they started from) keep their place; unmeasured titles keep their order. Pure, so
 * the host tests check it.
 */
internal object PlaylistMix {
    /** How many coming titles of the list's own order are looked at for each place. */
    const val WINDOW = 4

    /**
     * How many titles are measured before playing: fewer on a light analysis, never the whole of a long list (each read
     * takes a few seconds; the titles after them are prepared during playback, as Ketmix always does).
     */
    fun budget(depth: AnalysisDepth, size: Int): Int = minOf(size, when (depth) {
        AnalysisDepth.Light -> 4
        AnalysisDepth.Balanced -> 6
        AnalysisDepth.Maximum -> 8
    })

    /**
     * The new order, as indices into the list. [intros] are the titles' measured starts, [endings] their measured ends
     * (a title's start stands in when its end is not known: the tempo and key rarely change inside a title).
     */
    fun order(intros: List<SegmentAnalysis?>, endings: List<SegmentAnalysis?>, pinned: Set<Int>): List<Int> {
        val n = intros.size
        val placed = IntArray(n) { -1 }
        pinned.filter { it in 0 until n }.forEach { placed[it] = it }
        val waiting = ArrayDeque((0 until n).filter { placed[it] == -1 })
        var last = -1
        for (place in 0 until n) {
            if (placed[place] == -1) placed[place] = next(waiting, last, intros, endings)
            last = placed[place]
        }
        return placed.toList()
    }

    private fun next(waiting: ArrayDeque<Int>, last: Int, intros: List<SegmentAnalysis?>, endings: List<SegmentAnalysis?>): Int {
        val candidates = waiting.take(WINDOW)
        val out = if (last >= 0) endings.getOrNull(last) ?: intros[last] else null
        val choice = if (out == null || candidates.size < 2) 0 else SmartQueue.pick(out, candidates.map { intros[it] })
        val chosen = candidates[choice]
        waiting.remove(chosen)
        return chosen
    }

    /** The order applied to [items]. */
    fun <T> apply(items: List<T>, order: List<Int>): List<T> = order.map { items[it] }
}
