package com.ketvoice.app.smartlib

/** Styles the smart library understands, with the catalogue's (Deezer) genre id when it has one. */
enum class Genre(val label: String, val catalogueId: Int) {
    Rap("Rap", 116), Pop("Pop", 132), RnB("R&B", 165), Rock("Rock", 152), Electro("Électro", 106),
    Dance("Dance", 113), Jazz("Jazz", 129), Classical("Classique", 98), Reggae("Reggae", 144),
    Latin("Latino", 197), KPop("K-pop", 0), Afro("Afro", 2), Metal("Metal", 464), Country("Country", 84),
    Soul("Soul", 169), Folk("Folk", 466), Blues("Blues", 153), Chanson("Chanson", 52), LoFi("Lo-fi", 0),
    Soundtrack("Musique de film", 173), Indie("Indé", 85);

    companion object {
        fun ofCatalogue(id: Int): Genre? = entries.firstOrNull { it.catalogueId == id && id != 0 }
    }
}

/** Languages a title can be sung in, as far as the device can tell. */
enum class Lang(val label: String) {
    French("Français"), English("Anglais"), Spanish("Espagnol"), Italian("Italien"), German("Allemand"),
    Portuguese("Portugais"), Russian("Russe"), Japanese("Japonais"), Chinese("Chinois"), Korean("Coréen"),
    Hindi("Hindi"), Arabic("Arabe")
}

/** Ambiances: the name of a mood playlist and of the chip that asks for it. */
enum class Mood(val label: String) {
    Calm("Calme"), Energy("Énergie"), Party("Soirée"), Focus("Concentration"), Melancholy("Mélancolie"), Happy("Bonne humeur")
}

enum class Tempo(val label: String) { Slow("Lent"), Fast("Rapide") }

/** One thing understood in a free-text search. [label] is French, translated where it is shown when [localized]. */
sealed interface QueryFilter {
    val key: String
    val label: String
    val localized: Boolean get() = true
}

data class GenreFilter(val genre: Genre) : QueryFilter {
    override val key get() = "g:${genre.name}"
    override val label get() = genre.label
}

data class LanguageFilter(val lang: Lang) : QueryFilter {
    override val key get() = "l:${lang.name}"
    override val label get() = lang.label
}

data class MoodFilter(val mood: Mood) : QueryFilter {
    override val key get() = "m:${mood.name}"
    override val label get() = mood.label
}

data class TempoFilter(val tempo: Tempo) : QueryFilter {
    override val key get() = "t:${tempo.name}"
    override val label get() = tempo.label
}

data class YearFilter(val from: Int, val to: Int) : QueryFilter {
    override val key get() = "y:$from-$to"
    override val label get() = if (from == to) "$from" else "$from–$to"
    override val localized get() = false
}

data class ArtistFilter(val name: String) : QueryFilter {
    override val key get() = "a:${SmartText.fold(name)}"
    override val label get() = name
    override val localized get() = false
}
