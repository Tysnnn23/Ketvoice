package com.ketvoice.desktop.ui

/**
 * The address of a cover at the size the window shows it. Each catalogue names its picture's size in the address
 * (Apple's `100x100bb`, Deezer's `250x250-…`, Google's `=w120-h120`, Spotify's size code, YouTube's `hqdefault`,
 * SoundCloud's `-large`, Tidal's `320x320`): a small one stretched over a 27" 1440p screen is what looked soft.
 * [candidates] asks for the size needed (never past what each service serves) and falls back to the address as given,
 * so a size a service does not have never leaves the placeholder.
 */
internal object CoverUrls {
    /** The decode steps, in pixels on the long side: a few sizes, so a resize does not fetch again at every pixel. */
    val STEPS = intArrayOf(128, 256, 512, 768, 1024, 1536, 2048, 3000)
    /** What a cover is asked at when the place it fills is not known (a background, the thumbnail bar). */
    const val DEFAULT_PX = 1024

    /** The smallest step that covers [px] (the largest step beyond it). */
    fun step(px: Int): Int = STEPS.firstOrNull { it >= px } ?: STEPS.last()

    /** The addresses to try for [url] shown at [px], best first and the address as given last; empty for none. */
    fun candidates(url: String?, px: Int): List<String> {
        val given = url?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (!given.startsWith("https://") && !given.startsWith("http://")) return listOf(given)
        return (sized(given, step(px)) + given).distinct()
    }

    /** The bigger addresses of [url] for [px], best first (empty when its service is not known). */
    internal fun sized(url: String, px: Int): List<String> {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return when {
            host.endsWith("mzstatic.com") -> listOfNotNull(apple(url, px))
            host.endsWith("dzcdn.net") -> listOfNotNull(deezer(url, px))
            host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com") -> listOfNotNull(google(url, px))
            host == "i.scdn.co" -> spotify(url, px)
            host.endsWith("ytimg.com") -> youtube(url, px)
            host.endsWith("sndcdn.com") -> listOfNotNull(soundcloud(url))
            host == "resources.tidal.com" -> listOfNotNull(tidal(url, px))
            else -> emptyList()
        }
    }

    private val appleSize = Regex("""/(\d{2,4})x(\d{2,4})(?:bb|cc|sr|-\d+)?(?:-\d+)?\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)
    private val appleTemplate = Regex("""\{w\}x\{h\}(\w*)""")

    /** Apple: any size is served from the master, up to its own (3000 is the store's largest). */
    private fun apple(url: String, px: Int): String? {
        val n = px.coerceAtMost(3000)
        if (appleTemplate.containsMatchIn(url)) return appleTemplate.replace(url) { "${n}x${n}${it.groupValues[1].ifEmpty { "bb" }}" }
            .replace("{f}", "jpg")
        val m = appleSize.find(url) ?: return null
        if (m.groupValues[1].toInt() >= n) return null
        return url.replaceRange(m.range, "/${n}x${n}bb.${m.groupValues[3]}")
    }

    private val deezerSize = Regex("""/(\d{2,4})x(\d{2,4})-""")

    /** Deezer: `cover_xl` is 1000 px, the largest it serves for every picture. */
    private fun deezer(url: String, px: Int): String? {
        val n = px.coerceAtMost(1000)
        val m = deezerSize.find(url) ?: return null
        if (m.groupValues[1].toInt() >= n) return null
        return url.replaceRange(m.range, "/${n}x${n}-")
    }

    private val googleSize = Regex("""=(?:[swh]\d+[^/?#]*)$""")

    /** Google (YouTube Music): the `=w…-h…` suffix sets the size; without one, the original is served. */
    private fun google(url: String, px: Int): String? {
        val n = px.coerceAtMost(2400)
        val m = googleSize.find(url) ?: return null
        val asked = Regex("""[swh](\d+)""").findAll(m.value).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
        if (asked >= n) return null
        return url.replaceRange(m.range, "=w$n-h$n-l90-rj")
    }

    /** Spotify's album sizes: 64, 300, 640 and the upload itself (about 1000 to 2000). */
    private val spotifyAlbum = listOf("ab67616d00004851" to 64, "ab67616d00001e02" to 300, "ab67616d0000b273" to 640, "ab67616d000082c1" to 2000)
    private val spotifyArtist = listOf("ab6761610000f178" to 160, "ab67616100005174" to 320, "ab6761610000e5eb" to 640)

    private fun spotify(url: String, px: Int): List<String> {
        for (sizes in listOf(spotifyAlbum, spotifyArtist)) {
            val (code, size) = sizes.firstOrNull { it.first in url } ?: continue
            val bigger = sizes.filter { it.second > size }
            // The smallest size that covers the place, then the bigger-than-given ones below it.
            val pick = bigger.firstOrNull { it.second >= px } ?: bigger.lastOrNull() ?: return emptyList()
            return (listOf(pick) + bigger.filter { it.second < pick.second }.reversed()).map { url.replace(code, it.first) }
        }
        return emptyList()
    }

    private val youtubeThumb = Regex("""/(default|mqdefault|hqdefault|sddefault|hq720)(\.jpg|\.webp)""")

    /** YouTube: `maxresdefault` is the 1280 × 720 picture without bars; `hqdefault` is 480 × 360 with black bars. */
    private fun youtube(url: String, px: Int): List<String> {
        val m = youtubeThumb.find(url) ?: return emptyList()
        val wanted = if (px > 480) listOf("maxresdefault", "hq720", "sddefault") else listOf("hq720", "sddefault")
        return wanted.filter { it != m.groupValues[1] }.map { url.replaceRange(m.range, "/$it${m.groupValues[2]}").substringBefore('?') }
    }

    /** SoundCloud: `-t500x500` is the largest size every picture has (`-large` is 100 px). */
    private fun soundcloud(url: String): String? =
        Regex("""-(large|t300x300|crop|t67x67|badge|small|tiny|mini)\.""").find(url)?.let { url.replaceRange(it.range, "-t500x500.") }

    private val tidalSize = Regex("""/(\d{2,4})x(\d{2,4})\.jpg$""")

    /** Tidal: 80, 160, 320, 640, 750 and 1280 px. */
    private fun tidal(url: String, px: Int): String? {
        val m = tidalSize.find(url) ?: return null
        val n = if (px > 750) 1280 else if (px > 640) 750 else 640
        if (m.groupValues[1].toInt() >= n) return null
        return url.replaceRange(m.range, "/${n}x$n.jpg")
    }
}
