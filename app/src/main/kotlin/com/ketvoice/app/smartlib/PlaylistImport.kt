package com.ketvoice.app.smartlib

import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.ProviderConfig
import com.ketvoice.app.data.SpotifyPlaylistImport
import com.ketvoice.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

enum class ImportService(val label: String) {
    Spotify("Spotify"), Deezer("Deezer"), AppleMusic("Apple Music"), Tidal("Tidal"), SoundCloud("SoundCloud"),
    /** Windows 2.0 only ([YouTubeImport]); [PlaylistImport.serviceOf] does not take YouTube links, so the phone's import is unchanged. */
    YouTube("YouTube")
}

/** A title of an imported playlist; [catalogue] is set when the service's title is already one of Ketvoice's catalogue. */
data class ImportSong(val sourceId: String, val title: String, val artist: String, val durationMs: Long,
                      val isrc: String? = null, val catalogue: Track? = null)

/**
 * A public playlist read from its link. [complete] is false when the service shows only part of it
 * (Spotify's preview, Tidal's first 50, SoundCloud's first titles); [publishedCount] is the size the
 * service announces for the whole playlist, when it does.
 */
data class ImportPreview(val service: ImportService, val name: String, val url: String, val cover: String?,
                         val songs: List<ImportSong>, val complete: Boolean, val publishedCount: Int? = null)

/** What was found in Ketvoice for one imported title, and how sure the match is (0..1). */
data class ImportMatch(val song: ImportSong, val track: Track?, val score: Float)

/**
 * Imports public Spotify, Deezer, Apple Music, Tidal and SoundCloud playlists from their links, without
 * an account or a developer key: Spotify's public embed page, Deezer's public API, and the public web
 * pages of the three others ([AppleMusicImport], [TidalImport], [SoundCloudImport]). Each title is then
 * looked for in Ketvoice's catalogue (by its ISRC first when the service gives one) and scored on its
 * title, artist and length.
 */
object PlaylistImport {
    const val MAX_SONGS = 500
    const val MATCH_FROM = .74f
    private val deezerHosts = setOf("deezer.com", "www.deezer.com")
    private val shortHosts = setOf("link.deezer.com", "deezer.page.link")

    /** Which service a link belongs to; throws with a message for the listener when it is neither. */
    fun serviceOf(link: String): ImportService {
        val text = link.trim()
        if (text.startsWith("spotify:playlist:")) return ImportService.Spotify
        val host = runCatching { URI(text).host?.lowercase() }.getOrNull()
        return when (host) {
            "open.spotify.com" -> ImportService.Spotify
            in deezerHosts, in shortHosts -> ImportService.Deezer
            in AppleMusicImport.hosts -> ImportService.AppleMusic
            in TidalImport.hosts -> ImportService.Tidal
            in SoundCloudImport.hosts, SoundCloudImport.SHORT_HOST -> ImportService.SoundCloud
            else -> throw IllegalArgumentException("Collez un lien de playlist publique Spotify, Deezer, Apple Music, Tidal ou SoundCloud")
        }
    }

    /** The playlist id of a full Deezer link ("https://www.deezer.com/fr/playlist/123"), or null for a short link. */
    fun deezerId(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: invalidDeezer()
        val host = uri.host?.lowercase()
        if (host in shortHosts) return null
        require(uri.scheme == "https" && host in deezerHosts && uri.userInfo == null && uri.port in listOf(-1, 443)) { "Lien Deezer invalide" }
        val parts = uri.path.orEmpty().trim('/').split('/')
        val start = if (parts.firstOrNull()?.matches(Regex("[a-z]{2}(-[a-z]{2})?")) == true) 1 else 0
        if (parts.size != start + 2 || parts[start] != "playlist" || !parts[start + 1].matches(Regex("\\d{1,15}"))) invalidDeezer()
        return parts[start + 1]
    }

    private fun invalidDeezer(): Nothing = throw IllegalArgumentException("Collez un lien de playlist Deezer publique")

    /** The first page of a Deezer playlist and the address of the next one. */
    fun parseDeezer(json: String, id: String): Pair<ImportPreview, String?> {
        val root = JSONObject(json)
        root.optJSONObject("error")?.let { throw IllegalStateException("Playlist Deezer introuvable ou privée") }
        val tracks = root.optJSONObject("tracks")
        val songs = deezerSongs(tracks?.optJSONArray("data"))
        val cover = root.optString("picture_xl").takeIf { it.startsWith("https://") && hostOf(it)?.endsWith("dzcdn.net") == true }
        val name = root.optString("title").trim().take(80).ifBlank { "Deezer" }
        val preview = ImportPreview(ImportService.Deezer, name, "https://www.deezer.com/playlist/$id", cover, songs, true)
        return preview to tracks?.optString("next")?.takeIf { it.startsWith("https://api.deezer.com/playlist/$id/tracks") }
    }

    /** The titles of one page of Deezer's playlist tracks (`data`), with their catalogue title when Deezer can play it. */
    fun deezerSongs(data: JSONArray?): List<ImportSong> {
        data ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim().take(200)
            val artist = item.optJSONObject("artist")?.optString("name").orEmpty().trim().take(200)
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val catalogue = CatalogRepo.parseTracks(JSONArray().put(item)).firstOrNull()
            ImportSong(item.optString("id"), title, artist, item.optLong("duration").coerceIn(0, 86_400) * 1000,
                item.optString("isrc").takeIf { it.isNotBlank() && it != "null" }, catalogue)
        }
    }

    private fun hostOf(url: String) = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /** A Spotify embed preview as an import: only the titles the public preview shows. */
    fun fromSpotify(html: String, id: String): ImportPreview {
        val preview = SpotifyPlaylistImport.parse(html, id)
        return ImportPreview(ImportService.Spotify, preview.name, preview.url, preview.cover,
            preview.entries.map { ImportSong(it.id, it.title, it.artist, it.durationMs) }, false)
    }

    /** Reads the playlist behind [link]. [get] fetches a page (tests give recorded pages). */
    suspend fun preview(link: String, get: suspend (String) -> String? = ::open): ImportPreview = withContext(Dispatchers.IO) {
        when (serviceOf(link)) {
            ImportService.Spotify -> {
                val id = SpotifyPlaylistImport.playlistId(link)
                val html = get("https://open.spotify.com/embed/playlist/$id")
                    ?: throw IllegalStateException("Aperçu Spotify indisponible. Vérifiez que la playlist est publique.")
                fromSpotify(html, id)
            }
            ImportService.Deezer -> deezer(deezerId(link) ?: expandShort(link), get)
            ImportService.AppleMusic -> AppleMusicImport.pageUrl(link).let { url ->
                AppleMusicImport.parse(get(url) ?: throw IllegalStateException(PublicPage.NOT_FOUND), url)
            }
            ImportService.Tidal -> TidalImport.playlistId(link).let { id ->
                val page = get(TidalImport.pageUrl(id)) ?: throw IllegalStateException(PublicPage.NOT_FOUND)
                TidalImport.parse(page, get(TidalImport.embedUrl(id)) ?: throw IllegalStateException(PublicPage.NOT_FOUND), id)
            }
            ImportService.SoundCloud -> {
                val full = if (!SoundCloudImport.isShort(link)) link
                    else PublicPage.fetch(link.trim(), SoundCloudImport.hosts + SoundCloudImport.SHORT_HOST)?.first
                        ?: throw IllegalStateException(PublicPage.NOT_FOUND)
                val path = SoundCloudImport.setPath(full)
                SoundCloudImport.parse(get(SoundCloudImport.pageUrl(path)) ?: throw IllegalStateException(PublicPage.NOT_FOUND), path)
            }
            // Never returned by serviceOf (the phone's import is unchanged); Windows reads YouTube links itself.
            ImportService.YouTube -> YouTubeImport.preview(link)
        }
    }

    /** A page of one of the five services; Apple Music, Tidal and SoundCloud may redirect within their own sites. */
    private suspend fun open(address: String): String? {
        val host = hostOf(address)
        val hosts = listOf(AppleMusicImport.hosts, TidalImport.hosts, SoundCloudImport.hosts).firstOrNull { host in it }
        return if (hosts == null) download(address) else PublicPage.fetch(address, hosts)?.second
    }

    private suspend fun deezer(id: String, get: suspend (String) -> String?): ImportPreview {
        val first = get(api("playlist/$id")) ?: throw IllegalStateException("Deezer est injoignable. Réessayez plus tard.")
        var (preview, next) = parseDeezer(first, id)
        val songs = preview.songs.toMutableList()
        var pages = 0
        while (next != null && songs.size < MAX_SONGS && pages++ < 20) {
            val page = get(api(next.removePrefix("https://api.deezer.com/"))) ?: break
            val root = JSONObject(page)
            songs += deezerSongs(root.optJSONArray("data"))
            next = root.optString("next").takeIf { it.startsWith("https://api.deezer.com/playlist/$id/tracks") }
        }
        require(songs.isNotEmpty()) { "Aucun titre accessible dans cette playlist" }
        return preview.copy(songs = songs.distinctBy { it.sourceId }.take(MAX_SONGS), complete = next == null || songs.size >= MAX_SONGS)
    }

    private fun api(path: String) = ProviderConfig.publicUrl("https://api.deezer.com/$path") ?: "https://api.deezer.com/$path"

    /** A short Deezer link leads, by redirections within Deezer only, to the playlist's full link. */
    private fun expandShort(link: String): String {
        var current = link.trim()
        repeat(4) {
            deezerIdOrNull(current)?.let { return it }
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false; connection.connectTimeout = 8000; connection.readTimeout = 8000
                connection.requestMethod = "GET"
                val location = connection.getHeaderField("Location") ?: invalidDeezer()
                val next = URI(current).resolve(location).toString()
                val host = hostOf(next)
                if (!next.startsWith("https://") || (host !in deezerHosts && host !in shortHosts)) invalidDeezer()
                current = next
            } finally { connection.disconnect() }
        }
        return deezerIdOrNull(current) ?: invalidDeezer()
    }

    private fun deezerIdOrNull(link: String): String? = runCatching { deezerId(link.substringBefore('?')) }.getOrNull()

    private suspend fun download(address: String): String? = withContext(Dispatchers.IO) {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Ketvoice")
            if (connection.responseCode != 200) return@withContext null
            connection.inputStream.use { input -> String(readCapped(input), Charsets.UTF_8) }
        } catch (_: java.io.IOException) { null } finally { connection.disconnect() }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val block = ByteArray(8192)
        while (out.size() <= 4 * 1024 * 1024) { val count = input.read(block); if (count < 0) break; out.write(block, 0, count) }
        require(out.size() <= 4 * 1024 * 1024) { "Playlist trop volumineuse" }
        return out.toByteArray()
    }

    /* ── Matching ── */

    /** How sure it is that [track] is [song]: title 55 %, artist 30 %, length 15 %; the same ISRC is certain. */
    fun score(song: ImportSong, track: Track): Float {
        if (song.isrc != null && song.isrc.equals(track.isrc, ignoreCase = true)) return 1f
        val title = titleScore(song.title, track.title)
        val artist = artistScore(song.artist, track.artist)
        if (title < .6f || artist < .5f) return 0f
        val score = .55f * title + .3f * artist + .15f * lengthScore(song.durationMs, track.durationMs)
        // Both lengths known and 45 s apart: an extended mix, an edit or a live take, not the same recording.
        val apart = song.durationMs > 0 && track.durationMs > 0 && kotlin.math.abs(song.durationMs - track.durationMs) > 45_000
        return if (apart) score * .8f else score
    }

    private fun titleScore(a: String, b: String): Float {
        val left = Duplicates.baseTitle(a); val right = Duplicates.baseTitle(b)
        val same = if (left == right) 1f else jaccard(left, right)
        // "Live" for the studio take (or the reverse) is another recording.
        return if (Duplicates.versionOf(a) == Duplicates.versionOf(b)) same else same * .5f
    }

    private fun artistScore(a: String, b: String): Float {
        val wanted = Duplicates.baseArtist(a)
        val credited = SmartText.words(b)
        if (wanted.isNotEmpty() && SmartText.find(credited, wanted) >= 0) return 1f
        return jaccard(wanted, credited)
    }

    private fun lengthScore(a: Long, b: Long): Float {
        if (a <= 0 || b <= 0) return .6f
        val gap = kotlin.math.abs(a - b)
        return when { gap <= 3_000 -> 1f; gap <= 10_000 -> .7f; gap <= 30_000 -> .3f; else -> 0f }
    }

    private fun jaccard(a: String, b: String): Float {
        val x = a.split(' ').filter { it.isNotBlank() }.toSet(); val y = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0f
        return x.intersect(y).size.toFloat() / x.union(y).size
    }

    /** The best of [candidates] for [song], when it is sure enough; titles of the catalogue win a tie. */
    fun best(song: ImportSong, candidates: List<Track>): ImportMatch {
        song.catalogue?.let { return ImportMatch(song, it, 1f) }
        val ranked = candidates.map { it to score(song, it) + if (it.addon == "um" || it.youtubeCatalogVerified) .02f else 0f }
            .maxByOrNull { it.second }
        val track = ranked?.takeIf { it.second >= MATCH_FROM }?.first
        return ImportMatch(song, track?.copy(spotifyId = if (song.sourceId.length == 22) song.sourceId else track.spotifyId,
            streamUrl = null, downloadUrl = null), ranked?.second?.coerceAtMost(1f) ?: 0f)
    }

    /**
     * Looks every title up, three at a time, telling [progress] how many are done; the playlist's order is
     * kept. A title with an ISRC is first asked of the catalogue by that code ([byIsrc]).
     */
    suspend fun resolve(preview: ImportPreview, search: suspend (String) -> List<Track>,
                        byIsrc: suspend (String) -> Track? = ::catalogueByIsrc,
                        progress: (Int, Int) -> Unit): List<ImportMatch> = coroutineScope {
        val gate = Semaphore(3)
        val lock = Mutex()
        var done = 0
        val total = preview.songs.size
        preview.songs.map { song ->
            async {
                val match = gate.withPermit { matchOne(song, search, byIsrc) }
                lock.withLock { done++; progress(done, total) }
                match
            }
        }.awaitAll()
    }

    private suspend fun matchOne(song: ImportSong, search: suspend (String) -> List<Track>,
                                 byIsrc: suspend (String) -> Track?): ImportMatch {
        if (song.catalogue != null) return best(song, emptyList())
        if (song.isrc != null) {
            val found = try { byIsrc(song.isrc) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (found != null && found.isrc.equals(song.isrc, ignoreCase = true)) return best(song.copy(catalogue = found), emptyList())
        }
        val candidates = try { search("${song.title} ${Duplicates.baseArtist(song.artist)}") }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        return best(song, candidates)
    }

    /** The catalogue's title for an ISRC, through Deezer's public API (no account, no key). */
    private suspend fun catalogueByIsrc(isrc: String): Track? {
        if (!isrc.matches(Regex("[A-Za-z]{2}[A-Za-z0-9]{3}\\d{7}"))) return null
        val json = download(api("track/isrc:${isrc.uppercase()}")) ?: return null
        val item = runCatching { JSONObject(json) }.getOrNull()?.takeIf { !it.has("error") } ?: return null
        return CatalogRepo.parseTracks(JSONArray().put(item)).firstOrNull()
    }

    /** The entries of the new playlist: every title, those not found kept visible without a playable title. */
    fun entries(matches: List<ImportMatch>): List<PlaylistEntry> = matches.map { match ->
        PlaylistEntry(title = match.song.title, artist = match.song.artist,
            spotifyId = match.song.sourceId.takeIf { it.length == 22 }, track = match.track)
    }
}
