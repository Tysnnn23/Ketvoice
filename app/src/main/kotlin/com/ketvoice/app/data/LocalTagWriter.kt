package com.ketvoice.app.data

import java.io.ByteArrayOutputStream

/**
 * « Écrire dans le fichier » (7.0): the accepted corrections written into the file's own tags, only when the
 * listener asks for it and Android lets Ketvoice write the file. Pure byte work, host-tested:
 *
 * - MP3: the ID3v2.3/2.4 tag at the start is rebuilt; its other frames (covers, lyrics, comments…) are kept as
 *   they are, the audio after it is copied byte for byte. A tag Ketvoice cannot read safely (unsynchronised, with
 *   an extended header) is left alone.
 * - FLAC: the Vorbis comment block is rebuilt (other comments kept), the other metadata blocks and the audio
 *   frames are copied byte for byte.
 * - Other formats (MP4/M4A, Ogg, WAV…) are not written: the correction stays in the app.
 *
 * [rewrite] returns null whenever it cannot promise the audio comes out untouched.
 */
internal object LocalTagWriter {
    enum class Kind { Mp3, Flac }

    /** null keeps what the file says. */
    data class Update(val title: String? = null, val artist: String? = null, val album: String? = null,
                      val albumArtist: String? = null, val track: String? = null, val disc: String? = null,
                      val year: String? = null) {
        val isEmpty: Boolean get() = listOf(title, artist, album, albumArtist, track, disc, year).all { it == null }
    }

    fun kind(head: ByteArray): Kind? = when {
        head.size >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> Kind.Flac
        head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> Kind.Mp3
        head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> Kind.Mp3
        else -> null
    }

    /** The file with [update] written in, or null (unsupported format, unreadable tag, nothing to write). */
    fun rewrite(bytes: ByteArray, update: Update): ByteArray? {
        if (update.isEmpty) return null
        return when (kind(bytes)) {
            Kind.Mp3 -> Id3.rewrite(bytes, update)
            Kind.Flac -> Flac.rewrite(bytes, update)
            null -> null
        }
    }

    /** Where the audio starts, after the tags this writer rebuilds (to check that it came out untouched). */
    fun audioStart(bytes: ByteArray): Int? = when (kind(bytes)) {
        Kind.Mp3 -> Id3.audioStart(bytes)
        Kind.Flac -> Flac.blocks(bytes)?.second
        null -> null
    }

    /** Both files carry the same audio, byte for byte. */
    fun sameAudio(before: ByteArray, after: ByteArray): Boolean {
        val a = audioStart(before) ?: return false
        val b = audioStart(after) ?: return false
        if (before.size - a != after.size - b) return false
        for (i in 0 until before.size - a) if (before[a + i] != after[b + i]) return false
        return true
    }

    internal fun u32be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    internal fun syncsafe(b: ByteArray, at: Int) = ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
        ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)
    internal fun syncsafeBytes(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
    internal fun u32beBytes(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    // ------------------------------------------------------------------ ID3v2

    internal object Id3 {
        private const val PADDING = 1024

        /** 0 without a tag; after the tag (and its footer) with one; null for a tag that runs past the file. */
        fun audioStart(b: ByteArray): Int? {
            if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return 0
            val end = 10 + syncsafe(b, 6) + if ((b[5].toInt() and 0x10) != 0) 10 else 0
            return end.takeIf { it <= b.size }
        }

        private fun ids(major: Int, u: Update): Map<String, String> = buildMap {
            u.title?.let { put("TIT2", it) }; u.artist?.let { put("TPE1", it) }; u.album?.let { put("TALB", it) }
            u.albumArtist?.let { put("TPE2", it) }; u.track?.let { put("TRCK", it) }; u.disc?.let { put("TPOS", it) }
            u.year?.let { put(if (major == 4) "TDRC" else "TYER", it) }
        }

        fun rewrite(b: ByteArray, u: Update): ByteArray? {
            val tagged = b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()
            val major = if (tagged) b[3].toInt() else 3
            if (major != 3 && major != 4) return null
            if (tagged && (b[5].toInt() and 0xC0) != 0) return null  // unsynchronised or extended header: left alone
            val start = audioStart(b) ?: return null
            // An ID3 tag laid over a FLAC file: its own tags are Vorbis comments, an ID3 tag there is not rewritten.
            if (start + 4 <= b.size && String(b, start, 4, Charsets.ISO_8859_1) == "fLaC") return null
            val wanted = ids(major, u)
            val kept = if (tagged) keptFrames(b, major, 10, 10 + syncsafe(b, 6), wanted.keys) ?: return null else emptyList()
            val frames = ByteArrayOutputStream()
            kept.forEach { frames.write(it) }
            wanted.forEach { (id, text) -> frames.write(textFrame(major, id, text)) }
            val body = frames.toByteArray()
            val out = ByteArrayOutputStream(body.size + PADDING + b.size - start + 10)
            out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0))
            out.write(syncsafeBytes(body.size + PADDING))
            out.write(body); out.write(ByteArray(PADDING))
            out.write(b, start, b.size - start)
            return out.toByteArray()
        }

        /** Every frame but those rewritten, as raw bytes; null for a frame that runs past its tag. */
        private fun keptFrames(b: ByteArray, major: Int, from: Int, to: Int, replaced: Set<String>): List<ByteArray>? {
            val out = ArrayList<ByteArray>()
            var at = from
            while (at + 10 <= to && b[at] != 0.toByte()) {
                val id = String(b, at, 4, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) return null
                val size = if (major == 4) syncsafe(b, at + 4) else u32be(b, at + 4)
                if (size < 0 || at + 10 + size > to) return null
                val dropped = id in replaced || (id == "TYER" || id == "TDRC") && ("TYER" in replaced || "TDRC" in replaced)
                if (!dropped) out += b.copyOfRange(at, at + 10 + size)
                at += 10 + size
            }
            return out
        }

        /** A text frame: UTF-16 with its byte-order mark in v2.3, UTF-8 in v2.4. */
        private fun textFrame(major: Int, id: String, text: String): ByteArray {
            val payload = if (major == 4) byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
                else byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            val size = if (major == 4) syncsafeBytes(payload.size) else u32beBytes(payload.size)
            return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, 0) + payload
        }

        /** The text of a frame, for checks: what a player will read back. */
        fun text(b: ByteArray, id: String): String? {
            if (audioStart(b) == 0) return null
            val major = b[3].toInt()
            var at = 10
            val to = 10 + syncsafe(b, 6)
            while (at + 10 <= to && b[at] != 0.toByte()) {
                val size = if (major == 4) syncsafe(b, at + 4) else u32be(b, at + 4)
                if (String(b, at, 4, Charsets.ISO_8859_1) == id) return decode(b.copyOfRange(at + 10, at + 10 + size))
                at += 10 + size
            }
            return null
        }

        private fun decode(p: ByteArray): String = when (p.firstOrNull()?.toInt()) {
            0 -> String(p, 1, p.size - 1, Charsets.ISO_8859_1)
            1 -> String(p, 1, p.size - 1, Charsets.UTF_16)
            2 -> String(p, 1, p.size - 1, Charsets.UTF_16BE)
            else -> String(p, 1, p.size - 1, Charsets.UTF_8)
        }.trimEnd('\u0000')
    }

    // ------------------------------------------------------------------ FLAC

    internal object Flac {
        private const val VORBIS = 4

        data class Block(val type: Int, val body: ByteArray)

        /** The metadata blocks and where the audio frames start; null for a damaged header. */
        fun blocks(b: ByteArray): Pair<List<Block>, Int>? {
            if (b.size < 8) return null
            val out = ArrayList<Block>()
            var at = 4
            while (true) {
                if (at + 4 > b.size) return null
                val header = b[at].toInt() and 0xFF
                val length = ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
                if (at + 4 + length > b.size) return null
                out += Block(header and 0x7F, b.copyOfRange(at + 4, at + 4 + length))
                at += 4 + length
                if ((header and 0x80) != 0) return out to at
                if (out.size > 1000) return null
            }
        }

        private fun keys(u: Update): Map<String, String> = buildMap {
            u.title?.let { put("TITLE", it) }; u.artist?.let { put("ARTIST", it) }; u.album?.let { put("ALBUM", it) }
            u.albumArtist?.let { put("ALBUMARTIST", it) }; u.track?.let { put("TRACKNUMBER", it) }
            u.disc?.let { put("DISCNUMBER", it) }; u.year?.let { put("DATE", it) }
        }

        fun rewrite(b: ByteArray, u: Update): ByteArray? {
            val (blocks, audio) = blocks(b) ?: return null
            if (blocks.firstOrNull()?.type != 0) return null  // STREAMINFO comes first in every valid file
            val old = blocks.firstOrNull { it.type == VORBIS }?.let { comments(it.body) ?: return null }
            val wanted = keys(u)
            val replaced = wanted.keys + if ("ALBUMARTIST" in wanted) setOf("ALBUM ARTIST") else emptySet()
            val kept = old?.second.orEmpty().filter { it.substringBefore('=').uppercase() !in replaced }
            val block = Block(VORBIS, encode(old?.first ?: "Ketvoice", kept + wanted.map { (k, v) -> "$k=$v" }))
            val list = blocks.toMutableList()
            val index = list.indexOfFirst { it.type == VORBIS }
            if (index >= 0) list[index] = block else list.add(1, block)
            val out = ByteArrayOutputStream(b.size + 1024)
            out.write(b, 0, 4)
            list.forEachIndexed { i, blk ->
                if (blk.body.size >= 1 shl 24) return null
                out.write((blk.type or if (i == list.lastIndex) 0x80 else 0)); out.write(blk.body.size shr 16)
                out.write(blk.body.size shr 8); out.write(blk.body.size); out.write(blk.body)
            }
            out.write(b, audio, b.size - audio)
            return out.toByteArray()
        }

        private fun le(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
        private fun leBytes(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

        /** The vendor and the « KEY=value » comments; null for a damaged block. */
        fun comments(body: ByteArray): Pair<String, List<String>>? = runCatching {
            var at = 0
            val vendorLength = le(body, at); at += 4
            val vendor = String(body, at, vendorLength, Charsets.UTF_8); at += vendorLength
            val count = le(body, at); at += 4
            require(count in 0..100_000)
            val list = (0 until count).map {
                val n = le(body, at); at += 4
                String(body, at, n, Charsets.UTF_8).also { at += n }
            }
            vendor to list
        }.getOrNull()

        private fun encode(vendor: String, comments: List<String>): ByteArray {
            val out = ByteArrayOutputStream()
            val v = vendor.toByteArray(Charsets.UTF_8)
            out.write(leBytes(v.size)); out.write(v); out.write(leBytes(comments.size))
            comments.forEach { c -> val bytes = c.toByteArray(Charsets.UTF_8); out.write(leBytes(bytes.size)); out.write(bytes) }
            return out.toByteArray()
        }
    }
}
