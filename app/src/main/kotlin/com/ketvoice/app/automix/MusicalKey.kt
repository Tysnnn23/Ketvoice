package com.ketvoice.app.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Collects, from the mono signal the analysis already works on, how much of each of the
 * twelve notes was heard: long windows (a third of a second), only the peaks of their
 * spectrum, each counted for the note it is closest to.
 */
internal class ChromaTracker(private val rate: Float) {
    private val frame = FloatArray(WINDOW)
    private var filled = 0
    private val real = FloatArray(WINDOW)
    private val imaginary = FloatArray(WINDOW)
    private val magnitude = FloatArray(WINDOW / 2)
    private val notes = FloatArray(12)
    private val sum = FloatArray(12)
    private val firstBin = (LOW_HZ / (rate / WINDOW)).toInt().coerceAtLeast(2)
    private val lastBin = (HIGH_HZ / (rate / WINDOW)).toInt().coerceAtMost(WINDOW / 2 - 2)
    var frames = 0
        private set

    fun sample(value: Float) {
        frame[filled++] = value
        if (filled == WINDOW) {
            emit()
            System.arraycopy(frame, HOP, frame, 0, WINDOW - HOP)
            filled = WINDOW - HOP
        }
    }

    private fun emit() {
        var energy = 0f
        for (i in 0 until WINDOW) { val v = frame[i]; energy += v * v; real[i] = v * HANN[i]; imaginary[i] = 0f }
        if (energy / WINDOW < 1e-7f) return
        Fft.transform(real, imaginary)
        var top = 0f
        for (bin in firstBin - 1..lastBin + 1) {
            magnitude[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            if (magnitude[bin] > top) top = magnitude[bin]
        }
        if (top <= 0f) return
        notes.fill(0f)
        val floor = top * .04f
        for (bin in firstBin..lastBin) {
            val m = magnitude[bin]
            if (m < floor || m <= magnitude[bin - 1] || m < magnitude[bin + 1]) continue
            // The peak lies between bins: a parabola through its neighbours says where.
            val left = magnitude[bin - 1]; val right = magnitude[bin + 1]
            val shift = (.5f * (left - right) / (left - 2f * m + right)).coerceIn(-.5f, .5f)
            val hz = (bin + shift) * rate / WINDOW
            val pitch = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)
            val nearest = pitch.roundToInt()
            val off = (pitch - nearest).toFloat()
            // A partial half-way between two notes belongs to neither.
            val weight = cos(PI * off).toFloat().let { it * it }
            notes[((nearest % 12) + 12) % 12] += m * weight
        }
        var peak = 0f
        for (v in notes) if (v > peak) peak = v
        if (peak <= 0f) return
        // Every window counts the same, so that one loud passage does not decide alone.
        for (i in 0 until 12) sum[i] += notes[i] / peak
        frames++
    }

    /** Notes from C to B, or null when too little was heard to tell. */
    fun snapshot(): FloatArray? = if (frames < MIN_FRAMES) null else sum.copyOf()

    private companion object {
        const val WINDOW = 4096
        const val HOP = 2048
        const val LOW_HZ = 100f
        const val HIGH_HZ = 2_000f
        const val MIN_FRAMES = 24
        val HANN = FloatArray(WINDOW) { (.5 - .5 * cos(2.0 * PI * it / (WINDOW - 1))).toFloat() }
    }
}

/**
 * The key of a stretch of music and whether two keys go together. A key is 0..11 for the
 * major keys from C to B and 12..23 for the minor ones; [UNREAD] when it was not looked
 * for, [NONE] when the music does not say.
 */
internal object MusicalKey {
    const val UNREAD = -2
    const val NONE = -1
    /** Under this the notes heard do not single out a key. */
    const val MIN_STRENGTH = .6f

    enum class Relation { Unknown, Same, Close, Clash }

    private val MAJOR = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MINOR = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

    /** The key whose usual weight of notes (Krumhansl and Kessler's profiles) fits best, and how well (−1..1). */
    fun estimate(chroma: FloatArray?): Pair<Int, Float> {
        if (chroma == null || chroma.size != 12) return NONE to 0f
        var best = NONE; var bestFit = -2f
        for (tonic in 0 until 12) {
            val major = correlation(chroma, MAJOR, tonic)
            if (major > bestFit) { bestFit = major; best = tonic }
            val minor = correlation(chroma, MINOR, tonic)
            if (minor > bestFit) { bestFit = minor; best = 12 + tonic }
        }
        return if (bestFit >= MIN_STRENGTH) best to bestFit else NONE to bestFit.coerceAtLeast(0f)
    }

    private fun correlation(chroma: FloatArray, profile: FloatArray, tonic: Int): Float {
        var meanC = 0f; var meanP = 0f
        for (i in 0 until 12) { meanC += chroma[i]; meanP += profile[i] }
        meanC /= 12f; meanP /= 12f
        var cross = 0f; var powerC = 0f; var powerP = 0f
        for (i in 0 until 12) {
            val c = chroma[(i + tonic) % 12] - meanC
            val p = profile[i] - meanP
            cross += c * p; powerC += c * c; powerP += p * p
        }
        val scale = sqrt(powerC * powerP)
        return if (scale > 1e-9f) cross / scale else 0f
    }

    /** Position on the wheel DJs use (1..12), where neighbours go together. */
    fun wheel(key: Int): Int {
        if (key !in 0..23) return 0
        // A minor key sits with its relative major, three semitones up.
        val tonic = if (key >= 12) (key - 12 + 3) % 12 else key
        return (tonic * 7 + 7) % 12 + 1
    }

    /** "8A", "9B"…: the wheel position and A for minor, B for major. Empty when the key is unknown. */
    fun code(key: Int): String = if (key !in 0..23) "" else "${wheel(key)}${if (key >= 12) 'A' else 'B'}"

    /**
     * Two keys go together when they are the same, neighbours on the wheel in the same mode,
     * or a major key and its relative minor.
     */
    fun relation(from: Int, to: Int): Relation {
        if (from !in 0..23 || to !in 0..23) return Relation.Unknown
        if (from == to) return Relation.Same
        val sameMode = (from >= 12) == (to >= 12)
        val steps = abs(wheel(from) - wheel(to)).let { minOf(it, 12 - it) }
        return when {
            sameMode && steps == 1 -> Relation.Close
            !sameMode && steps == 0 -> Relation.Close
            else -> Relation.Clash
        }
    }
}
