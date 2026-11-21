package com.ketvoice.app.data

import java.text.Normalizer

/**
 * 8.0 (owner: « quand il y a un feat et que le deuxième chanteur chante, que les lyrics ne soient pas du même côté,
 * comme Apple Music »): which lines another singer than the main artist sings, so that the lyrics put them on the
 * other side.
 *
 * Two sources, never a guess:
 * - the lyrics name their singers (TTML `ttm:agent`, enhanced LRC `v1:` / `v2:`, a JSON `agent`): the main
 *   singer is the one named like the main artist, else `v1`, else the first one heard; the others go across;
 * - otherwise, only for a title with a featured artist (« feat. X », « (with X) », or a second artist), section
 *   headers that name that artist (« [Verse 2: X] », « [X:] », « (X) » at the start of a line) move the lines
 *   that follow them across, until a header names the main artist, both, or nobody.
 */
object LyricsDuet {
    /** For each line, true when it belongs on the other side. Lines of the main artist (and unknown ones) stay put. */
    fun sides(lines: List<MusicApi.LyricLine>, title: String, artist: String): BooleanArray {
        val out = BooleanArray(lines.size)
        if (lines.isEmpty()) return out
        val main = mainArtist(artist)
        val singers = lines.mapNotNull { it.singer }.distinct()
        if (singers.isNotEmpty()) {
            if (singers.size < 2) return out
            val lead = singers.firstOrNull { same(it, main) } ?: singers.firstOrNull { it.equals("v1", true) } ?: singers.first()
            lines.forEachIndexed { i, line -> out[i] = line.singer != null && line.singer != lead }
            return out
        }
        val featured = featured(title, artist)
        if (featured.isEmpty() || main.isBlank()) return out
        var across = false
        lines.forEachIndexed { i, line ->
            header(line.text, main, featured)?.let { across = it }
            out[i] = across
        }
        return out
    }

    /** The main artist: the first name of the artist field. */
    fun mainArtist(artist: String): String = names(artist.split(featuring).first()).firstOrNull().orEmpty()

    /** Featured artists named by the title (« feat. X », « ft. X », « (with X) ») or after the first artist. */
    fun featured(title: String, artist: String): List<String> {
        val fromArtist = artist.split(featuring).let { parts -> names(parts.first()).drop(1) + parts.drop(1).flatMap { names(it) } }
        val fromTitle = titleFeature.findAll(title).flatMap { names(it.groupValues[1].ifBlank { it.groupValues[2] }) }.toList()
        return (fromArtist + fromTitle).distinctBy { normal(it) }
    }

    /**
     * When [text] is a section header (or starts with « (Name) »): true if it names only featured artists, false if
     * it names the main artist or nobody. Null when the line is a lyric.
     */
    internal fun header(text: String, main: String, featured: List<String>): Boolean? {
        val t = text.trim()
        val known = featured + main
        val named: List<String> = bracket.matchEntire(t)?.let { m ->
            val inside = m.groupValues[1]
            when {
                ':' in inside -> names(inside.substringAfter(':').ifBlank { inside.substringBefore(':') })
                names(inside).any { n -> known.any { same(n, it) } } -> names(inside)
                else -> emptyList() // « [Chorus] »: a section without a name is the main artist's.
            }
        } ?: paren.find(t)?.let { m ->
            // « (X) » only counts when X is one of the title's artists; otherwise it is a backing vocal.
            names(m.groupValues[1]).takeIf { found -> found.isNotEmpty() && found.all { n -> known.any { same(n, it) } } }
        } ?: return null
        if (named.isEmpty() || named.any { same(it, main) }) return false
        return named.any { n -> featured.any { same(n, it) } }
    }

    private val featuring = Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with|avec)\\s+")
    private val titleFeature = Regex("(?i)[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with|avec)\\s+([^)\\]]+)[)\\]]|\\s(?:feat\\.|ft\\.|featuring)\\s+([^()\\[\\]\\-–]+)")
    private val bracket = Regex("^\\[([^\\[\\]]{1,80})]$")
    private val paren = Regex("^[(（]([^()（）]{1,60})[)）]")
    private val separators = Regex("(?i)\\s*(?:,|&|\\+|/|\\bx\\b|\\band\\b|\\bet\\b|\\by\\b|\\bfeat\\.?|\\bft\\.?)\\s*")

    private fun names(text: String): List<String> = text.split(separators).map { it.trim() }.filter { it.isNotEmpty() }

    private fun normal(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

    internal fun same(a: String, b: String): Boolean {
        val x = normal(a); val y = normal(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (minOf(x.length, y.length) >= 3 && (x.contains(y) || y.contains(x)))
    }
}
