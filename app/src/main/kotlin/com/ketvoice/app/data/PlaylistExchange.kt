package com.ketvoice.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Local playlist portability: JSON round-trip and plain M3U export. */
object PlaylistExchange {

    private data class ExportedPlaylist(
        val kind: String = "ketvoice-playlist",
        val version: Int = 1,
        val name: String,
        val spotifyUrl: String? = null,
        val entries: List<ExportedEntry>
    )
    private data class ExportedEntry(val title: String, val artist: String, val spotifyId: String? = null)

    fun toJson(playlist: LocalPlaylist): String {
        val payload = ExportedPlaylist(name = playlist.name, spotifyUrl = playlist.spotifyUrl,
            entries = playlist.entries.map { ExportedEntry(it.title, it.artist, it.spotifyId) })
        return Gson().toJson(payload)
    }

    fun toM3u(playlist: LocalPlaylist): String = buildString {
        appendLine("#EXTM3U")
        for (entry in playlist.entries) {
            appendLine("#EXTINF:-1,${entry.artist.replace(",", "")} - ${entry.title}")
            appendLine("${entry.title} - ${entry.artist}")
        }
    }

    data class Parsed(val name: String, val spotifyUrl: String?,
                      val entries: List<PlaylistEntry>)

    fun parseJson(text: String): Parsed {
        val root = Gson().fromJson(text.trim(), ExportedPlaylist::class.java)
            ?: throw IllegalArgumentException("Fichier de playlist illisible")
        require(root.kind == "ketvoice-playlist") { "Ce fichier n'est pas une playlist Ketvoice" }
        val name = PlaylistRules.name(root.name)
        require(root.entries.size in 1..500) { "La playlist doit contenir entre 1 et 500 titres" }
        val entries = root.entries.map { entry ->
            val title = entry.title.trim()
            val artist = entry.artist.trim()
            require(title.isNotEmpty() && artist.isNotEmpty() &&
                title.length <= 200 && artist.length <= 200) { "Entrée invalide dans la playlist" }
            PlaylistEntry(title = title, artist = artist,
                spotifyId = entry.spotifyId?.takeIf { it.matches(Regex("[A-Za-z0-9]{10,40}")) })
        }
        return Parsed(name, root.spotifyUrl?.takeIf { it.startsWith("https://") }, entries)
    }

    /** M3U lines carry "Title - Artist"; entries resolve later like import previews. */
    fun parseM3u(text: String): Parsed {
        var name = ""
        val entries = mutableListOf<PlaylistEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U")) continue
            if (line.startsWith("#PLAYLIST:")) { name = line.removePrefix("#PLAYLIST:").trim(); continue }
            if (line.startsWith("#")) continue
            val split = line.indexOf(" - ")
            if (split in 1..<line.length - 3) {
                entries += PlaylistEntry(title = line.substring(0, split).trim().take(200),
                    artist = line.substring(split + 3).trim().take(200))
            }
        }
        require(entries.size in 1..500) { "Aucun titre lisible dans ce fichier M3U" }
        return Parsed(name.ifBlank { "Import M3U" }, null, entries)
    }

    private fun share(context: Context, fileName: String, content: String, mime: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Exporter la playlist"))
    }

    suspend fun exportJson(context: Context, playlist: LocalPlaylist) = withContext(Dispatchers.IO) {
        val safeName = playlist.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "playlist" }
        share(context, "$safeName.ketvoice.json", toJson(playlist), "application/json")
    }

    suspend fun exportM3u(context: Context, playlist: LocalPlaylist) = withContext(Dispatchers.IO) {
        val safeName = playlist.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "playlist" }
        share(context, "$safeName.m3u", toM3u(playlist), "audio/x-mpegurl")
    }
}
