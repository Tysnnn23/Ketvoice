package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Persistent artist cache: profile pages, their resolved channel id, artwork and
 * signatures. Repeat visits are served from disk instead of re-querying YouTube
 * Music, MusicBrainz, Fanart and Wikidata on every open.
 */
object ArtistCache {
    private const val PAGE_TTL = 24 * 60 * 60_000L
    private const val MISS_TTL = 5 * 60_000L
    private const val MAX_ENTRIES = 300

    private data class PageEntry(val page: ArtistPage?, val expires: Long)
    private val memory = object : LinkedHashMap<String, PageEntry>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageEntry>?) = size > MAX_ENTRIES
    }
    private val lock = Mutex()
    private var file: File? = null
    private var loaded = false

    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "artist-cache-v1.json")
    }

    internal fun key(id: String?, name: String): String {
        val clean = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return if (!id.isNullOrBlank()) "id:${id.trim()}" else "name:$clean"
    }

    /** Returns a cached page and warms the memory map at the same time. */
    suspend fun get(id: String?, name: String): ArtistPage? = lock.withLock {
        load()
        val entry = memory[key(id, name)] ?: return@withLock null
        if (entry.expires <= System.currentTimeMillis()) { memory.remove(key(id, name)); return@withLock null }
        entry.page
    }

    suspend fun put(id: String?, name: String, page: ArtistPage?) = lock.withLock {
        load()
        val lifetime = if (page == null) MISS_TTL else PAGE_TTL
        memory[key(id, name)] = PageEntry(page, System.currentTimeMillis() + lifetime)
        // Also index by channel id when the page resolved one.
        page?.artist?.id?.takeIf { it.isNotBlank() }?.let { memory["id:$it"] = PageEntry(page, System.currentTimeMillis() + lifetime) }
        save()
    }

    suspend fun clear() = lock.withLock { memory.clear(); save() }

    internal fun encode(page: ArtistPage?): JSONObject {
        val root = JSONObject()
        if (page == null) return root.put("missing", true)
        root.put("artist", JSONObject().apply {
            put("id", page.artist.id); put("name", page.artist.name); put("photo", page.artist.photo ?: "")
        })
        root.put("description", page.description)
        root.put("listeners", page.listeners)
        root.put("heroAspectRatio", page.heroAspectRatio.toDouble())
        root.put("tracks", JSONArray().apply { page.tracks.forEach { put(trackJson(it)) } })
        root.put("albums", JSONArray().apply {
            page.albums.forEach { album ->
                put(JSONObject().apply {
                    put("id", album.id); put("title", album.title); put("artist", album.artist)
                    put("type", album.type); put("coverUrl", album.coverUrl ?: "")
                })
            }
        })
        root.put("related", JSONArray().apply {
            page.related.forEach { artist ->
                put(JSONObject().apply { put("id", artist.id); put("name", artist.name); put("photo", artist.photo ?: "") })
            }
        })
        return root
    }

    internal fun decode(root: JSONObject): ArtistPage? {
        if (root.optBoolean("missing", false)) return null
        val artist = root.optJSONObject("artist") ?: return null
        val tracks = root.optJSONArray("tracks")?.let { array ->
            (0 until array.length()).mapNotNull { index -> track(array.optJSONObject(index)) }
        }.orEmpty()
        val albums = root.optJSONArray("albums")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { Album(it.optString("id"), it.optString("title"), it.optString("artist"),
                    it.optString("type", "Album"), it.optString("coverUrl").takeIf(String::isNotBlank)) }
            }
        }.orEmpty()
        val related = root.optJSONArray("related")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { MusicArtist(it.optString("id"), it.optString("name"),
                    it.optString("photo").takeIf(String::isNotBlank)) }
            }
        }.orEmpty()
        return ArtistPage(MusicArtist(artist.optString("id"), artist.optString("name"),
            artist.optString("photo").takeIf(String::isNotBlank)),
            root.optString("description"), tracks, albums, related,
            root.optString("listeners"), root.optDouble("heroAspectRatio", 1.0).toFloat())
    }

    private fun track(value: JSONObject?): Track? {
        value ?: return null
        val id = value.optString("id")
        if (id.isBlank()) return null
        return Track(
            id = id, title = value.optString("title"), artist = value.optString("artist"),
            album = value.optString("album"), durationMs = value.optLong("durationMs"),
            coverUrl = value.optString("coverUrl").takeIf(String::isNotBlank),
            addon = value.optString("addon").takeIf(String::isNotBlank),
            addonId = value.optString("addonId").takeIf(String::isNotBlank),
            videoId = value.optString("videoId").takeIf(String::isNotBlank),
            isrc = value.optString("isrc").takeIf(String::isNotBlank),
            youtubeCatalogVerified = value.optBoolean("youtubeCatalogVerified", true),
            albumId = value.optString("albumId").takeIf(String::isNotBlank),
            artistId = value.optString("artistId").takeIf(String::isNotBlank)
        )
    }

    private fun trackJson(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id); put("title", track.title); put("artist", track.artist)
        put("album", track.album); put("durationMs", track.durationMs)
        put("coverUrl", track.coverUrl ?: ""); put("addon", track.addon ?: "")
        put("addonId", track.addonId ?: ""); put("videoId", track.videoId ?: "")
        put("isrc", track.isrc ?: ""); put("youtubeCatalogVerified", track.youtubeCatalogVerified)
        put("albumId", track.albumId ?: ""); put("artistId", track.artistId ?: "")
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val target = file ?: return
        runCatching {
            if (!target.isFile || target.length() > 8 * 1024 * 1024) return@runCatching
            val root = JSONObject(target.readText(Charsets.UTF_8))
            val now = System.currentTimeMillis()
            root.keys().forEach { key ->
                root.optJSONObject(key)?.let { entry ->
                    val expires = entry.optLong("expires")
                    if (expires > now) entry.optJSONObject("page")?.let {
                        memory[key] = PageEntry(decode(it), expires)
                    }
                }
            }
        }
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val target = file ?: return@withContext
        runCatching {
            val now = System.currentTimeMillis()
            val root = JSONObject()
            memory.entries.toList().takeLast(MAX_ENTRIES).forEach { (key, entry) ->
                if (entry.expires <= now) return@forEach
                root.put(key, JSONObject().put("expires", entry.expires).put("page", encode(entry.page)))
            }
            val atomic = android.util.AtomicFile(target)
            val out = atomic.startWrite()
            try { out.write(root.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (failure: Exception) { atomic.failWrite(out); throw failure }
        }
    }
}
