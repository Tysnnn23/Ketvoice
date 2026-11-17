package com.ketvoice.app.smartlib

import com.ketvoice.app.data.Track

/** What Ketmix measured of a title (tempo, level, key), when it did. */
data class SoundSignals(val bpm: Float?, val loudnessDb: Float?, val minor: Boolean?)

/**
 * What the device knows about a title: its styles and year from the catalogue when it said so,
 * the language guessed from its words, Ketmix's measurements, and its folded words.
 */
data class TrackFacts(
    val genres: Set<Genre> = emptySet(),
    val year: Int? = null,
    val lang: Lang? = null,
    val sound: SoundSignals? = null,
    val words: String = "",
    val artist: String = "",
) {
    val bpm: Float? get() = sound?.bpm
}

/** What the catalogue said of an album: its styles and the year it came out. */
data class AlbumFacts(val genres: Set<Genre>, val year: Int?)

internal object Facts {
    /**
     * The facts of [track]. [album] comes from the catalogue, [sound] from Ketmix, [artistGenres] and
     * [artistLang] from the other titles of the same artist, [chartGenres] from the catalogue's style charts.
     */
    fun of(track: Track, album: AlbumFacts? = null, sound: SoundSignals? = null, chartGenre: Genre? = null,
           artistGenres: Set<Genre> = emptySet(), artistLang: Lang? = null): TrackFacts {
        val words = SmartText.words("${track.title} ${track.album}")
        val genres = (album?.genres.orEmpty() + listOfNotNull(chartGenre) + wordGenres(words)).ifEmpty { artistGenres }
        val lang = LanguageGuess.of(track.title) ?: langOfGenres(genres) ?: artistLang
        return TrackFacts(genres, album?.year, lang, sound, words, SmartText.words(track.artist))
    }

    private fun wordGenres(words: String): Set<Genre> = buildSet {
        if (SmartText.find(words, "lofi") >= 0 || SmartText.find(words, "lo fi") >= 0) add(Genre.LoFi)
        if (SmartText.find(words, "ost") >= 0 || SmartText.find(words, "soundtrack") >= 0 || SmartText.find(words, "bande originale") >= 0) add(Genre.Soundtrack)
        if (SmartText.find(words, "kpop") >= 0) add(Genre.KPop)
    }

    private fun langOfGenres(genres: Set<Genre>): Lang? = when {
        Genre.Chanson in genres -> Lang.French
        Genre.KPop in genres -> Lang.Korean
        else -> null
    }

    /** The year of a catalogue release date ("2016-03-11"). */
    fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }

    /** The style most of an artist's titles share, by name, for titles the catalogue said nothing of. */
    fun artistGenres(facts: Collection<TrackFacts>): Map<String, Set<Genre>> = facts.filter { it.genres.isNotEmpty() && it.artist.isNotBlank() }
        .groupBy { it.artist }
        .mapValues { (_, rows) -> rows.flatMap { it.genres }.groupingBy { it }.eachCount().filterValues { it * 2 >= rows.size }.keys }

    /** The language most of an artist's titles are sung in, when at least two titles agree and nothing contradicts them much. */
    fun artistLanguages(facts: Collection<TrackFacts>): Map<String, Lang> = facts.filter { it.lang != null && it.artist.isNotBlank() }
        .groupBy { it.artist }
        .mapNotNull { (artist, rows) ->
            val best = rows.groupingBy { it.lang!! }.eachCount().maxByOrNull { it.value } ?: return@mapNotNull null
            if (best.value >= 2 && best.value * 10 >= rows.size * 6) artist to best.key else null
        }.toMap()
}

/** The language of a title, from its script, its letters and its small words; null when it cannot be told. */
internal object LanguageGuess {
    private val stopwords: Map<Lang, Set<String>> by lazy {
        mapOf(
            Lang.French to "je|j'|tu|il|elle|nous|vous|les|des|une|pas|mon|ma|mes|ton|ta|moi|toi|avec|dans|pour|sur|qui|c'est|j'ai|sans|plus|comme|tout|rien|jamais|toujours|amour|coeur|nuit|soleil|ciel|encore|quand|bebe|mais|d'|l'|qu'|t'|m'|n'|s'",
            Lang.Spanish to "el|los|las|mi|yo|con|por|para|corazon|quiero|eres|tengo|noche|nada|todo|sin|pero|mas|besame|bailar|ella|contigo|amor|vida|que",
            Lang.Italian to "il|lo|gli|di|che|non|sono|ti|cuore|amore|notte|sempre|ancora|della|nel|questo|io|sei|ciao|come",
            Lang.German to "ich|du|und|der|die|das|nicht|mit|mein|dein|liebe|herz|nacht|ein|eine|ist|wir|auf|fur|zu|im|wenn",
            Lang.Portuguese to "eu|voce|nao|meu|minha|com|coracao|saudade|uma|em|tudo|amor|vida|que|pra|seu|sua",
            Lang.English to "the|you|i|me|my|love|your|in|on|of|to|and|it|is|be|don't|i'm|we|all|for|with|night|baby|heart|feel|like|know|oh|this|what|can't|never|up|down",
        ).mapValues { (_, words) -> words.split('|').toSet() }
    }

    fun of(title: String): Lang? = script(title) ?: latin(title)

    private fun script(text: String): Lang? {
        var kana = 0; var han = 0; var hangul = 0; var cyrillic = 0; var devanagari = 0; var arabic = 0
        for (c in text) when (Character.UnicodeBlock.of(c)) {
            Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana++
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han++
            Character.UnicodeBlock.HANGUL_SYLLABLES -> hangul++
            Character.UnicodeBlock.CYRILLIC -> cyrillic++
            Character.UnicodeBlock.DEVANAGARI -> devanagari++
            Character.UnicodeBlock.ARABIC -> arabic++
            else -> {}
        }
        return when {
            kana > 0 -> Lang.Japanese
            hangul > 0 -> Lang.Korean
            han > 0 -> Lang.Chinese
            cyrillic > 1 -> Lang.Russian
            devanagari > 1 -> Lang.Hindi
            arabic > 1 -> Lang.Arabic
            else -> null
        }
    }

    private fun latin(title: String): Lang? {
        val plain = title.lowercase().substringBefore(" (").substringBefore(" [").substringBefore(" - ")
        val letters = letterHint(plain)
        val tokens = SmartText.words(plain).split(' ').filter { it.isNotBlank() }
        val scores = stopwords.mapValues { (_, set) -> tokens.count { it in set || it.take(2) in set && it.getOrNull(1) == '\'' }.toFloat() }
            .toMutableMap()
        letters?.let { scores[it] = (scores[it] ?: 0f) + 1.5f }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0f
        return best.key.takeIf { best.value >= 1f && best.value > second }
    }

    /** Letters only one language uses among those told apart here. */
    private fun letterHint(text: String): Lang? = when {
        text.any { it == 'ñ' || it == '¿' || it == '¡' } -> Lang.Spanish
        text.any { it == 'ã' || it == 'õ' } -> Lang.Portuguese
        text.any { it == 'ä' || it == 'ö' || it == 'ü' || it == 'ß' } -> Lang.German
        text.any { it == 'è' || it == 'ê' || it == 'ç' || it == 'œ' || it == 'û' || it == 'î' } -> Lang.French
        else -> null
    }
}
