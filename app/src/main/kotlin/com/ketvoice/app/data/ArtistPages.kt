package com.ketvoice.app.data

/**
 * Finds an artist's page in the catalogue, by its channel when it has one, by its name otherwise.
 * Shared by the artist page and by the « Plus de … » shelf of an album page, so that both open the
 * same artist and fill the same cache ([ArtistCache]).
 */
object ArtistPages {
    private val channel = Regex("UC[A-Za-z0-9_-]+")

    /**
     * Fetches the page from the network. A title does not always carry a YouTube channel id
     * ("UC…"): the artist is then looked up by name, an exact match of the name first, so that a
     * loose search does not open the page of a namesake.
     */
    suspend fun fetch(id: String, name: String): ArtistPage? {
        val directId = id.takeIf { it.matches(channel) }
        var resolved = directId?.let { runCatching { YouTubeCatalog.artist(it) }.getOrNull() }
        if (resolved == null && name.isNotBlank()) {
            val candidates = runCatching { YouTubeCatalog.searchArtists(name) }.getOrDefault(emptyList())
            val match = candidates.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: candidates.firstOrNull()
            if (match != null) resolved = YouTubeCatalog.artist(match.id)
        }
        return resolved
    }

    /**
     * The cached page when there is one, the network's otherwise (then cached) when [network] allows it;
     * null when neither has it.
     */
    suspend fun cachedOrFetch(id: String, name: String, network: Boolean = true): ArtistPage? {
        ArtistCache.get(id, name)?.let { return it }
        if (!network) return null
        val page = runCatching { fetch(id, name) }.getOrNull() ?: return null
        ArtistCache.put(id, name, page)
        return page
    }
}
