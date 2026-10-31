package com.ketvoice.app.ui.update

import android.content.Context
import com.ketvoice.app.BuildConfig
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
    private val quest: Boolean = BuildConfig.IS_QUEST,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: () -> String = ::fetchUpdateFeed
) {
    private val prefs = context.applicationContext.getSharedPreferences("ketvoice_updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    fun lastCheckMs(): Long = if (prefs.getString("checked_version", null) == version)
        prefs.getLong("last_check", 0L) else 0L

    private fun visible(info: UpdateInfo?, force: Boolean): UpdateInfo? = info?.takeIf {
        UpdateChecker.isNewer(it.tag, version) && (force ||
            (it.tag != prefs.getString("skipped_tag", null) && it.tag != prefs.getString("dismissed_tag", null)))
    }

    fun cached(force: Boolean = false): UpdateInfo? = visible(runCatching {
        val raw = prefs.getString("available_release", null) ?: return@runCatching null
        UpdateFeed.select(JSONArray(raw), version, quest)
    }.getOrNull(), force)

    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val time = now()
            val last = lastCheckMs()
            if (!force && last > 0L && time >= last && time - last < UpdateChecker.CHECK_INTERVAL_MS)
                return@withLock cached()
            // Do not advance last_check on HTTP/JSON failures: returning to the app can retry.
            val feed = JSONArray(fetch())
            val candidate = UpdateFeed.select(feed, version, quest)
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

    fun validTag(tag: String): Boolean = versionPattern.matches(tag)

    fun select(feed: JSONArray, current: String, quest: Boolean): UpdateInfo? {
        var newest: UpdateInfo? = null
        for (index in 0 until feed.length()) {
            val release = feed.optJSONObject(index) ?: continue
            val tag = release.optString("tag_name")
            if (release.optBoolean("draft") || !validTag(tag) || !UpdateChecker.isNewer(tag, current)) continue
            if (!current.substringBefore('+').contains('-') &&
                (release.optBoolean("prerelease") || tag.substringBefore('+').contains('-'))) continue
            val version = tag.removePrefix("v").removePrefix("V")
            val expected = "Ketvoice-$version${if (quest) "-Quest" else ""}.apk"
            val assets = release.optJSONArray("assets") ?: continue
            val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstOrNull {
                it.optString("name").equals(expected, ignoreCase = true) &&
                    it.optString("state", "uploaded") == "uploaded" && it.optLong("size") > 0 &&
                    (it.optString("digest").isBlank() || digestPattern.matches(it.optString("digest"))) &&
                    it.optString("browser_download_url").startsWith(DOWNLOAD_PREFIX)
            } ?: continue
            val info = UpdateInfo(tag, release.optString("name").ifBlank { "Ketvoice $tag" },
                release.optString("body").lines(), asset.optString("browser_download_url"),
                "https://github.com/Tysnnn23/Ketvoice/releases/tag/$tag",
                release.optString("published_at").take(10), asset.optLong("size"),
                digestPattern.matchEntire(asset.optString("digest"))?.groupValues?.get(1)?.lowercase())
            if (newest == null || UpdateChecker.isNewer(tag, newest.tag)) newest = info
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
