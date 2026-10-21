package com.ketvoice.app.data

import androidx.compose.ui.graphics.Color

enum class AudioQuality { LOSSLESS, HIGH, NORMAL }

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0L,
    val streamUrl: String? = null,     // branché plus tard (addon)
    val downloadUrl: String? = null,
    val canvasUrl: String? = null,
    val lyricsProvider: String = "LRCLIB",
    val explicit: Boolean = false,
    val coverUrl: String? = null,
    // --- sources réelles ---
    val addon: String? = null,         // "lb" (Lossless Bridge) | "um" (Ultra MAX)
    val addonId: String? = null,       // id côté addon (monochrome / deezer)
    val videoId: String? = null,       // YouTube Music
    val spotifyId: String? = null,     // pour les canvases
    val isrc: String? = null,
    val youtubeCatalogVerified: Boolean = false,
    val albumId: String? = null,
    val artistId: String? = null
)

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val type: String = "Album",           // Single / EP / Album
    val coverUrl: String? = null,
    val tint: Color = Color(0xFF3A3A3C)
)

data class MoodCard(
    val label: String,
    val tint: Color,
    val coverUrl: String? = null
)

data class Playlist(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: Kind
) {
    enum class Kind { NEW, SPOTIFY_IMPORT, LIKED, NORMAL, PODCAST }
}

/** Cache récentes de recherche */
data class RecentSearch(val query: String, val kind: String = "Artiste")

/** Secousses de données de démo, calquées sur les captures */
object MockData {
    val recents = listOf(
        Track("p1", "Pétunias", "Werenoi", "Pétunias", explicit = true),
        Track("p2", "Chemin d'or", "Werenoi", "Pétunias", explicit = true),
        Track("p3", "C63", "Werenoi", "Pétunias", explicit = true),
        Track("p4", "Lace It", "Juice WRLD, Eminem, benny blanco", "The Party Never Ends", explicit = true)
    )

    val queue = listOf(
        Track("q1", "Love Letter", "Juice WRLD", "The Party Never Ends"),
        Track("q2", "KTM Drip", "Juice WRLD", "The Party Never Ends"),
        Track("q3", "AGATS2", "Juice WRLD", "The Party Never Ends"),
        Track("q4", "The Party Never Ends", "Juice WRLD", "The Party Never Ends"),
        Track("q5", "PHOTO", "Werenoi", "Pétunias"),
        Track("q6", "Bené", "Werenoi", "Pétunias"),
        Track("q7", "Burn Them All Down (feat. Nile Rodgers)", "Alan Walker", "Walkerverse")
    )

    val albums = listOf(
        Album("a1", "VNews #10", "VNews", "Single"),
        Album("a2", "Après L'Éclipse", "Lunis", "Album"),
        Album("a3", "L'Humanité", "Werenoi", "EP"),
        Album("a4", "Baby Shark's Space Adventure", "Pinkfong", "Album")
    )

    val moods = listOf(
        MoodCard("Automne", Color(0xFFC7C26A)), MoodCard("Bonne humeur", Color(0xFF52B788)),
        MoodCard("Calme", Color(0xFF7E96C4)), MoodCard("Concentration", Color(0xFF8E8E93)),
        MoodCard("Énergie", Color(0xFFB39E4B)), MoodCard("Entraînement", Color(0xFFC46A29)),
        MoodCard("Gaming", Color(0xFF5E5E60)), MoodCard("Pour la route", Color(0xFFD4AF37)),
        MoodCard("Romance", Color(0xFFB02431)), MoodCard("Soirée", Color(0xFF7B4FD8)),
        MoodCard("Famille", Color(0xFF35BCC5)), MoodCard("Folk et acoustique", Color(0xFF3E9151)),
        MoodCard("Hip-hop & rap", Color(0xFFC03B10)), MoodCard("Jazz", Color(0xFF7E96C4)),
        MoodCard("K-Pop", Color(0xFF8F5CD8)), MoodCard("Métal", Color(0xFF5A5A5E)),
        MoodCard("Musique latino-américaine", Color(0xFFC7A423)), MoodCard("Pop", Color(0xFFC949B8)),
        MoodCard("Pop & variété française", Color(0xFF8E1F7B)), MoodCard("Pop japonaise", Color(0xFFC9749E)),
        MoodCard("Pop urbaine française", Color(0xFF6E6E73)), MoodCard("R&B et soul", Color(0xFF5B3CC4)),
        MoodCard("Rap français", Color(0xFF8A8A8E)), MoodCard("Reggae & caraïbes", Color(0xFFC8B54A))
    )

    val genres = listOf(
        MoodCard("Famille", Color(0xFF35BCC5)), MoodCard("Folk et acoustique", Color(0xFF3E9151)),
        MoodCard("Hip-hop & rap", Color(0xFFC03B10)), MoodCard("Jazz", Color(0xFF7E96C4)),
        MoodCard("K-Pop", Color(0xFF8F5CD8)), MoodCard("Métal", Color(0xFF5A5A5E)),
        MoodCard("Musique latino-américaine", Color(0xFFC7A423)), MoodCard("Pop", Color(0xFFC949B8)),
        MoodCard("Pop & variété française", Color(0xFF8E1F7B)), MoodCard("Pop japonaise", Color(0xFFC9749E)),
        MoodCard("Pop urbaine française", Color(0xFF6E6E73)), MoodCard("R&B et soul", Color(0xFF5B3CC4)),
        MoodCard("Rap français", Color(0xFF8A8A8E)), MoodCard("Reggae & caraïbes", Color(0xFFC8B54A))
    )

    val playlists = listOf(
        Playlist("pl0", "Nouvelle playlist", "Enregistrée sur YouTube Music", Playlist.Kind.NEW),
        Playlist("pl1", "Importer depuis Spotify", "Depuis un lien de playlist", Playlist.Kind.SPOTIFY_IMPORT),
        Playlist("pl2", "Musique du monde", "Playlist", Playlist.Kind.NORMAL),
        Playlist("pl3", "\"J'aime\"", "Automatique", Playlist.Kind.LIKED),
        Playlist("pl4", "I AM MUSIC", "2 titres", Playlist.Kind.NORMAL),
        Playlist("pl5", "Épisodes pour plus tard", "Vos épisodes dans la file", Playlist.Kind.PODCAST)
    )

    val recentSearches = listOf(
        RecentSearch("Werenoi"), RecentSearch("Juice WRLD"), RecentSearch("Eminem"),
        RecentSearch("The Party Never Ends"), RecentSearch("benny blanco")
    )

    val lyricsDemo = listOf(
        "J'suis plus très loin du soleil, jamais très loin d'lacroute",
        "La bécane qui n'en peut plus, although elle guide trop",
        "Débrouillard dans la coupe comme d'habitude",
        "Trop de khapta sur la morra, tant que pas de doute",
        "J'lai vu les grands-mères prier pour qu'on tienne bon",
        "Pétunias au fond du fond, ça grandit vraiment",
        "Avant que j'y aille, un passage sur ma loi",
        "Quand le fond s'écroule, plus rien ne se ressemble",
        "Toujours la même horloge à Casio",
        "Toujours la même horloge à Casio"
    )

    val lyricsSources = listOf(
        "lrc.red", "BiniLyrics", "BetterLyrics", "BetterLyrics Portato", "PaxSenix",
        "PaxSenix: Spotify", "PaxSenix: Musixmatch", "LyricsPlus", "Unison",
        "YouTube captions", "YouTube Music", "KuGou", "LRCLIB", "Musixmatch", "Genius"
    )
}
