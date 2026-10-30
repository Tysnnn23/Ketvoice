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
            putExtra(Intent.EXTRA_TEXT, "$title — $artist\n${albumUrl(id, title, artist)}")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, com.ketvoice.app.data.AppText.t("Partager l'album")))
        }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}
