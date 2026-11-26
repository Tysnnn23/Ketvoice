package com.ketvoice.app.automix

import com.ketvoice.app.LiteTuning
import com.ketvoice.app.automix.SongStructure.Label
import com.ketvoice.app.automix.SongStructure.Section

/**
 * The song's sections on the phone (9.0): [SongStructure] run on a stretch Ketmix already reads (a title's start or
 * end), from the cheap band energies [OnsetTracker] keeps while it reads ([TimbreBands]), on the bars of the stretch's
 * beat grid (the beat model's when it ran). A stretch is not the whole title: its labels are those of the stretch,
 * so an ending has no intro and a start no outro.
 *
 * The planner then leaves a title where a section starts (the end of a chorus, the start of the outro) rather than in
 * the middle of one, never right as a chorus begins, and lets the next title come in where its intro ends.
 */
internal object SongSections {
    /** Not on Light (the least work) nor in Ketvoice Lite, whose budget is kept for the beat itself. */
    fun wanted(depth: AnalysisDepth, lite: Boolean = LiteTuning.lite): Boolean = depth != AnalysisDepth.Light && !lite

    /** The first beats of the bars of [a]'s grid inside its stretch, ms; empty without bars. */
    fun bars(a: SegmentAnalysis): List<Double> {
        if (!a.hasBeat || a.barOffset < 0) return emptyList()
        val bar = a.beatMs * 4.0
        val end = (a.startMs + a.lengthMs).toDouble()
        val out = ArrayList<Double>()
        var at = a.barAtOrAfter(a.startMs.toDouble())
        while (at < end - bar / 2 && out.size < 1_000) { out += at; at += bar }
        return out
    }

    /**
     * The sections of the stretch [a] was read from; [ending] when it is the end of the title. Empty when the stretch
     * has too few bars to tell, null when it could not be looked at (no band energies, no bars).
     */
    fun of(envelope: OnsetEnvelope, a: SegmentAnalysis, ending: Boolean): List<Section>? {
        val timbre = envelope.timbre?.takeIf { it.isNotEmpty() && envelope.timbreFps > 0f } ?: return null
        return read(timbre, TimbreBands.BANDS, envelope.timbreFps, a.startMs.toDouble(), a, ending)
    }

    /** The same from the beat model's log-mel frames, when they cover the whole stretch (else null: the band energies do). */
    fun of(mels: BeatModel.Mels, a: SegmentAnalysis, ending: Boolean): List<Section>? {
        val fps = BeatSpectrogram.RATE.toFloat() / BeatSpectrogram.HOP
        val untilMs = mels.originMs + mels.frames.size / BeatSpectrogram.MELS / fps * 1000.0
        if (mels.originMs > a.startMs + COVER_MS || untilMs < a.startMs + a.lengthMs - COVER_MS) return null
        return read(mels.frames, BeatSpectrogram.MELS, fps, mels.originMs, a, ending)
    }

    /** [frames] of [bands] values at [fps], the first at [originMs] in the title. */
    private fun read(frames: FloatArray, bands: Int, fps: Float, originMs: Double, a: SegmentAnalysis, ending: Boolean): List<Section>? {
        val bars = bars(a).filter { it >= originMs - 1.0 }.takeIf { it.isNotEmpty() } ?: return null
        val end = a.startMs + a.lengthMs
        val lengthMs = (end - originMs).toLong()
        val found = SongStructure.sections(frames, bands, fps, bars.map { it - originMs }, lengthMs)
        fun inTitle(ms: Long) = (ms + originMs).toLong().coerceIn(a.startMs, end)
        return placed(found.mapIndexed { i, s -> Section(if (i == 0) a.startMs else inTitle(s.startMs), inTitle(s.endMs), s.label) }, ending)
    }

    /** How far the model's frames may fall short of the stretch at either end. */
    private const val COVER_MS = 1_500L

    /** An ending has no intro and a start no outro: there the stretch, not the title, begins or stops. */
    internal fun placed(sections: List<Section>, ending: Boolean): List<Section> = sections.mapIndexed { i, s ->
        when {
            ending && i == 0 && s.label == Label.Intro -> s.copy(label = Label.Verse)
            !ending && i == sections.lastIndex && s.label == Label.Outro -> s.copy(label = Label.Verse)
            else -> s
        }
    }

    /**
     * Where the outgoing title may be left between [fromMs] and [untilMs]: the starts of its sections (the end of
     * the one before), except a chorus's own start, which would be faded over as it begins. Null without sections.
     */
    fun exits(a: SegmentAnalysis, fromMs: Double, untilMs: Double): List<Double>? {
        val sections = a.sections?.takeIf { it.size > 1 } ?: return null
        return sections.drop(1).filter { it.label != Label.Chorus }.map { it.startMs.toDouble() }
            .filter { it >= fromMs - 1e-6 && it <= untilMs + 1e-6 }.sorted()
    }

    /** True when [ms] falls on the first bar of one of [a]'s choruses (from half a beat before its start). */
    fun opensChorus(a: SegmentAnalysis, ms: Double): Boolean {
        val sections = a.sections ?: return false
        val bar = if (a.bpm > 0f) a.beatMs * 4.0 else 2_000.0
        val early = if (a.bpm > 0f) a.beatMs / 2.0 else 250.0
        return sections.any { it.label == Label.Chorus && ms >= it.startMs - early && ms < it.startMs + bar }
    }

    /**
     * Where the incoming title's intro gives way (its next section), else where its first chorus starts: the place
     * a cut or a drop may land on. Null without sections, or when the title opens on its strong part.
     */
    fun entry(b: SegmentAnalysis): Long? {
        val sections = b.sections?.takeIf { it.size > 1 } ?: return null
        if (sections.first().label == Label.Intro) return sections[1].startMs
        return sections.drop(1).firstOrNull { it.label == Label.Chorus }?.startMs
    }

    /** As kept on the device: start, end and label of each section. */
    fun encode(sections: List<Section>): org.json.JSONArray = org.json.JSONArray().also { array ->
        for (s in sections) array.put(s.startMs).put(s.endMs).put(s.label.ordinal)
    }

    /** What [encode] wrote, inside [startMs]..[endMs]; null when it does not read as sections. */
    fun decode(array: org.json.JSONArray?, startMs: Long, endMs: Long): List<Section>? {
        array ?: return null
        if (array.length() % 3 != 0 || array.length() > 3 * 64) return null
        val out = ArrayList<Section>()
        for (i in 0 until array.length() / 3) {
            val from = array.optLong(3 * i, -1); val until = array.optLong(3 * i + 1, -1)
            val label = Label.values().getOrNull(array.optInt(3 * i + 2, -1)) ?: return null
            if (from < startMs || until < from || until > endMs || (out.isNotEmpty() && from < out.last().startMs)) return null
            out += Section(from, until, label)
        }
        return out
    }
}
