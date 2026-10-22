package com.ketvoice.app.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class ImportedLyrics(val content: String, val format: String, val lines: List<MusicApi.LyricLine>) {
    companion object {
        fun read(file: File): ImportedLyrics {
            require(file.length() in 1..1_048_576) { "Paroles trop volumineuses (1 Mo maximum)" }
            val format = file.extension.lowercase()
            require(format in setOf("ttml", "lrc")) { "Choisissez un fichier TTML ou LRC" }
            val bytes = file.readBytes()
            val charset = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            val content = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            val lines = if (format == "ttml") LyricsFormats.ttml(content) else MusicApi.parseLrc(content)
            require(lines.isNotEmpty()) { "Aucune parole synchronisée valide dans ce fichier" }
            return ImportedLyrics(content, format, lines)
        }
    }
}
