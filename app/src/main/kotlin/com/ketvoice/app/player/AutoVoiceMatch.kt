package com.ketvoice.app.player

import android.os.Bundle
import android.provider.MediaStore
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.SearchRank
import com.ketvoice.app.data.Track

/**
 * A spoken request, read once (8.4): the words without « radio » or « aléatoire », whether either was said,
 * and the assistant's own fields (artist, album, title, genre, playlist, focus).
 */
internal data class VoiceAsk(
    val text: String,
    val focus: String?,
    val artist: String?,
    val title: String?,
    val album: String?,
    val playlist: String?,
    val genre: String?,
    val shuffle: Boolean,
    val radio: Boolean,
    /** The words as they were said, before « radio » or « aléatoire » were taken out. */
    val spoken: String
) {
    /** Nothing named: « joue de la musique sur Ketvoice ». */
    val isEmpty: Boolean get() = text.isEmpty() && artist == null && title == null && album == null && playlist == null &&
        genre == null && !radio

    companion object {
        const val ARTIST = "vnd.android.cursor.item/artist"
        const val ALBUM = "vnd.android.cursor.item/album"
        const val PLAYLIST = "vnd.android.cursor.item/playlist"
        const val GENRE = "vnd.android.cursor.item/genre"
        const val TITLE = "vnd.android.cursor.item/audio"

        private fun Bundle.text(key: String) = getString(key)?.trim()?.takeIf { it.isNotEmpty() }

        fun of(query: String, extras: Bundle?): VoiceAsk {
            val artist = extras?.text(MediaStore.EXTRA_MEDIA_ARTIST)
            val title = extras?.text(MediaStore.EXTRA_MEDIA_TITLE)
            val album = extras?.text(MediaStore.EXTRA_MEDIA_ALBUM)
            val playlist = extras?.text(MediaStore.EXTRA_MEDIA_PLAYLIST)
            val genre = extras?.text(MediaStore.EXTRA_MEDIA_GENRE) ?: extras?.text("android.intent.extra.genre")
            val words = VoiceMatch.words(query.trim())
            val radio = VoiceMatch.marked(words, VoiceMatch.radioWords)
            val shuffle = VoiceMatch.marked(radio.first, VoiceMatch.shuffleWords)
            val said = shuffle.first.joinToString(" ").takeUnless { MediaOverrides.normalize(it) in VoiceMatch.anythingWords }.orEmpty()
            val text = said.ifEmpty { listOfNotNull(title, artist ?: album).joinToString(" ") }
            return VoiceAsk(text, extras?.text(MediaStore.EXTRA_MEDIA_FOCUS), artist, title, album, playlist, genre,
                shuffle.second, radio.second, query.trim())
        }
    }
}

/** Which result a spoken request means. */
internal object VoiceMatch {
    private fun normalized(vararg words: String) = words.map(MediaOverrides::normalize).toSet()

    /** Spoken names of the favourites, in the interface's languages. */
    val favoriteWords = normalized("favoris", "mes favoris", "mes titres favoris", "titres favoris", "j'aime", "mes j'aime",
        "titres likés", "mes titres likés", "mes coups de coeur", "favorites", "my favorites", "favourites", "my favourites",
        "liked songs", "my liked songs", "my likes", "likes", "favoritos", "mis favoritos", "me gusta", "canciones que me gustan",
        "preferiti", "i miei preferiti", "brani preferiti", "favoriten", "meine favoriten", "lieblingssongs", "meine lieblingssongs",
        "músicas curtidas", "minhas favoritas", "curtidas", "избранное", "мне нравится", "お気に入り", "我喜欢", "收藏", "좋아요",
        "좋아요 표시한 곡", "पसंदीदा")

    /** Spoken names of the downloads. */
    val downloadWords = normalized("téléchargements", "mes téléchargements", "musique téléchargée", "titres téléchargés", "hors ligne",
        "downloads", "my downloads", "downloaded music", "downloaded songs", "offline", "descargas", "mis descargas", "scaricati",
        "musica scaricata", "heruntergeladen", "meine downloads", "transferências", "músicas baixadas", "загрузки", "скачанное",
        "ダウンロード", "下载", "다운로드", "डाउनलोड")

    /** Spoken names of a personal mix. */
    val mixWords = normalized("mix", "mon mix", "mes mix", "un mix", "my mix", "my mixes", "mi mix", "il mio mix", "mein mix", "meu mix",
        "мой микс", "ミックス", "我的混音", "믹스")

    /** Words that name nothing in particular: « joue de la musique ». */
    val anythingWords = normalized("musique", "de la musique", "ma musique", "quelque chose", "n'importe quoi", "music", "my music",
        "some music", "something", "anything", "música", "musica", "mi música", "la mia musica", "musik", "meine musik", "minha música",
        "музыку", "музыка", "音楽", "音乐", "음악", "संगीत")

    val radioWords = normalized("radio", "radios", "station", "rádio", "радио", "ラジオ", "电台", "라디오", "रेडियो")

    val shuffleWords = normalized("aléatoire", "aleatoire", "mélange", "mélanger", "shuffle", "shuffled", "aleatorio", "aleatoria",
        "zufällig", "zufallswiedergabe", "casuale", "aleatório", "перемешать", "вперемешку", "シャッフル", "随机", "셔플", "शफल")

    /** Small words left at an edge once « radio » or « aléatoire » is taken out (« la radio de », « en aléatoire »). */
    private val joiners = normalized("la", "le", "les", "ma", "mon", "de", "du", "des", "d", "en", "mode", "lecture", "the", "of", "on",
        "in", "by", "a", "an", "el", "del", "di", "da", "do", "von", "im")

    /** The words of [query], split on spaces, as said. */
    fun words(query: String): List<String> = query.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * [words] without a marker said first or last (« radio Adele », « Adele radio »), with its small joining words;
     * the second value tells whether a marker was found. A marker inside the words (« Video killed the radio star ») stays.
     */
    fun marked(words: List<String>, markers: Set<String>): Pair<List<String>, Boolean> {
        val norm = words.map(MediaOverrides::normalize)
        val first = norm.indexOfFirst { it !in joiners }
        val last = norm.indexOfLast { it !in joiners }
        if (first < 0) return words to false
        val at = when {
            norm[first] in markers -> first
            norm[last] in markers -> last
            else -> return words to false
        }
        val rest = words.filterIndexed { index, _ -> index != at }.toMutableList()
        while (rest.isNotEmpty() && MediaOverrides.normalize(rest.first()) in joiners) rest.removeAt(0)
        while (rest.isNotEmpty() && MediaOverrides.normalize(rest.last()) in joiners) rest.removeAt(rest.size - 1)
        return rest to true
    }

    private fun tokens(value: String) = MediaOverrides.normalize(value).split(' ').filter { it.isNotEmpty() }

    fun sameArtist(track: Track, artist: String): Boolean {
        val wanted = MediaOverrides.normalize(artist)
        return wanted.isNotEmpty() && MediaOverrides.normalize(track.artist).contains(wanted)
    }

    /** How well [track] answers the query: words found in its title and artist, plus an exact title or the artist named. */
    fun score(track: Track, query: String, title: String?, artist: String?): Double {
        val words = tokens(query)
        val own = (tokens(track.title) + tokens(track.artist)).toSet()
        var score = if (words.isEmpty()) 0.0 else words.count { it in own }.toDouble() / words.size
        val name = MediaOverrides.normalize(track.title)
        val spoken = MediaOverrides.normalize(query)
        if (name.isNotEmpty() && (spoken == name || spoken.startsWith("$name "))) score += .5
        if (title != null && MediaOverrides.normalize(title) == name) score += .5
        if (artist != null && sameArtist(track, artist)) score += .5
        return score
    }

    /**
     * Best match first. Equal scores follow the phone's search order ([SearchRank]: exact title, then the artist
     * named, then every word), then the search's own order (the library before the catalogue).
     */
    fun rank(tracks: List<Track>, query: String, title: String?, artist: String?): List<Track> =
        tracks.withIndex().sortedWith(compareByDescending<IndexedValue<Track>> { score(it.value, query, title, artist) }
            .thenByDescending { SearchRank.score(query, it.value) }.thenBy { it.index })
            .map { it.value }

    /**
     * The request names an artist and nothing narrower (Ketvoice TV 1.1.0, « joue Adele » → the artist's radio): the
     * assistant said so (artist focus), or, without a focus, the words are an artist's name and not a title's.
     */
    fun asksArtist(ask: VoiceAsk, ranked: List<Track>): Boolean {
        if (ask.title != null || ask.album != null || ask.playlist != null) return false
        val name = ask.artist ?: ask.text
        if (name.isBlank() || ranked.none { sameArtist(it, name) }) return false
        return when (ask.focus) {
            VoiceAsk.ARTIST -> true
            null -> !namesTitle(name, ranked) && SearchRank.namesArtist(name, ranked)
            else -> false
        }
    }

    /** The words are exactly the name of one of [tracks]: « Radio Ga Ga » is a title, not a radio. */
    fun namesTitle(spoken: String, tracks: List<Track>): Boolean {
        val q = SearchRank.fold(spoken)
        return q.isNotEmpty() && tracks.take(10).any { SearchRank.fold(it.title) == q }
    }
}
