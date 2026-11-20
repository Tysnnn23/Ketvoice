package com.ketvoice.app.data

/** The same recording, whatever its source (radio, autoplay, history, downloads): one key per song. */
internal object Recordings {
    /** Its plain title without versions and its main artist. */
    fun of(title: String, artist: String): String {
        val plain = title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
            .replace(Regex("\\s+-\\s+.*(remaster|version|edit|live|mix|radio|clip|video|audio|lyrics|paroles).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), "")
        return MediaOverrides.normalize(plain) + "|" + MediaOverrides.normalize(MediaOverrides.mainArtist(artist))
    }
    fun of(track: Track): String = of(track.title, track.artist)
}
