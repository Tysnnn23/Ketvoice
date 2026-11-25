package com.ketvoice.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The pure half of « Playlists téléchargées à jour » (8.4): which titles of a followed playlist are new, and how what a
 * check knows is written down. Shared by the phone's [PlaylistUpdates] and the Windows app's watcher, without Android.
 */
object PlaylistFollow {
    /** New titles of one downloaded playlist: what a notification carries. */
    data class Update(val playlistId: String, val name: String, val trackIds: List<String>)

    /** What a check finds, and what it remembers afterwards (playlist id → its titles at this check). */
    data class Check(val updates: List<Update>, val known: Map<String, Set<String>>)

    /** The catalogue titles of [playlist] (files of this device left out), each once. */
    fun ids(playlist: LocalPlaylist): List<String> =
        playlist.entries.mapNotNull { it.track?.id }.filter { it.isNotBlank() && !it.startsWith("local:") }.distinct()

    /**
     * For every followed playlist, the titles not known at the last check and not on the device or on their way
     * ([downloaded]). A playlist not followed yet starts to be when it is wholly downloaded (nothing announced then).
     */
    fun diff(playlists: List<LocalPlaylist>, downloaded: Set<String>, known: Map<String, Set<String>>): Check {
        val next = HashMap<String, Set<String>>()
        val updates = mutableListOf<Update>()
        for (playlist in playlists) {
            val titles = ids(playlist)
            val before = known[playlist.id]
            if (before == null) {
                if (titles.isNotEmpty() && titles.all { it in downloaded }) next[playlist.id] = titles.toSet()
                continue
            }
            // Every download of it removed: the listener let it go.
            if (titles.isNotEmpty() && titles.none { it in downloaded }) continue
            val fresh = titles.filter { it !in before && it !in downloaded }
            if (fresh.isNotEmpty()) updates += Update(playlist.id, playlist.name, fresh)
            next[playlist.id] = titles.toSet()
        }
        return Check(updates, next)
    }

    /** [known] with [trackIds] added to a followed playlist: titles the listener added themselves are never announced. */
    fun added(known: Map<String, Set<String>>, playlistId: String, trackIds: Collection<String>): Map<String, Set<String>> {
        val before = known[playlistId] ?: return known
        return known + (playlistId to before + trackIds)
    }

    /** [known] with [playlist] followed from now on, with the titles it holds (« Télécharger » from its menu). */
    fun followed(known: Map<String, Set<String>>, playlist: LocalPlaylist): Map<String, Set<String>> =
        known + (playlist.id to ids(playlist).toSet())

    /**
     * Windows 2.0 « Suivre une playlist externe »: the songs of an outside playlist ([songIds], the service's own ids in
     * its order) not seen at its last check ([known]), each once. A song that left and came back is not new.
     */
    fun outsideFresh(known: Set<String>, songIds: List<String>): List<String> = songIds.filter { it.isNotBlank() && it !in known }.distinct()

    fun decode(text: String?): Map<String, Set<String>> = runCatching {
        val root = JSONObject(text ?: return emptyMap())
        root.keys().asSequence().associateWith { key ->
            root.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        }
    }.getOrDefault(emptyMap())

    fun encode(known: Map<String, Set<String>>): String =
        JSONObject().apply { known.forEach { (id, titles) -> put(id, JSONArray(titles.sorted())) } }.toString()
}
