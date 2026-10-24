package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

data class SpotifyPreview(val name: String, val url: String, val entries: List<SpotifySong>, val cover: String?)
data class SpotifySong(val id: String, val title: String, val artist: String, val durationMs: Long)

object SpotifyPlaylistImport {
    private val id = Regex("[A-Za-z0-9]{22}")
    fun playlistId(value: String): String {
        val text = value.trim()
        if (text.startsWith("spotify:playlist:")) return text.removePrefix("spotify:playlist:").also { require(id.matches(it)) { "Lien Spotify invalide" } }
        val uri = runCatching { URI(text) }.getOrNull() ?: error("Lien Spotify invalide")
        require(uri.scheme == "https" && uri.host == "open.spotify.com" && uri.userInfo == null && uri.port in listOf(-1,443)) { "Lien Spotify invalide" }
        val parts = uri.path.orEmpty().trim('/').split('/')
        val start = if (parts.firstOrNull()?.matches(Regex("intl-[a-zA-Z-]+")) == true) 1 else 0
        require(parts.size == start + 2 && parts[start] == "playlist" && id.matches(parts[start+1])) { "Collez un lien de playlist Spotify publique" }
        return parts[start+1]
    }
    fun parse(html: String, playlistId: String): SpotifyPreview {
        require(id.matches(playlistId))
        val body = Regex("<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>([\\s\\S]*?)</script>").find(html)?.groupValues?.get(1)
            ?: error("Aperçu Spotify indisponible")
        val entity = JSONObject(body).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        val name = entity.optString("name").trim().take(80).ifBlank { "Spotify" }
        val array = entity.optJSONArray("trackList") ?: error("Aperçu Spotify indisponible")
        val entries = (0 until minOf(array.length(),200)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val trackId = item.optString("uri").removePrefix("spotify:track:")
            if (!id.matches(trackId) || !item.optString("uri").startsWith("spotify:track:")) return@mapNotNull null
            val title = item.optString("title").trim().take(200)
            val artist = item.optString("subtitle").trim().take(200)
            if (title.isBlank() || artist.isBlank()) null else SpotifySong(trackId,title,artist,item.optLong("duration").coerceIn(0,86400000))
        }
        require(entries.isNotEmpty()) { "Aucun titre accessible dans cet aperçu" }
        val art = entity.optJSONObject("coverArt")?.optJSONArray("sources")
        val cover = art?.optJSONObject(0)?.optString("url")?.takeIf { value ->
            runCatching { URI(value).let { it.scheme == "https" && it.host in setOf("i.scdn.co","mosaic.scdn.co") && it.userInfo == null } }.getOrDefault(false)
        }
        return SpotifyPreview(name,"https://open.spotify.com/playlist/$playlistId",entries,cover)
    }
    suspend fun preview(link: String): SpotifyPreview = withContext(Dispatchers.IO) {
        val playlistId = playlistId(link)
        val c = URL("https://open.spotify.com/embed/playlist/$playlistId").openConnection() as HttpURLConnection
        try {
            c.connectTimeout=10000;c.readTimeout=15000;c.instanceFollowRedirects=false
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Android) Ketvoice/2.12")
            require(c.responseCode == 200) { "Aperçu Spotify indisponible. Vérifiez que la playlist est publique." }
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val block = ByteArray(8192)
                while (out.size() <= 2*1024*1024) {
                    val count = input.read(block); if (count < 0) break
                    out.write(block,0,count)
                }
                out.toByteArray()
            }
            require(bytes.size <= 2*1024*1024) { "Aperçu Spotify trop volumineux" }
            parse(String(bytes,Charsets.UTF_8),playlistId)
        } finally { c.disconnect() }
    }
    private fun normalized(value: String) = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT),Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"),"").replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun match(song: SpotifySong, candidates: List<Track>): Track? {
        val title = normalized(song.title)
        val artist = normalized(song.artist.substringBefore(',').substringBefore(" & "))
        return candidates.filter { track ->
            normalized(track.title) == title && artist.isNotEmpty() &&
                (" ${normalized(track.artist)} ").contains(" $artist ") && track.youtubeCatalogVerified &&
                (song.durationMs <= 0 || track.durationMs <= 0 || kotlin.math.abs(song.durationMs-track.durationMs) <= maxOf(8000L,song.durationMs/12))
        }.minByOrNull { if (it.durationMs > 0) kotlin.math.abs(it.durationMs-song.durationMs) else Long.MAX_VALUE }
            ?.copy(spotifyId = song.id, streamUrl = null, downloadUrl = null)
    }
    suspend fun resolve(preview: SpotifyPreview, progress: (Int) -> Unit): List<PlaylistEntry> = coroutineScope {
        val gate = Semaphore(2)
        var completed = 0
        preview.entries.map { song -> async {
            gate.withPermit {
                val found = runCatching { match(song,MusicApi.searchYtMusic("${song.title} ${song.artist}")) }.getOrNull()
                withContext(Dispatchers.Main.immediate) { progress(++completed) }
                PlaylistEntry(title=song.title,artist=song.artist,spotifyId=song.id,track=found)
            }
        } }.awaitAll()
    }
}
