package com.ketvoice.app.ui.update

import android.content.Context
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.Edition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Public release metadata only. No account/session credentials are sent to GitHub. */
internal class UpdateRepository(
    context: Context,
    private val version: String = BuildConfig.VERSION_NAME,
    /** Which APK of a release this build installs: mobile, Quest and Lite never update to another edition's. */
    private val edition: Edition = Edition.current,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: () -> String = ::fetchUpdateFeed
) {
    private val prefs = context.applicationContext.getSharedPreferences("ketvoice_updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    fun lastCheckMs(): Long = if (prefs.getString("checked_version", null) == version)
        prefs.getLong("last_check", 0L) else 0L

    private fun visible(info: UpdateInfo?, force: Boolean): UpdateInfo? = info?.takeIf {
        UpdateChecker.isNewer(it.versionLabel(), version) && (force ||
            (it.tag != prefs.getString("skipped_tag", null) && it.tag != prefs.getString("dismissed_tag", null)))
    }

    fun cached(force: Boolean = false): UpdateInfo? = visible(runCatching {
        val raw = prefs.getString("available_release", null) ?: return@runCatching null
        UpdateFeed.select(JSONArray(raw), version, edition)
    }.getOrNull(), force)

    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val time = now()
            val last = lastCheckMs()
            if (!force && last > 0L && time >= last && time - last < UpdateChecker.CHECK_INTERVAL_MS)
                return@withLock cached()
            // Do not advance last_check on HTTP/JSON failures: returning to the app can retry.
            val feed = JSONArray(fetch())
            val candidate = UpdateFeed.select(feed, version, edition)
            prefs.edit().putLong("last_check", time).putString("checked_version", version)
                .putString("available_release", feed.toString()).apply()
            visible(candidate, force)
        }
    }
}

internal object UpdateFeed {
    private val versionPattern = Regex("^[vV]?\\d+(?:\\.\\d+){1,3}(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?$")
    private val digestPattern = Regex("^sha256:([a-fA-F0-9]{64})$")
    const val DOWNLOAD_PREFIX = "https://github.com/Tysnnn23/Ketvoice/releases/download/"

    /** Ketvoice TV's own release line (`tv-v1.0.0`), never the latest release of the repository. */
    const val TV_TAG_PREFIX = "tv-v"
    const val SUMS_ASSET = "SHA256SUMS.txt"
    private val sumLine = Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$")

    /** A release tag of the phone line (`v9.1.1`) or of Ketvoice TV (`tv-v1.0.0`). */
    fun validTag(tag: String): Boolean = Edition.entries.any { versionOf(tag, it) != null }

    /**
     * The version [tag] carries for [edition], or null when the tag is not of that edition's line: mobile, Quest and
     * Lite read `v<version>` only (never `tv-v*`, `windows-v*` or the model tags), Ketvoice TV reads `tv-v<version>` only.
     */
    fun versionOf(tag: String, edition: Edition): String? {
        val rest = if (edition == Edition.Tv) {
            if (!tag.startsWith(TV_TAG_PREFIX)) return null
            tag.removePrefix(TV_TAG_PREFIX).takeIf { it.firstOrNull()?.isDigit() == true } ?: return null
        } else tag
        return if (versionPattern.matches(rest)) rest.removePrefix("v").removePrefix("V") else null
    }

    /** The SHA-256 listed for [file] in a `sha256sum` list (`<hash>  <name>` or `<hash> *<name>`), lower case. */
    fun sumFor(sums: String, file: String): String? = sums.lineSequence().mapNotNull { sumLine.matchEntire(it.trim()) }
        .firstOrNull { it.groupValues[2].trim() == file }?.groupValues?.get(1)?.lowercase()

    fun select(feed: JSONArray, current: String, quest: Boolean): UpdateInfo? =
        select(feed, current, if (quest) Edition.Quest else Edition.Mobile)

    /**
     * The newest release newer than [current] that carries this [edition]'s own APK. A release without it
     * (for Lite, every release published before Ketvoice Lite existed) is skipped, never answered with
     * another edition's APK. Ketvoice TV also needs the release's SHA256SUMS.txt, read before the download.
     */
    fun select(feed: JSONArray, current: String, edition: Edition): UpdateInfo? {
        var newest: UpdateInfo? = null
        var newestVersion = ""
        for (index in 0 until feed.length()) {
            val release = feed.optJSONObject(index) ?: continue
            val tag = release.optString("tag_name")
            val version = versionOf(tag, edition) ?: continue
            if (release.optBoolean("draft") || !UpdateChecker.isNewer(version, current)) continue
            if (!current.substringBefore('+').contains('-') &&
                (release.optBoolean("prerelease") || version.substringBefore('+').contains('-'))) continue
            val expected = edition.assetName(version)
            val assets = release.optJSONArray("assets") ?: continue
            val listed = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.filter {
                it.optString("state", "uploaded") == "uploaded" && it.optLong("size") > 0 &&
                    it.optString("browser_download_url").startsWith(DOWNLOAD_PREFIX)
            }
            val asset = listed.firstOrNull {
                it.optString("name").equals(expected, ignoreCase = true) &&
                    (it.optString("digest").isBlank() || digestPattern.matches(it.optString("digest")))
            } ?: continue
            val sums = if (edition == Edition.Tv) {
                listed.firstOrNull { it.optString("name") == SUMS_ASSET &&
                    it.optString("browser_download_url").startsWith("$DOWNLOAD_PREFIX$tag/") }
                    ?.optString("browser_download_url") ?: continue
            } else null
            val info = UpdateInfo(tag, release.optString("name").ifBlank { "Ketvoice $tag" },
                release.optString("body").lines(), asset.optString("browser_download_url"),
                "https://github.com/Tysnnn23/Ketvoice/releases/tag/$tag",
                release.optString("published_at").take(10), asset.optLong("size"),
                digestPattern.matchEntire(asset.optString("digest"))?.groupValues?.get(1)?.lowercase(), sums)
            if (newest == null || UpdateChecker.isNewer(version, newestVersion)) {
                newest = info
                newestVersion = version
            }
        }
        return newest
    }
}

internal fun fetchUpdateFeed(): String {
    val connection = URL("https://api.github.com/repos/Tysnnn23/Ketvoice/releases?per_page=20")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Ketvoice/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Cache-Control", "no-cache")
        if (connection.responseCode != 200) throw IOException("GitHub HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader().use { reader ->
            val text = reader.readText()
            if (text.length > 2_000_000) throw IOException("Release response too large")
            text
        }
    } finally {
        connection.disconnect()
    }
}
