package com.ketvoice.app.data

import android.content.Context
import androidx.core.content.edit

/**
 * Which poster the "For you" card at the top of Home shows. Fresh releases and personal mixes take
 * turns: the one shown the fewest times comes first, never the same one twice in a row, so the
 * card does not show the same release at every launch for three weeks.
 */
object ForYouRotation {
    private var prefs: android.content.SharedPreferences? = null
    /** What this launch shows: kept while it is still a candidate, so the card does not change under the eyes. */
    private var launchChoice: String? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("for-you", Context.MODE_PRIVATE)
    }

    /** The candidate to show among [candidates] (ids such as "release:…" or "mix:…"); recorded as shown once per launch. */
    @Synchronized fun choose(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        launchChoice?.takeIf { it in candidates }?.let { return it }
        val counted = launchChoice != null
        val store = prefs
        val seen = store?.getString("seen", null)?.let(::decode).orEmpty()
        val last = store?.getString("last", null)
        val chosen = pick(candidates, seen, last, System.currentTimeMillis() / 3_600_000L) ?: return null
        launchChoice = chosen
        // One show per launch is counted, whatever the card has to change to later.
        if (!counted) store?.edit(commit = false) { putString("seen", encode(shown(seen, chosen, candidates))); putString("last", chosen) }
        return chosen
    }

    /** For tests: a new launch. */
    internal fun newLaunchForTest() { launchChoice = null }

    /** The least shown candidate, not [last] when there is another; ties take turns with [seed]. */
    internal fun pick(candidates: List<String>, seen: Map<String, Int>, last: String?, seed: Long): String? {
        val pool = candidates.distinct().filter { it != last }.ifEmpty { candidates.distinct() }
        if (pool.isEmpty()) return null
        val fewest = pool.minOf { seen[it] ?: 0 }
        val tied = pool.filter { (seen[it] ?: 0) == fewest }
        return tied[Math.floorMod(seed, tied.size.toLong()).toInt()]
    }

    /** Counts after [chosen] was shown; candidates that are gone are forgotten, so the list never grows. */
    internal fun shown(seen: Map<String, Int>, chosen: String, candidates: List<String>): Map<String, Int> =
        seen.filterKeys { it in candidates } + (chosen to ((seen[chosen] ?: 0) + 1))

    internal fun encode(seen: Map<String, Int>): String = seen.entries.joinToString("\n") { "${it.value}\t${it.key}" }
    internal fun decode(text: String): Map<String, Int> = text.lines().mapNotNull { line ->
        val count = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
        line.substringAfter('\t', "").takeIf { it.isNotEmpty() }?.let { it to count }
    }.toMap()
}
