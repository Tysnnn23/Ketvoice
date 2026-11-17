package com.ketvoice.app.smartlib

/**
 * How well a title fits an ambiance, from Ketmix's measurements (tempo, level, key) when they
 * exist and from its style and words otherwise. Null when too little is known to say.
 */
internal object MoodScore {
    /** What a mood asks for: an ideal tempo, a level, a key colour, styles and words. */
    private class Profile(
        val bpm: ClosedFloatingPointRange<Float>, val loud: Boolean?, val minor: Boolean?,
        val likes: Set<Genre>, val dislikes: Set<Genre>, val words: String, val avoid: String,
    )

    private val sadWords = "sad|triste|alone|seul|seule|tears|larmes|cry|pleure|pleurer|goodbye|adieu|lonely|solitude|pain|heartbreak|broken|blue|sorry|miss you|manque|nostalgie|regret|lost|perdu|tristesse|sombre|dark|mort|death|alone again|lagrimas|traurig"
    private val calmWords = "acoustic|acoustique|acustico|akustik|piano|lullaby|berceuse|sleep|sommeil|dormir|chill|calm|calme|slow|soft|douce|doux|rain|pluie|ocean|ambient|relax|quiet|silence|unplugged|ballad|ballade|interlude"
    private val energyWords = "workout|hype|bounce|power|run|fire|feu|rage|fast|energy|banger|turn up|wild|sauvage|boom|remix|bass boosted|hardstyle|drill"
    private val partyWords = "party|dance|danse|club|fiesta|soiree|fete|bailar|baila|disco|celebration|weekend|shake|dancefloor|tonight|ce soir"
    private val focusWords = "instrumental|piano|ambient|lofi|lo fi|study|focus|concentration|beats|meditation|sonata|sonate|prelude|etude|nocturne|interlude"
    private val happyWords = "happy|heureux|sun|soleil|summer|ete|joy|joie|good vibes|smile|sourire|fun|sunshine|vacances|holiday|sunny|celebrate|feliz|alegria"

    private val profiles: Map<Mood, Profile> by lazy {
        mapOf(
            Mood.Calm to Profile(60f..98f, false, null,
                setOf(Genre.Classical, Genre.Jazz, Genre.LoFi, Genre.Soul, Genre.Folk, Genre.Chanson, Genre.Soundtrack, Genre.Indie),
                setOf(Genre.Metal, Genre.Dance, Genre.Electro), calmWords, energyWords + "|" + partyWords),
            Mood.Energy to Profile(120f..180f, true, null,
                setOf(Genre.Rock, Genre.Metal, Genre.Electro, Genre.Dance, Genre.Rap),
                setOf(Genre.Classical, Genre.LoFi, Genre.Jazz, Genre.Folk, Genre.Blues), energyWords, calmWords + "|" + sadWords),
            Mood.Party to Profile(100f..132f, true, null,
                setOf(Genre.Dance, Genre.Electro, Genre.Latin, Genre.Afro, Genre.Reggae, Genre.Pop, Genre.RnB, Genre.KPop),
                setOf(Genre.Classical, Genre.LoFi, Genre.Folk, Genre.Blues, Genre.Soundtrack), partyWords, sadWords + "|" + calmWords),
            Mood.Focus to Profile(60f..112f, false, null,
                setOf(Genre.LoFi, Genre.Classical, Genre.Jazz, Genre.Soundtrack),
                setOf(Genre.Rap, Genre.Metal, Genre.Dance), focusWords, partyWords + "|" + energyWords),
            Mood.Melancholy to Profile(55f..100f, null, true,
                setOf(Genre.Blues, Genre.Folk, Genre.Indie, Genre.Chanson, Genre.Soul),
                setOf(Genre.Dance, Genre.Latin, Genre.Afro, Genre.Reggae, Genre.KPop), sadWords, happyWords + "|" + partyWords),
            Mood.Happy to Profile(100f..136f, null, false,
                setOf(Genre.Pop, Genre.Latin, Genre.Afro, Genre.Reggae, Genre.KPop, Genre.Soul, Genre.Dance),
                setOf(Genre.Blues, Genre.Metal), happyWords, sadWords),
        )
    }

    /** Evidence needed before a score is given: one measurement, a style or a word is enough. */
    private const val MIN_WEIGHT = .9f

    fun score(mood: Mood, facts: TrackFacts): Float? {
        val profile = profiles.getValue(mood)
        val parts = listOfNotNull(tempo(profile, facts.sound), level(profile, facts.sound), key(profile, facts.sound),
            genre(profile, facts.genres), words(profile, facts.words))
        val weight = parts.sumOf { it.second.toDouble() }.toFloat()
        if (weight < MIN_WEIGHT) return null
        return parts.sumOf { (it.first * it.second).toDouble() }.toFloat() / weight
    }

    /** 1 inside the ideal tempo, falling to 0 twenty-five beats away; half and double tempos count too. */
    private fun tempo(profile: Profile, sound: SoundSignals?): Pair<Float, Float>? {
        val bpm = sound?.bpm?.takeIf { it > 30f } ?: return null
        val fit = listOf(bpm, bpm * 2f, bpm / 2f).maxOf { closeness(it, profile.bpm) * if (it == bpm) 1f else .4f }
        return fit to 1.1f
    }

    private fun closeness(bpm: Float, range: ClosedFloatingPointRange<Float>): Float = when {
        bpm in range -> 1f
        bpm < range.start -> (1f - (range.start - bpm) / 25f).coerceAtLeast(0f)
        else -> (1f - (bpm - range.endInclusive) / 25f).coerceAtLeast(0f)
    }

    /** Loud titles sit around −7 dBFS, quiet ones under −13. */
    private fun level(profile: Profile, sound: SoundSignals?): Pair<Float, Float>? {
        val wantsLoud = profile.loud ?: return null
        val db = sound?.loudnessDb?.takeIf { it > -60f } ?: return null
        val loudness = ((db + 14f) / 7f).coerceIn(0f, 1f)
        return (if (wantsLoud) loudness else 1f - loudness) to .7f
    }

    private fun key(profile: Profile, sound: SoundSignals?): Pair<Float, Float>? {
        val wantsMinor = profile.minor ?: return null
        val minor = sound?.minor ?: return null
        return (if (minor == wantsMinor) 1f else 0f) to .6f
    }

    private fun genre(profile: Profile, genres: Set<Genre>): Pair<Float, Float>? = when {
        genres.isEmpty() -> null
        genres.any { it in profile.likes } -> 1f to 1f
        genres.any { it in profile.dislikes } -> 0f to 1f
        else -> .45f to .5f
    }

    private fun words(profile: Profile, words: String): Pair<Float, Float>? = when {
        words.isBlank() -> null
        hits(words, profile.words) -> 1f to 1.2f
        hits(words, profile.avoid) -> 0f to .9f
        else -> null
    }

    private fun hits(words: String, list: String) = list.split('|').any { SmartText.find(words, it) >= 0 }
}
