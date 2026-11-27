package com.ketvoice.app.tv

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Where a `ketvoice://tv/…` link leads: a page of the app, or picking up a title where it stopped. */
sealed interface TvLink {
    data class Open(val route: TvRoute) : TvLink
    /**
     * Resume [trackId] (the title of « Continuer l'écoute ») from [atMs] when it is no longer loaded, or what was
     * playing when it is null.
     */
    data class Resume(val trackId: String?, val atMs: Long = 0L) : TvLink
}

/**
 * The links the TV's home screen opens Ketvoice with (Ketvoice TV 1.1.0): the programmes of its channel and the
 * « Continuer l'écoute » entry. Built and read here only, by plain string work (no Android): host-tested.
 *
 * - `ketvoice://tv/home`, `ketvoice://tv/art-frame`, `ketvoice://tv/replay?month=2026-11`
 * - `ketvoice://tv/collection?kind=playlist&id=…&title=…&cover=…`
 * - `ketvoice://tv/artist?id=…&name=…`
 * - `ketvoice://tv/resume?track=…`
 */
object TvLinks {
    const val SCHEME = "ketvoice"
    const val HOST = "tv"
    private const val BASE = "$SCHEME://$HOST/"

    fun home(): String = BASE + "home"
    fun artFrame(): String = BASE + "art-frame"
    fun resume(trackId: String?, atMs: Long = 0L): String =
        BASE + "resume" + query("track" to trackId, "at" to atMs.takeIf { it > 0L }?.toString())
    fun replay(month: String?): String = BASE + "replay" + query("month" to month)
    fun collection(kind: String, id: String, title: String, cover: String?): String =
        BASE + "collection" + query("kind" to kind, "id" to id, "title" to title, "cover" to cover)
    fun artist(id: String, name: String): String = BASE + "artist" + query("id" to id, "name" to name)

    /** The link of a page that can be opened from the home screen, or null for one that cannot. */
    fun of(route: TvRoute): String? = when (route) {
        TvRoute.Home -> home()
        TvRoute.ArtFrame -> artFrame()
        is TvRoute.Replay -> replay(route.month)
        is TvRoute.Collection -> collection(route.kind, route.id, route.title, route.cover)
        is TvRoute.Artist -> artist(route.id, route.name)
        else -> null
    }

    private fun query(vararg pairs: Pair<String, String?>): String {
        val parts = pairs.filter { it.second != null }.map { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return if (parts.isEmpty()) "" else parts.joinToString("&", prefix = "?")
    }

    /** Reads a link; null for anything that is not one of Ketvoice TV's. */
    fun parse(link: String?): TvLink? {
        val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, true) || !uri.host.equals(HOST, true)) return null
        val q = params(uri.rawQuery)
        return when (uri.path.orEmpty().trim('/')) {
            "", "home" -> TvLink.Open(TvRoute.Home)
            "art-frame" -> TvLink.Open(TvRoute.ArtFrame)
            "replay" -> TvLink.Open(TvRoute.Replay(q["month"]?.takeIf { it.isNotBlank() }))
            "resume" -> TvLink.Resume(q["track"]?.takeIf { it.isNotBlank() }, q["at"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
            "collection" -> collection(q)
            "artist" -> q["name"]?.takeIf { it.isNotBlank() }?.let { TvLink.Open(TvRoute.Artist(q["id"].orEmpty(), it)) }
            else -> null
        }
    }

    private fun collection(q: Map<String, String>): TvLink? {
        val kind = q["kind"]?.takeIf { it.isNotBlank() } ?: return null
        val id = q["id"]?.takeIf { it.isNotBlank() } ?: return null
        return TvLink.Open(TvRoute.Collection(kind, id, q["title"].orEmpty(), q["cover"]?.takeIf { it.isNotBlank() }))
    }

    private fun params(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to runCatching { URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrDefault("")
    }.toMap()
}
