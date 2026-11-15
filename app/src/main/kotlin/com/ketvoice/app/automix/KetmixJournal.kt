package com.ketvoice.app.automix

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What Ketmix did at each transition, kept in memory (the last [LIMIT]): the two titles, what
 * was measured of them, the transition chosen and why, and whether the levels and filters could
 * be tied to the music or a plain fade on the volume had to be used. The listener can copy it
 * from the settings and send it: it is how a mix that sounded wrong on a phone can be understood.
 */
internal object KetmixJournal {
    const val LIMIT = 25

    data class Entry(
        val at: Long,
        val from: String,
        val to: String,
        /** "mixed" or the reason the pair was not mixed. */
        val outcome: String,
        val kind: MixKind = MixKind.None,
        val reason: String = "",
        val outBpm: Float = 0f, val outConfidence: Float = 0f, val outKey: Int = MusicalKey.UNREAD,
        val inBpm: Float = 0f, val inConfidence: Float = 0f, val inKey: Int = MusicalKey.UNREAD,
        val rate: Float = 1f,
        val overlapMs: Long = 0L,
        val onPhrase: Boolean = false,
        /** The decks' processors were in the sound path and the clock was confirmed: levels and filters follow the music. */
        val precise: Boolean? = null,
        val clock: Boolean? = null,
        val sungUntilMs: Long? = null,
        val sungFromMs: Long? = null,
        val analysis: String = "",
        /** Where the outgoing title's sound ends, and how ("hard", "fade", "rings"). */
        val outEndMs: Long? = null,
        val ending: String = "",
        /** Where the mix starts in the outgoing title, and where the incoming title is then. */
        val mixAtMs: Long? = null,
        val inAtMs: Long? = null,
        /** The processors of each deck were in its sound path. */
        val pathOut: Boolean? = null,
        val pathIn: Boolean? = null,
        /** The transition rang on past the end of the outgoing title. */
        val tail: Boolean = false,
        /** While both titles were heard in time: the largest gap between their beats, and how often a speed was changed. */
        val syncWorstMs: Int? = null,
        val speedChanges: Int? = null
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun add(entry: Entry) { _entries.value = (listOf(entry) + _entries.value).take(LIMIT) }

    fun clear() { _entries.value = emptyList() }

    /** Corrects the newest entry: what happened once the mix played. */
    fun amend(update: (Entry) -> Entry) {
        val list = _entries.value
        if (list.isNotEmpty()) _entries.value = listOf(update(list.first())) + list.drop(1)
    }

    private fun seconds(ms: Long) = "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)

    private fun beat(bpm: Float, confidence: Float) = if (bpm <= 0f) "no beat" else "%.1f BPM (%.0f %%)".format(java.util.Locale.ROOT, bpm, confidence * 100)
    private fun key(key: Int) = MusicalKey.code(key).ifEmpty { if (key == MusicalKey.UNREAD) "key not read" else "no key" }

    /** One line per transition, newest first, in English: meant to be pasted in a message. */
    fun text(entries: List<Entry> = _entries.value): String = buildString {
        append("Ketmix journal (").append(entries.size).append(")\n")
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
        for (e in entries) {
            append(time.format(java.util.Date(e.at))).append("  ").append(e.from).append(" → ").append(e.to).append('\n')
            append("  ").append(e.outcome)
            if (e.kind != MixKind.None) append(" · ").append(e.kind.name).append(" (").append(e.reason).append(')')
                .append(" · overlap ").append(e.overlapMs / 1000.0).append(" s")
                .append(" · rate ").append("%.3f".format(java.util.Locale.ROOT, e.rate))
                .append(if (e.onPhrase) " · on a phrase" else "")
            append('\n')
            append("  out ").append(beat(e.outBpm, e.outConfidence)).append(", ").append(key(e.outKey))
            append(" · in ").append(beat(e.inBpm, e.inConfidence)).append(", ").append(key(e.inKey)).append('\n')
            append("  precise ").append(e.precise ?: "-").append(" · clock ").append(e.clock ?: "-")
            e.sungUntilMs?.let { append(" · singing ends ").append(it / 1000).append(" s") }
            e.sungFromMs?.let { append(" · next sings at ").append(it / 1000).append(" s") }
            if (e.analysis.isNotEmpty()) append(" · ").append(e.analysis)
            append('\n')
            if (e.outEndMs != null || e.mixAtMs != null || e.pathOut != null) {
                append(" ")
                e.outEndMs?.let { append(" out ends ").append(seconds(it)); if (e.ending.isNotEmpty()) append(" (").append(e.ending).append(')') }
                e.mixAtMs?.let { append(" · mix at ").append(seconds(it)) }
                e.inAtMs?.let { append(" → in ").append(seconds(it)) }
                if (e.tail) append(" · rings past the end")
                e.syncWorstMs?.let { append(" · sync within ").append(it).append(" ms") }
                e.speedChanges?.let { append(" · ").append(it).append(" speed changes") }
                if (e.pathOut != null || e.pathIn != null)
                    append(" · paths out ").append(if (e.pathOut == true) "on" else "off").append(", in ").append(if (e.pathIn == true) "on" else "off")
                append('\n')
            }
        }
    }
}
