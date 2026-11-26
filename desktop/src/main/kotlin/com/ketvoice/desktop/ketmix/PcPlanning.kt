package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.SongStructure
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.MusicalKey
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.SmartQueue
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player

/**
 * What « Maximum » adds to a transition's plan (Windows 2.0), pure: the sections of both titles become the places the
 * planner lands on ([SegmentAnalysis.sectionStartsMs]), S-KEY's key replaces the chroma estimate when it is surer, and
 * the energy of the outgoing ending against the incoming start becomes [com.ketvoice.app.automix.MixContext.energyStep].
 * Without facts everything stays as Ketmix had it (and the phone never has them).
 */
internal object PcInputs {
    /** S-KEY's probability above which its key is taken over the chroma estimate. */
    const val KEY_CONFIDENCE = .3f
    private const val ENERGY_SPAN_MS = 20_000L

    fun analysis(a: SegmentAnalysis?, facts: TrackFacts?): SegmentAnalysis? {
        if (a == null || facts == null) return a
        val starts = SongStructure.starts(facts.sections).takeIf { it.isNotEmpty() }
        val neural = facts.key.takeIf { it in 0..23 && facts.keyConfidence >= KEY_CONFIDENCE }
        return a.copy(sectionStartsMs = starts ?: a.sectionStartsMs,
            key = neural ?: a.key, keyStrength = if (neural != null) maxOf(a.keyStrength, MusicalKey.MIN_STRENGTH) else a.keyStrength)
    }

    /** Energy of the incoming start minus that of the outgoing ending (NaN when either is unknown). */
    fun energyStep(out: TrackFacts?, incoming: TrackFacts?, outro: SegmentAnalysis?, intro: SegmentAnalysis?): Float {
        if (out == null || incoming == null) return Float.NaN
        val outEnd = outro?.soundEndMs ?: out.durationMs
        val inStart = intro?.soundStartMs ?: 0L
        val before = EnergyCurve.around(out.energy, outEnd - ENERGY_SPAN_MS, outEnd - 2_000L)
        val after = EnergyCurve.around(incoming.energy, inStart + 2_000L, inStart + ENERGY_SPAN_MS)
        return after - before
    }
}

/**
 * « Ordre par le son » (Windows 2.0, « Maximum »): in shuffle with « File intelligente » on, the next title is the one
 * among the coming ones that sounds closest to the current title (CLAP embeddings), then fits its tempo and key
 * ([SmartQueue.distance]) and its energy; [suggest] ranks any titles the same way for the interface.
 */
internal object SoundQueue {
    /** 0 = a perfect follower, larger = worse; null when nothing is known of the pair. */
    fun score(sound: Float?, tempoKey: Float?, energyStep: Float): Float? {
        if (sound == null && tempoKey == null) return null
        val s = sound?.let { (1f - it).coerceIn(0f, 2f) } ?: .5f
        val t = tempoKey ?: .05f
        val e = if (energyStep.isNaN()) 0f else kotlin.math.abs(energyStep)
        return s + 2f * t + .5f * e
    }

    /** Index among [scores] (the coming titles, in order) to play next; 0 keeps the order. A clear margin is needed. */
    fun pick(scores: List<Float?>): Int {
        val best = scores.withIndex().filter { it.value != null }.minByOrNull { it.value!! } ?: return 0
        val first = scores.firstOrNull()
        val clear = if (first == null) best.value!! <= .35f else first - best.value!! >= .08f
        return if (best.index != 0 && clear) best.index else 0
    }

    private fun scoreOf(current: Track, durationMs: Long, candidate: Track): Float? {
        val mine = TrackFactsStore.get(current.id, durationMs)
        val theirs = TrackFactsStore.get(candidate.id, candidate.durationMs)
        val outro = AnalysisStore.outro(current.id, durationMs)
        val intro = AnalysisStore.intro(candidate.id, candidate.durationMs.takeIf { it > 0 } ?: 600_000L)
        return score(SoundPrint.cosine(mine?.sound, theirs?.sound), SmartQueue.distance(PcInputs.analysis(outro, mine), PcInputs.analysis(intro, theirs)),
            PcInputs.energyStep(mine, theirs, outro, intro))
    }

    /** [candidates] best first, with their scores (titles nothing is known of are left out). */
    fun suggest(current: Track, durationMs: Long, candidates: List<Track>): List<Pair<Track, Float>> =
        candidates.filter { it.id != current.id }.mapNotNull { c -> scoreOf(current, durationMs, c)?.let { c to it } }.sortedBy { it.second }

    /** Moves the best follower right after the current title (shuffle, « File intelligente », « Maximum »). */
    fun reorder(current: Track, durationMs: Long): Boolean {
        val state = Player.state.value
        if (!AppSettings.ketmixSmartQueue.value || !state.shuffle || state.repeatOne || !KetmixPc.maximum()) return false
        val coming = Player.automixUpcoming(SmartQueue.WINDOW)
        if (coming.size < 2 || coming.first().index != state.index + 1 || Player.pinnedNext(coming.first().entryId)) return false
        val choice = pick(coming.map { scoreOf(current, durationMs, it.track) })
        if (choice == 0 || coming[choice].index <= state.index + 1) return false
        Player.moveQueue(coming[choice].index, state.index + 1)
        return true
    }
}
