package com.ketvoice.app.util

import android.content.Context
import android.content.Intent
import com.ketvoice.app.data.Track
import java.net.URLEncoder

/**
 * Liens de partage Ketvoice.
 *
 * Un morceau partagé produit une URL https servie par la page GitHub Pages du
 * projet (`listen.html`) : chez un ami SANS l'app, la page s'ouvre dans le
 * navigateur avec le titre + un bouton de téléchargement ; AVEC l'app installée,
 * la page bascule automatiquement via une URL `intent://` vers
 * `ketvoice://listen?...` et la lecture démarre dans Ketvoice.
 */
object ShareLinks {

    /** Page d'atterrissage publiée (branche main → GitHub Pages). */
    const val LISTEN_PAGE = "https://tysnnn23.github.io/Ketvoice/listen.html"
    const val DOWNLOAD_PAGE = "https://github.com/Tysnnn23/Ketvoice/releases/latest"

    fun trackUrl(id: String, title: String, artist: String): String = buildString {
        append(LISTEN_PAGE)
        append("?track="); append(enc(id))
        append("&title="); append(enc(title))
        append("&artist="); append(enc(artist))
    }

    fun albumUrl(id: String, title: String, artist: String): String = buildString {
        append(LISTEN_PAGE)
        append("?track="); append(enc(id))
        append("&title="); append(enc(title))
        append("&artist="); append(enc(artist))
        append("&kind=album")
    }

    /** Texte partagé : le lien fait tout le travail (app ou page web). */
    fun trackText(track: Track): String =
        "${track.title} — ${track.artist}\n${trackUrl(track.id, track.title, track.artist)}"

    fun shareTrack(context: Context, track: Track) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "${track.title} — ${track.artist}")
            putExtra(Intent.EXTRA_TEXT, trackText(track))
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, com.ketvoice.app.data.AppText.t("Partager le morceau")))
        }
    }

    fun shareAlbum(context: Context, id: String, title: String, artist: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_TEXT, "$title — $artist\n${albumPageUrl(id, title, artist)}")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, com.ketvoice.app.data.AppText.t("Partager l'album")))
        }
    }

    /**
     * A real, openable page for an artist: its YouTube Music channel when the id
     * is known, otherwise an artist search on the same service.
     */
    fun artistUrl(id: String?, name: String): String =
        if (!id.isNullOrBlank() && id.matches(Regex("UC[A-Za-z0-9_-]{10,}")))
            "https://music.youtube.com/channel/$id"
        else "https://music.youtube.com/search?q=" + enc(name)

    fun shareArtist(context: Context, id: String?, name: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
            putExtra(Intent.EXTRA_TEXT, "$name\n${artistUrl(id, name)}")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, com.ketvoice.app.data.AppText.t("Partager l'artiste")))
        }
    }

    /** A real YouTube Music page for a catalogue album, or the Ketvoice page otherwise. */
    fun albumPageUrl(id: String, title: String, artist: String): String =
        if (id.startsWith("MPRE")) "https://music.youtube.com/browse/$id" else albumUrl(id, title, artist)

    fun playlistUrl(id: String, name: String): String = buildString {
        append(LISTEN_PAGE)
        append("?playlist="); append(enc(id))
        append("&name="); append(enc(name))
    }

    fun sharePlaylist(context: Context, id: String, name: String, count: Int) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
            putExtra(Intent.EXTRA_TEXT, "$name · $count titres\n${playlistUrl(id, name)}")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, com.ketvoice.app.data.AppText.t("Partager la playlist")))
        }
    }

    /** Liked songs / recent / all / downloads: one stable Ketvoice page per section. */
    fun collectionUrl(kind: String, title: String): String = buildString {
        append(LISTEN_PAGE)
        append("?collection="); append(enc(kind))
        append("&name="); append(enc(title))
    }

    fun shareCollection(context: Context, kind: String, title: String, count: Int) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_TEXT, "$title · $count titres\n${collectionUrl(kind, title)}")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, com.ketvoice.app.data.AppText.t("Partager")))
        }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}
