package com.ketvoice.app.smartlib

import com.ketvoice.app.data.Track

/**
 * The match report of a playlist import (Windows 2.0 « Importer en un clic »; pure, the phone can show it later): each
 * title is found (sure), uncertain (found, but the match could be another version) or missing, the listener may fix
 * any of them by hand, and the rate is what will be playable in the new playlist.
 */
object ImportReport {
    /** From this score on, a match is sure (the same ISRC, or title, artist and length agreeing). */
    const val SURE = .9f

    enum class Kind { Matched, Uncertain, Missing }

    data class Summary(val total: Int, val matched: Int, val uncertain: Int, val missing: Int, val fixed: Int) {
        /** The share of titles that will play, in whole percent (0 for an empty playlist). */
        val rate: Int get() = if (total == 0) 0 else Math.round((matched + uncertain) * 100f / total)
    }

    fun kind(match: ImportMatch): Kind = when {
        match.track == null -> Kind.Missing
        match.score >= SURE -> Kind.Matched
        else -> Kind.Uncertain
    }

    /**
     * [matches] with the listener's hand fixes applied ([fixes]: index → the title they picked, or null to leave that
     * title out of the playable ones): a fixed title counts as sure.
     */
    fun applied(matches: List<ImportMatch>, fixes: Map<Int, Track?>): List<ImportMatch> = matches.mapIndexed { i, m ->
        if (i !in fixes) m else ImportMatch(m.song, fixes[i]?.copy(streamUrl = null, downloadUrl = null), if (fixes[i] != null) 1f else 0f)
    }

    fun summary(matches: List<ImportMatch>, fixes: Map<Int, Track?> = emptyMap()): Summary {
        val all = applied(matches, fixes)
        val kinds = all.map(::kind)
        return Summary(all.size, kinds.count { it == Kind.Matched }, kinds.count { it == Kind.Uncertain }, kinds.count { it == Kind.Missing },
            fixes.count { (i, t) -> t != null && i in matches.indices })
    }
}
