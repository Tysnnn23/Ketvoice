package com.ketvoice.app.smartlib

import java.text.Normalizer
import java.util.Locale

/** Text helpers shared by the smart library: everything runs on the device. */
internal object SmartText {
    private val latinMarks = Regex("(?<=[A-Za-z])\\p{M}+")
    private val apostrophes = Regex("[’`´]")
    private val spaces = Regex("\\s+")

    /**
     * Lower case without the accents of Latin letters ("Années" → "annees"). Marks of other scripts
     * are kept (й, ё, Devanagari vowel signs, Hangul syllables are recomposed).
     */
    fun fold(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val folded = Normalizer.normalize(decomposed.replace(latinMarks, ""), Normalizer.Form.NFC)
        return folded.replace(apostrophes, "'").replace('œ', 'o').replace('æ', 'a').replace('ß', 's')
    }

    /** [fold], with every sign that is not a letter, a digit or a mark turned into one space. */
    fun words(value: String): String = fold(value).replace(Regex("[^\\p{L}\\p{N}\\p{M}&']+"), " ")
        .replace(spaces, " ").trim()

    /** Scripts written without spaces between words: their words are found inside the text. */
    fun spaceless(word: String): Boolean = word.any { c ->
        val block = Character.UnicodeBlock.of(c)
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA || block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    /** Where [word] stands in [text] as a whole word (or anywhere for [spaceless] scripts), or -1. */
    fun find(text: String, word: String): Int {
        if (word.isEmpty()) return -1
        if (spaceless(word)) return text.indexOf(word)
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return -1
            if (boundary(text, at - 1) && boundary(text, at + word.length)) return at
            from = at + 1
        }
    }

    private fun boundary(text: String, index: Int): Boolean {
        if (index < 0 || index >= text.length) return true
        val c = text[index]
        return !(Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt())
    }

    /** [text] with [length] characters from [at] replaced by one space. */
    fun cut(text: String, at: Int, length: Int): String = (text.substring(0, at) + " " + text.substring(at + length))
        .replace(spaces, " ").trim()
}
