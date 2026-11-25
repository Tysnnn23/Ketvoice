package com.ketvoice.desktop.lyrics

import java.io.File
import java.util.Base64

/**
 * Whisper's multilingual tokenizer (byte-level BPE over `multilingual.tiktoken`, MIT), for the word sync by AI: the
 * lyrics are already known, so only encoding is needed. The ranks file ships with the model (`ketvoice-align-v1`).
 */
class WhisperTokens(private val ranks: Map<ByteKey, Int>) {
    /** A byte string usable as a map key. */
    class ByteKey(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is ByteKey && other.bytes.contentEquals(bytes)
    }

    /** The tokens of [text], as tiktoken's `encode` gives them. */
    fun encode(text: String): List<Int> = PATTERN.findAll(text).flatMap { bpe(it.value.toByteArray(Charsets.UTF_8)) }.toList()

    private fun bpe(piece: ByteArray): List<Int> {
        ranks[ByteKey(piece)]?.let { return listOf(it) }
        // Parts start as single bytes; the adjacent pair whose merge has the lowest rank merges first.
        val parts = MutableList(piece.size) { piece.copyOfRange(it, it + 1) }
        while (parts.size > 1) {
            var best = -1; var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val r = ranks[ByteKey(parts[i] + parts[i + 1])] ?: continue
                if (r < bestRank) { bestRank = r; best = i }
            }
            if (best < 0) break
            parts[best] = parts[best] + parts.removeAt(best + 1)
        }
        return parts.map { ranks[ByteKey(it)] ?: error("byte without a rank") }
    }

    companion object {
        const val EOT = 50257
        const val SOT = 50258
        const val FIRST_LANGUAGE = 50259
        const val TRANSCRIBE = 50359
        const val NO_TIMESTAMPS = 50363

        /** Whisper's 99 languages in token order (`whisper.tokenizer.LANGUAGES`). */
        val LANGUAGES = ("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg " +
            "lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be " +
            "tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su").split(' ')

        fun languageToken(code: String): Int? = LANGUAGES.indexOf(code).takeIf { it >= 0 }?.let { FIRST_LANGUAGE + it }

        /** tiktoken's GPT-2 split, which Whisper keeps. */
        private val PATTERN = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        /** Reads a `.tiktoken` file: one « base64 rank » per line. */
        fun load(file: File): WhisperTokens = parse(file.readLines())

        fun parse(lines: List<String>): WhisperTokens {
            val decoder = Base64.getDecoder()
            val ranks = HashMap<ByteKey, Int>(lines.size * 2)
            for (line in lines) {
                val space = line.indexOf(' ')
                if (space <= 0) continue
                // The file's last line, « = 50256 », decodes to nothing in Python and is never reached by a merge.
                val bytes = runCatching { decoder.decode(line.substring(0, space)) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                ranks[ByteKey(bytes)] = line.substring(space + 1).trim().toInt()
            }
            return WhisperTokens(ranks)
        }
    }
}
