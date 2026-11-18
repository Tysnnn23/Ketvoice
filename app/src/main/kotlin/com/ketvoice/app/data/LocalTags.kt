package com.ketvoice.app.data

import java.text.Normalizer
import java.util.Locale

/**
 * « Musique locale pro » (7.0): the tags of the listener's own files as the platform reads them (MediaStore
 * columns, or MediaMetadataRetriever for ID3, Vorbis and MP4 tags), and the same tags cleaned up. Pure: no
 * Android here, so every rule is host-tested.
 */
internal data class RawTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    /** « 3 », « 03/12 », « 3 of 12 », or MediaStore's disc × 1000 + track. */
    val track: String? = null,
    val disc: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val compilation: String? = null,
    val durationMs: Long = 0L,
    /** The file's name, with its extension. */
    val fileName: String = "",
    /** The folder the file sits in, relative to what was scanned (« Music/Daft Punk/Discovery »). */
    val folder: String = "",
)

internal data class LocalTags(
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNo: Int? = null,
    val trackTotal: Int? = null,
    val discNo: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val compilation: Boolean = false,
    val durationMs: Long = 0L,
    val folder: String = "",
) {
    /** The artist the album is filed under: its album artist, else the title's own (first) artist. */
    val filedArtist: String? get() = albumArtist ?: artist?.let(LocalTagNormalizer::primaryArtist)
}

internal object LocalTagNormalizer {
    /** What taggers and Android write when they know nothing: never a real artist, album or title. */
    private val placeholders = setOf("", "unknown", "<unknown>", "unknown artist", "unknown album", "artiste inconnu",
        "album inconnu", "sans titre", "untitled", "track", "piste", "various", "null", "undefined", "-")
    private val variousArtists = setOf("various artists", "various", "va", "v.a.", "artistes divers", "artistes variés",
        "compilation", "verschiedene interpreten", "varios artistas", "artisti vari")

    /** Trimmed, spaces collapsed, control characters and BOMs dropped; null for a placeholder. */
    fun clean(value: String?): String? {
        val text = value?.replace("﻿", "")?.filterNot { it.isISOControl() }?.replace(Regex("\\s+"), " ")?.trim()
            ?: return null
        return text.takeUnless { it.lowercase(Locale.ROOT) in placeholders }
    }

    fun isVariousArtists(name: String?): Boolean = name != null && name.trim().lowercase(Locale.ROOT) in variousArtists

    /** « 3 », « 03/12 », « 3 of 12 », « A3 » (a vinyl side keeps its number): the number and the total. */
    fun number(value: String?): Pair<Int?, Int?> {
        val text = value?.trim().orEmpty()
        val match = Regex("^[A-Za-z]?0*(\\d{1,4})(?:\\s*(?:/|of|sur|de)\\s*0*(\\d{1,4}))?").find(text) ?: return null to null
        val n = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        val total = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 && (n == null || it >= n) }
        return n to total
    }

    /** MediaStore's TRACK column: disc × 1000 + track (« 2005 » is the fifth title of the second disc). */
    fun mediaStoreTrack(value: Int): Pair<Int?, Int?> = when {
        value <= 0 -> null to null
        value >= 1000 -> (value % 1000).takeIf { it > 0 } to value / 1000
        else -> value to null
    }

    /** « 2019 », « 2019-05-01 », « 2019/05 »: the year, when it is plausible. */
    fun year(value: String?): Int? = Regex("(1[89]\\d\\d|20\\d\\d)").find(value.orEmpty())?.value?.toIntOrNull()

    /** ID3v1 genre references (« (17) », « (17)Rock ») are dropped; a name stays. */
    fun genre(value: String?): String? = clean(value?.replace(Regex("^\\(\\d+\\)"), ""))?.takeUnless { it.all(Char::isDigit) }

    fun compilation(value: String?): Boolean = value?.trim()?.lowercase(Locale.ROOT) in setOf("1", "true", "yes", "oui")

    /** The first credited artist: « A feat. B », « A, B », « A; B » are filed under A (« Simon & Garfunkel » stays whole). */
    fun primaryArtist(name: String): String =
        name.split(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+|\\s*[,;/]\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }?.trim() ?: name.trim()

    data class FromName(val trackNo: Int?, val artist: String?, val title: String)

    /** What a file's name says when its tags are missing: « 01 - Artiste - Titre », « 01. Titre », « Artiste - Titre ». */
    fun fromFileName(fileName: String): FromName {
        var stem = fileName.substringBeforeLast('.', fileName).replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        val numbered = Regex("^(\\d{1,3})\\s*(?:[-.)]\\s*|\\s+)(.+)$").find(stem)
        val trackNo = numbered?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..199 }
        if (trackNo != null) stem = numbered.groupValues[2].trim()
        val parts = stem.split(" - ", limit = 2).map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) FromName(trackNo, parts[0], parts[1])
        else FromName(trackNo, null, stem.ifBlank { fileName })
    }

    /** The tags cleaned up; what is missing comes from the file's name, never from a guess. */
    fun normalize(raw: RawTags): LocalTags {
        val name = fromFileName(raw.fileName)
        val artist = clean(raw.artist)
        val title = clean(raw.title)
        val (track, trackTotal) = number(raw.track).let { (n, t) -> if (n == null && raw.track == null) name.trackNo to null else n to t }
        val (disc, discTotal) = number(raw.disc)
        val albumArtist = clean(raw.albumArtist)
        val compilation = compilation(raw.compilation) || isVariousArtists(albumArtist)
        return LocalTags(
            title = title ?: name.title,
            artist = artist ?: name.artist?.takeIf { title == null },
            album = clean(raw.album),
            albumArtist = albumArtist?.takeUnless { isVariousArtists(it) },
            trackNo = track, trackTotal = trackTotal,
            discNo = disc, discTotal = discTotal,
            year = year(raw.year), genre = genre(raw.genre), compilation = compilation,
            durationMs = raw.durationMs.coerceAtLeast(0L), folder = raw.folder.trim('/'))
    }

    /** The same letters, whatever their case, accents and punctuation: « beyonce » and « Beyoncé » fold alike. */
    fun fold(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * A key to compare names across sources: lower case, no accents, no « (feat. …) », « [Remastered 2011] »,
     * « - Radio Edit », no punctuation.
     */
    fun key(value: String?): String {
        val base = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
        return base.replace(Regex("[(\\[][^)\\]]*(?:feat|ft\\.|remaster|edit|version|live|mono|stereo|bonus|explicit)[^)\\]]*[)\\]]"), " ")
            .replace(Regex("\\s+-\\s+(?:\\d{4}\\s+)?(?:remaster(?:ed)?|radio edit|single version|album version|mono|stereo).*$"), " ")
            .replace(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$"), " ")
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
