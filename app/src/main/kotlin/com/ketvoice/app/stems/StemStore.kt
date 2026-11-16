package com.ketvoice.app.stems

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The separations already computed, one small file per part of a title (its intro or its
 * ending): the network runs once for a title, later mixes only apply what it found.
 */
internal object StemStore {
    private const val MAGIC = 0x4B565331 // "KVS1", 5.10.0
    /** With the voice activity of each frame (5.11.0). */
    private const val MAGIC_VOICE = 0x4B565332
    /** Separations past this size are dropped, the least recently used first. */
    const val LIMIT_BYTES = 400L * 1024 * 1024
    @Volatile private var root: File? = null

    fun init(dir: File) { root = File(dir, "stems/masks").apply { mkdirs() } }

    private fun name(trackId: String, seconds: Long, intro: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }
        return "$digest-${if (intro) "i" else "o"}-$seconds.kvs"
    }

    private fun file(trackId: String, durationMs: Long, intro: Boolean): File? =
        root?.let { File(it, name(trackId, durationMs / 1000, intro)) }

    /**
     * The stored separation of this part. A duration read from a file's header and the one the player
     * reports may fall on either side of a second: the neighbouring seconds are looked at too.
     */
    private fun existing(trackId: String, durationMs: Long, intro: Boolean): File? {
        val dir = root ?: return null
        val seconds = durationMs / 1000
        return longArrayOf(seconds, seconds - 1, seconds + 1).map { File(dir, name(trackId, it, intro)) }.firstOrNull { it.isFile }
    }

    fun has(trackId: String, durationMs: Long, intro: Boolean): Boolean = existing(trackId, durationMs, intro) != null

    fun load(trackId: String, durationMs: Long, intro: Boolean): StemMask? {
        val f = existing(trackId, durationMs, intro) ?: return null
        return runCatching {
            decode(f.readBytes()).also { f.setLastModified(System.currentTimeMillis()) }
        }.getOrElse { f.delete(); null }
    }

    fun save(trackId: String, durationMs: Long, intro: Boolean, mask: StemMask) {
        val f = file(trackId, durationMs, intro) ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(encode(mask))
        if (!tmp.renameTo(f)) { tmp.delete(); return }
        trim()
    }

    fun encode(mask: StemMask): ByteArray {
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed, Deflater(Deflater.BEST_COMPRESSION)).use {
            it.write(mask.packed)
            if (mask.voiceShare != null && mask.voiceLevel != null) { it.write(mask.voiceShare); it.write(mask.voiceLevel) }
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(if (mask.voiceShare != null) MAGIC_VOICE else MAGIC); it.writeLong(mask.startUs); it.writeInt(mask.frames)
            it.writeInt(mask.packed.size); it.write(packed.toByteArray())
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): StemMask {
        DataInputStream(bytes.inputStream()).use { input ->
            val magic = input.readInt()
            check(magic == MAGIC || magic == MAGIC_VOICE) { "not a separation" }
            val start = input.readLong(); val frames = input.readInt(); val size = input.readInt()
            check(frames in 1..200_000 && size == frames * StemDsp.NET_BINS / 2) { "bad separation" }
            val packed = ByteArray(size)
            val data = DataInputStream(InflaterInputStream(input))
            data.readFully(packed)
            if (magic == MAGIC) return StemMask(start, frames, packed)
            val share = ByteArray(frames); val level = ByteArray(frames)
            data.readFully(share); data.readFully(level)
            return StemMask(start, frames, packed, share, level)
        }
    }

    /** Titles separated (a title counts once, whatever parts of it were) and the bytes they take. */
    fun usage(): Pair<Int, Long> {
        val files = root?.listFiles { f -> f.name.endsWith(".kvs") }.orEmpty()
        return files.map { it.name.substringBefore('-') }.toSet().size to files.sumOf { it.length() }
    }

    fun clear() { root?.listFiles()?.forEach { it.delete() } }

    private fun trim() {
        val files = root?.listFiles { f -> f.name.endsWith(".kvs") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= LIMIT_BYTES) break
            total -= f.length(); f.delete()
        }
    }
}

/**
 * The instrumental of part of a title, ready for a deck: interleaved stereo 16-bit PCM at the
 * deck's rate, its frame 0 at [startUs] in the title, trustworthy from frame [from] to [until].
 */
internal class StemAudio(val startUs: Long, val rate: Int, val pcm: ShortArray, val from: Int, val until: Int,
                         /** The voice alone, on the same frames, when it was asked for (a voice over another title's instrumental). */
                         val vocals: ShortArray? = null) {
    /** The frame at [positionUs] in the title, or -1 when this instrumental does not cover it. */
    fun frameAt(positionUs: Long): Int {
        val f = ((positionUs - startUs) * rate / 1_000_000L).toInt()
        return if (positionUs >= startUs && f in from until until) f else -1
    }

    fun covers(fromUs: Long, untilUs: Long): Boolean =
        fromUs >= startUs + from * 1_000_000L / rate && untilUs <= startUs + until * 1_000_000L / rate
}
