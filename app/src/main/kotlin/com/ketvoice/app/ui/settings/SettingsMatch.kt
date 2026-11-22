package com.ketvoice.app.ui.settings

import java.text.Normalizer

/**
 * How « Recherche dans les réglages » (8.4) matches a row, without any page: words folded without accents or case, and
 * the rank of a row's title and description. Shared by the phone's [SettingsSearch] and the Windows settings.
 */
internal object SettingsMatch {
    /** [text] without accents, apostrophes told apart or case: « Égaliseur » and « egaliseur » are one word. */
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('’', '\'').replace('œ', 'o').replace('Œ', 'o')
        .lowercase(java.util.Locale.ROOT)

    /** The words of [query], folded; empty when it holds none. */
    fun words(query: String): List<String> = fold(query).split(' ', '\'', '-').filter { it.isNotBlank() }

    /**
     * The rank of a row whose [title] and [sub] are as the listener reads them (best 0, then 1, 2, 3), or null when a
     * word of the query is in neither: a title that starts with the whole query comes first.
     */
    fun rank(query: String, words: List<String>, title: String, sub: String): Int? {
        val folded = fold(title)
        val text = folded + " " + fold(sub)
        if (words.isEmpty() || words.any { it !in text }) return null
        val whole = fold(query.trim())
        return when { folded.startsWith(whole) -> 0; whole in folded -> 1; words.all { it in folded } -> 2; else -> 3 }
    }
}
