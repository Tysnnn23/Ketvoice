package com.ketvoice.app.data

import com.ketvoice.app.util.moveOver

import java.io.File

/**
 * « Retélécharger en meilleure qualité » (7.3): what a saved file is, what a source offers, whether the
 * offer is really better, and a swap that never loses the saved file.
 */
internal object DownloadUpgrade {
    /** A rendition: lossless or not, its sample rate when known (a FLAC header, a provider's « 96 kHz »), its bitrate when lossy. */
    data class Rendition(val lossless: Boolean, val sampleRate: Int? = null, val kbps: Int = 0) {
        /** Short and untranslated: « FLAC 96 kHz », « FLAC », « 320 kb/s ». */
        val label: String get() = if (lossless) "FLAC" + (sampleRate?.let { " ${formatKhz(it)} kHz" } ?: "") else "$kbps kb/s"
    }

    /** A lossy offer must be at least this much richer to be worth a new download (128 → 160 is not). */
    const val MIN_KBPS_GAIN = 64

    private fun formatKhz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(java.util.Locale.ROOT, rate / 1000f)

    /** A saved file, from the first bytes of its audio and the format stored with it (« MP3 · 320 kbps », « FLAC · Sans perte »). */
    fun ofSaved(format: String, head: ByteArray?): Rendition {
        val rate = head?.let(::flacRate)
        if (rate != null) return Rendition(true, rate)
        val stored = MusicApi.StreamInfo(url = "", format = format, quality = format)
        return if (lossless(stored)) Rendition(true) else Rendition(false, kbps = StreamQuality.rank(stored))
    }

    /** What a source offers, as it describes it. */
    fun ofStream(stream: MusicApi.StreamInfo): Rendition {
        if (lossless(stream)) {
            val rate = Regex("(\\d{2,3}(?:[.,]\\d)?)\\s*kHz", RegexOption.IGNORE_CASE).find(stream.quality + " " + stream.format)
                ?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.let { kotlin.math.round(it * 1000).toInt() }
            return Rendition(true, rate)
        }
        return Rendition(false, kbps = StreamQuality.rank(stream))
    }

    private fun lossless(stream: MusicApi.StreamInfo): Boolean = StreamQuality.rank(stream) >= 10_000 ||
        stream.format.contains("wav", true) || stream.format.contains("alac", true)

    /**
     * Whether [offer] is better than [saved]: lossless beats lossy; between lossless files only a higher
     * sample rate counts, and only when both rates are known; between lossy ones, a clearly higher bitrate.
     */
    fun better(saved: Rendition, offer: Rendition): Boolean = when {
        offer.lossless && !saved.lossless -> true
        !offer.lossless && saved.lossless -> false
        offer.lossless -> saved.sampleRate != null && offer.sampleRate != null && offer.sampleRate > saved.sampleRate
        else -> offer.kbps >= saved.kbps + MIN_KBPS_GAIN
    }

    /**
     * Whether a downloaded replacement holds what was promised: a « .flac » must carry a real FLAC header,
     * and what its header says must still beat the saved file (a « 96 kHz » that is 44.1 kHz is refused).
     */
    fun verified(saved: Rendition, extension: String, promised: Rendition, head: ByteArray?): Boolean {
        val rate = head?.let(::flacRate)
        if (extension == "flac" && rate == null) return false
        val real = if (rate != null) Rendition(true, rate) else promised
        return better(saved, real)
    }

    /** The sample rate in a FLAC STREAMINFO header, null when [head] is not one. */
    fun flacRate(head: ByteArray): Int? {
        if (head.size < 21 || head[0] != 'f'.code.toByte() || head[1] != 'L'.code.toByte() ||
            head[2] != 'a'.code.toByte() || head[3] != 'C'.code.toByte() || head[4].toInt() and 0x7F != 0) return null
        val b = { i: Int -> head[i].toInt() and 0xFF }
        return ((b(18) shl 12) or (b(19) shl 4) or (b(20) shr 4)).takeIf { it > 0 }
    }

    fun head(file: File): ByteArray? = runCatching {
        file.inputStream().use { input -> ByteArray(21).also { if (input.read(it) < 21) return@runCatching null } }
    }.getOrNull()

    /**
     * Puts [fresh] in place of [old] as [target], and [metadata] in [meta], so that at every step either the
     * old file with its old metadata or the new file with the new metadata is what [meta] points to. On any
     * failure everything is put back and [fresh] is removed; the old file is removed only at the very end.
     * The lyrics, Canvas, cover and separations keep their names (they hang on the title, not on the file).
     */
    fun replace(old: File, fresh: File, target: File, meta: File, metadata: String): Boolean {
        if (!fresh.isFile || fresh.length() < 10_000 || !old.exists()) { fresh.delete(); return false }
        val sameName = target.path == old.path
        val backup = File(old.path + ".bak")
        if (sameName && !old.renameTo(backup)) { fresh.delete(); return false }
        if (!sameName && target.exists()) target.delete()
        if (!fresh.renameTo(target)) {
            if (sameName) backup.renameTo(old)
            fresh.delete(); return false
        }
        val pending = File(meta.path + ".new")
        val written = runCatching { pending.writeText(metadata, Charsets.UTF_8); pending.moveOver(meta) }.getOrDefault(false)
        if (!written) {
            pending.delete(); target.delete()
            if (sameName) backup.renameTo(old)
            return false
        }
        if (sameName) backup.delete() else old.delete()
        return true
    }

    /** After a crash in the middle of [replace]: a « .bak » whose file is gone goes back; one whose file is there is spare. */
    fun recover(dir: File) {
        dir.listFiles { f -> f.name.endsWith(".bak") }?.forEach { bak ->
            val original = File(bak.path.removeSuffix(".bak"))
            if (original.exists()) bak.delete() else bak.renameTo(original)
        }
        dir.listFiles { f -> f.name.endsWith(".json.new") || f.name.contains(".upgrade.") }?.forEach { it.delete() }
    }
}
