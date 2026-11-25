package com.ketvoice.app.data

/**
 * How rich a set of lyrics is (Windows 2.0 lyrics engine; pure, so the phone can use it later): word timing beats
 * syllable timing, which beats line timing, which beats plain text; lyrics timed word by word by the app's AI come
 * after a provider's own word or syllable timing but before line timing. Between two answers of the same level, the
 * more complete one wins, then the earlier source in the order asked.
 */
object LyricsRank {
    const val PLAIN = 1
    const val LINE = 2
    const val AI_WORD = 3
    const val SYLLABLE = 4
    const val WORD = 5

    /** The level of [lines]; 0 when there are none. [ai] marks word timing found by the app itself. */
    fun level(lines: List<MusicApi.LyricLine>, ai: Boolean = false): Int = when {
        lines.isEmpty() -> 0
        lines.any { it.words.size >= 2 } && ai -> AI_WORD
        lines.any { it.words.size >= 2 && !it.syllableTimed } -> WORD
        lines.any { it.syllableTimed && it.words.isNotEmpty() } -> SYLLABLE
        lines.any { it.timeMs >= 0 } -> LINE
        else -> PLAIN
    }

    /** The French name of a level, for the lyrics' source menu. */
    fun label(level: Int): String = when (level) {
        WORD -> "mot par mot"
        SYLLABLE -> "syllabe par syllabe"
        AI_WORD -> "mot par mot (IA)"
        LINE -> "ligne par ligne"
        PLAIN -> "texte non synchronisé"
        else -> "indisponible"
    }

    /** One source's answer. [order] is its place among the sources asked (lower first). */
    data class Candidate(val source: String, val label: String, val lines: List<MusicApi.LyricLine>, val order: Int) {
        val level: Int get() = level(lines)
    }

    /**
     * The answer to show: the one the listener chose for this title ([chosen], a source id) when it has lyrics, else
     * the richest timing; at the same level an answer with fewer than 55 % of the other's lines loses (a fragment),
     * then the earlier source wins.
     */
    fun best(candidates: List<Candidate>, chosen: String? = null): Candidate? {
        val usable = candidates.filter { it.lines.isNotEmpty() }
        if (chosen != null) usable.firstOrNull { it.source == chosen }?.let { return it }
        return usable.sortedWith(compareByDescending<Candidate> { it.level }.thenBy { it.order }).fold(null as Candidate?) { kept, next ->
            when {
                kept == null -> next
                next.level == kept.level && kept.lines.size < next.lines.size * .55 -> next
                else -> kept
            }
        }
    }
}
