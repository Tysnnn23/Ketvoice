package com.ketvoice.app.player

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.pow

/**
 * « Égaliseur par casque » (9.0): a correction for one pair of headphones, a preamp and up to [MAX_FILTERS] filters.
 * The listener brings it (a parametric equalizer file, as Equalizer APO and the headphone EQ projects publish them);
 * Ketvoice ships no measurement data of its own (see docs/licenses/AutoEq-review.md).
 */
data class HeadphoneProfile(val id: String, val name: String, val preampDb: Float, val filters: List<EqFilter>) {
    /** The gain of the filters alone at [hz], in dB. */
    fun responseDb(hz: Double, sampleRate: Int = REFERENCE_RATE): Double =
        filters.sumOf { Biquad.of(it, sampleRate).magnitudeDb(hz, sampleRate) }

    /** The highest gain the filters reach between 20 Hz and 20 kHz (1/48 octave apart), never below 0 dB. */
    fun peakDb(sampleRate: Int = REFERENCE_RATE): Double {
        var peak = 0.0
        val top = minOf(20_000.0, Biquad.maxHz(sampleRate))
        var hz = 20.0
        while (hz <= top) { peak = max(peak, responseDb(hz, sampleRate)); hz *= STEP }
        return peak
    }

    /** The preamp played: the profile's own, lowered further if its filters would still lift anything above full scale. */
    fun safePreampDb(sampleRate: Int = REFERENCE_RATE): Float = (minOf(preampDb.toDouble(), -peakDb(sampleRate)) + 0.0).toFloat()

    companion object {
        const val MAX_FILTERS = 20
        const val REFERENCE_RATE = 48_000
        private val STEP = 2.0.pow(1.0 / 48)
    }
}

/** Reading and keeping the listener's corrections: the parametric text they bring, and the stored JSON. */
object HeadphoneProfiles {
    const val MAX_PROFILES = 40
    /** The stored list of profiles: a bound on what a backup or the account's sync may carry. */
    const val MAX_STORED_CHARS = 64_000
    const val MAX_NAME = 80

    private val number = "([-+]?\\d+(?:[.,]\\d+)?)"
    private val preampRe = Regex("^\\s*Preamp\\s*:\\s*$number\\s*dB", RegexOption.IGNORE_CASE)
    private val filterRe = Regex("^\\s*Filter\\s*\\d*\\s*:\\s*(ON|OFF)\\s+([A-Z]+)(?:\\s+\\d+\\s*dB)?\\s+Fc\\s+$number\\s*Hz" +
        "(?:\\s+Gain\\s+$number\\s*dB)?(?:\\s+Q\\s+$number)?", RegexOption.IGNORE_CASE)

    private fun num(text: String): Float? = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

    private fun typeOf(code: String): EqFilterType? = when (code.uppercase()) {
        "PK", "PEQ" -> EqFilterType.Peak
        "LS", "LSC", "LSQ" -> EqFilterType.LowShelf
        "HS", "HSC", "HSQ" -> EqFilterType.HighShelf
        else -> null
    }

    /** A filter within the ranges Ketvoice plays, or null. */
    fun checked(type: EqFilterType, hz: Float, gainDb: Float, q: Float): EqFilter? {
        if (hz !in 10f..24_000f || gainDb !in -30f..30f || q !in .05f..30f) return null
        return EqFilter(type, hz, gainDb, q)
    }

    /**
     * A correction from the parametric text of a file (« Preamp: -6.2 dB », « Filter 1: ON PK Fc 105 Hz Gain 2.1 dB
     * Q 0.70 »…). Filters turned OFF and kinds Ketvoice does not play are left out; shelves without a Q take 0.71.
     * Throws with a message for the listener when nothing usable is found.
     */
    fun parse(text: String, name: String, id: String = newId()): HeadphoneProfile {
        var preamp = 0f
        val filters = mutableListOf<EqFilter>()
        for (line in text.replace("\r", "").lines()) {
            preampRe.find(line)?.let { m -> num(m.groupValues[1])?.let { preamp = it.coerceIn(-30f, 0f) } }
            val m = filterRe.find(line) ?: continue
            if (!m.groupValues[1].equals("ON", ignoreCase = true)) continue
            val type = typeOf(m.groupValues[2]) ?: continue
            val hz = num(m.groupValues[3]) ?: continue
            val gain = m.groupValues[4].takeIf { it.isNotEmpty() }?.let(::num) ?: continue
            val q = m.groupValues[5].takeIf { it.isNotEmpty() }?.let(::num) ?: if (type == EqFilterType.Peak) continue else .71f
            checked(type, hz, gain, q)?.let { filters += it }
        }
        require(filters.isNotEmpty()) { "Aucun filtre paramétrique trouvé dans ce texte" }
        require(filters.size <= HeadphoneProfile.MAX_FILTERS) { "Trop de filtres (20 au maximum)" }
        return HeadphoneProfile(id, cleanName(name), preamp, filters)
    }

    fun cleanName(name: String): String = name.replace(Regex("\\s+"), " ").trim()
        .removeSuffix(".txt").removeSuffix(" ParametricEQ").removeSuffix(" ParametricEq").trim()
        .take(MAX_NAME).ifBlank { "Casque" }

    fun newId(): String = java.lang.Long.toString(System.nanoTime() xor (Math.random() * Long.MAX_VALUE).toLong(), 36)
        .removePrefix("-").take(12)

    private fun code(type: EqFilterType) = when (type) { EqFilterType.Peak -> "PK"; EqFilterType.LowShelf -> "LSC"; EqFilterType.HighShelf -> "HSC" }

    fun encode(profiles: List<HeadphoneProfile>): String = JSONArray().apply {
        profiles.forEach { p ->
            put(JSONObject().put("id", p.id).put("n", p.name).put("p", p.preampDb.toDouble()).put("f", JSONArray().apply {
                p.filters.forEach { f -> put(JSONArray().put(code(f.type)).put(f.hz.toDouble()).put(f.gainDb.toDouble()).put(f.q.toDouble())) }
            }))
        }
    }.toString()

    /** The stored profiles; what is not a valid profile is left out. */
    fun decode(text: String): List<HeadphoneProfile> {
        if (text.isBlank() || text.length > MAX_STORED_CHARS) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), MAX_PROFILES)).mapNotNull { i -> array.optJSONObject(i)?.let(::profile) }
            .distinctBy { it.id }
    }

    private fun profile(o: JSONObject): HeadphoneProfile? {
        val id = o.optString("id").takeIf { it.matches(Regex("[a-z0-9]{1,24}")) } ?: return null
        val name = o.optString("n").takeIf { it.isNotBlank() && it.length <= MAX_NAME && it.none(Char::isISOControl) } ?: return null
        val preamp = o.optDouble("p", Double.NaN).toFloat().takeIf { it.isFinite() && it in -30f..0f } ?: return null
        val list = o.optJSONArray("f") ?: return null
        if (list.length() !in 1..HeadphoneProfile.MAX_FILTERS) return null
        val filters = (0 until list.length()).map { j ->
            val f = list.optJSONArray(j) ?: return null
            val type = typeOf(f.optString(0)) ?: return null
            checked(type, f.optDouble(1).toFloat(), f.optDouble(2).toFloat(), f.optDouble(3).toFloat()) ?: return null
        }
        return HeadphoneProfile(id, name, preamp, filters)
    }

    /** True when [text] is a stored list Ketvoice can read back whole (for backups and the account's sync). */
    fun valid(text: String): Boolean {
        if (text.isEmpty()) return true
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return false
        return text.length <= MAX_STORED_CHARS && array.length() <= MAX_PROFILES && decode(text).size == array.length()
    }

    /** The profiles whose name holds every word of [query], whatever the case and accents. */
    fun search(profiles: List<HeadphoneProfile>, query: String): List<HeadphoneProfile> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        return profiles.filter { p -> val name = fold(p.name); words.all { it in name } }.sortedBy { fold(it.name) }
    }

    private fun fold(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
