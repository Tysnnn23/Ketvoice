package com.ketvoice.app.sync

import android.content.Context
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.DownloadQueue
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * One part of the library that follows the account: what it holds on this device as canonical JSON
 * values by key ([snapshot], read on the main thread), and how the merged values land back ([apply]).
 */
internal interface SyncKind {
    val name: String
    fun snapshot(context: Context, previous: SyncDoc): Map<String, String>
    suspend fun apply(context: Context, values: Map<String, String>)
}

/** A title as the account keeps it: what identifies and shows it, never a stream address or a downloaded file. */
internal object SyncTracks {
    fun clean(track: Track): Track = track.copy(
        streamUrl = track.streamUrl?.takeIf { track.id.startsWith("local:") && it.startsWith("content://") },
        downloadUrl = null, canvasUrl = null,
        coverUrl = track.coverUrl?.takeIf { it.startsWith("https://") && it.length <= 1000 })

    fun json(track: Track): JSONObject = DownloadQueue.trackToJson(clean(track))
    fun text(track: Track): String = Canonical.text(json(track))
    fun parse(text: String): Track? = runCatching { DownloadQueue.trackFromJson(JSONObject(text))?.let(::clean) }.getOrNull()
        ?.takeIf { it.id.length <= 256 && it.title.length <= 300 }
}

internal object FavoritesKind : SyncKind {
    override val name = "favorites"

    /** Titles that only exist on this device (its own files) stay out of the account. */
    fun syncable(id: String): Boolean = id.isNotBlank() && id.length <= 256 && !id.startsWith("local:")

    override fun snapshot(context: Context, previous: SyncDoc): Map<String, String> {
        val known = SyncedTracks.lookup()
        val before = previous.live
        // A value once synced stays as it is (devices whose histories hold other copies of a title must not
        // keep replacing each other's); only a bare id is completed when this device learns the title.
        return AppSettings.favoriteIds.value.filter(::syncable).associateWith { id ->
            before[id]?.takeIf { "\"title\"" in it } ?: known[id]?.let(SyncTracks::text) ?: before[id]
                ?: Canonical.text(JSONObject().put("id", id))
        }
    }

    override suspend fun apply(context: Context, values: Map<String, String>) {
        SyncedTracks.remember(context, values.values.mapNotNull(SyncTracks::parse))
        val kept = AppSettings.favoriteIds.value.filterNot(::syncable)
        AppSettings.favoriteIds.value = values.keys + kept
        com.ketvoice.app.data.SettingsStore.save()
    }
}

internal object PlaylistsKind : SyncKind {
    override val name = "playlists"
    const val ORDER = "order"
    private const val PREFIX = "pl:"

    override fun snapshot(context: Context, previous: SyncDoc): Map<String, String> {
        // Shared playlists live on their own server tables (SharedPlaylists), never in the personal library.
        val rows = PlaylistStore.lists.value.filterNot { com.ketvoice.app.data.PlaylistRules.isShared(it.id) }
        val out = rows.associate { PREFIX + it.id to Canonical.text(encode(it)) }.toMutableMap()
        if (rows.isNotEmpty()) out[ORDER] = Canonical.text(JSONArray(rows.map { it.id }))
        return out
    }

    fun encode(playlist: LocalPlaylist): JSONObject = JSONObject().put("name", playlist.name)
        .put("createdAt", playlist.createdAt).put("pinned", playlist.pinned)
        .put("spotifyUrl", playlist.spotifyUrl ?: JSONObject.NULL)
        .put("coverUrl", playlist.coverUrl?.takeIf { it.startsWith("https://") } ?: JSONObject.NULL)
        .put("entries", JSONArray(playlist.entries.map(::entry)))

    private fun entry(entry: PlaylistEntry): JSONObject = JSONObject().put("id", entry.id).put("title", entry.title)
        .put("artist", entry.artist).put("spotifyId", entry.spotifyId ?: JSONObject.NULL)
        .put("track", entry.track?.let(SyncTracks::json) ?: JSONObject.NULL)

    fun decode(id: String, text: String): LocalPlaylist? = runCatching {
        val json = JSONObject(text)
        val rows = json.getJSONArray("entries")
        require(rows.length() <= 500)
        val entries = (0 until rows.length()).map { decodeEntry(rows.getJSONObject(it)) }.distinctBy { it.id }
        LocalPlaylist(id = id, name = PlaylistRules.name(json.getString("name")), entries = entries,
            spotifyUrl = json.optString("spotifyUrl").takeIf { !json.isNull("spotifyUrl") && it.startsWith("https://open.spotify.com/") },
            coverUrl = json.optString("coverUrl").takeIf { !json.isNull("coverUrl") && it.startsWith("https://") },
            createdAt = json.optLong("createdAt").coerceAtLeast(0), pinned = json.optBoolean("pinned"))
    }.getOrNull()

    private fun decodeEntry(json: JSONObject): PlaylistEntry {
        val id = json.getString("id").also { require(it.isNotBlank() && it.length <= 256) }
        val title = json.getString("title").also { require(it.isNotBlank() && it.length <= 300) }
        return PlaylistEntry(id, title, json.optString("artist").take(300),
            json.optString("spotifyId").takeIf { !json.isNull("spotifyId") && it.matches(Regex("[A-Za-z0-9]{10,40}")) },
            json.optJSONObject("track")?.let { SyncTracks.parse(it.toString()) })
    }

    /** The playlists in the account's order; those it does not place yet come first, newest first. */
    fun ordered(values: Map<String, String>): List<LocalPlaylist> {
        val lists = values.filterKeys { it.startsWith(PREFIX) }.mapNotNull { (key, text) -> decode(key.removePrefix(PREFIX), text) }
        val order = values[ORDER]?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        val placed = order.withIndex().associate { it.value to it.index }
        val (known, unplaced) = lists.partition { it.id in placed }
        return (unplaced.sortedByDescending { it.createdAt } + known.sortedBy { placed.getValue(it.id) }).take(100)
    }

    override suspend fun apply(context: Context, values: Map<String, String>) {
        PlaylistStore.replaceFromSync(ordered(values))
    }
}

internal object ArtistsKind : SyncKind {
    override val name = "artists"

    override fun snapshot(context: Context, previous: SyncDoc): Map<String, String> =
        ArtistPrefs.blocked.values.associate { "block:" + it.key to Canonical.text(entry(it)) } +
            ArtistPrefs.favourites.associate { "fav:" + it.key to Canonical.text(entry(it)) }

    private fun entry(it: ArtistPrefs.Entry) = JSONObject().put("name", it.name).put("id", it.id).put("photo", it.photo ?: "")

    private fun parse(text: String): ArtistPrefs.Entry? = runCatching {
        val json = JSONObject(text)
        ArtistPrefs.Entry(json.getString("name").take(200), json.optString("id").take(256),
            json.optString("photo").takeIf { it.startsWith("https://") })
    }.getOrNull()?.takeIf { it.name.isNotBlank() }

    override suspend fun apply(context: Context, values: Map<String, String>) {
        val blocked = values.filterKeys { it.startsWith("block:") }.values.mapNotNull(::parse)
        val favourites = values.filterKeys { it.startsWith("fav:") }.values.mapNotNull(::parse)
        ArtistPrefs.replaceFromSync(blocked, favourites)
    }
}

/**
 * Settings that belong to the listener rather than to one device: look, lyrics, Ketmix, sound. Left out:
 * what depends on the device or its network (performance mode, quality per network, cache, downloads,
 * Quest's own settings, the interface language, which needs the system's locale).
 */
internal object SettingsKind : SyncKind {
    override val name = "settings"
    val keys = setOf("themeMode", "accentArgb", "glassStyle", "glassIntensity", "glassTintLevel", "glassTinted", "glassTilt", "trueBlack", "coverAccent", "coverBackground",
        "coverTintedGlass", "meshBackground", "albumColors", "animatedArtwork", "albumHeroCanvas", "albumHeader", "artworkDepth", "livingArtwork",
        "fullArtwork", "panoramicCanvas", "sonicCanvas", "artistPhotos", "lyricsTextSize", "lyricsMode", "syncedLyrics",
        "lyricsSource", "translateLanguage", "translationSource", "lyricsRomanization", "lockscreenLyrics", "automixOn", "automixIntensity",
        "automixAlbums", "ketmixLoops", "ketmixStyle", "ketmixSmartQueue", "ketmixOnSkip", "ketmixHaptics", "smartShuffle", "volumeLeveling",
        "skipSilence", "equalizerEnabled", "equalizerGains", "reverbAmount", "reverbSize", "reverbDamping", "voiceLevel",
        "noRepeatSuggestions", "playNextOnSwipe", "haptics", "autoOfflineFavorites")

    override fun snapshot(context: Context, previous: SyncDoc): Map<String, String> {
        com.ketvoice.app.data.SettingsStore.save()
        val prefs = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).all
        // Ketmix Studio's presets (`ketmix-presets-v1`) ride along, one item each; the store is opened first, so an
        // unopened store never reads as « every preset removed ».
        com.ketvoice.app.automix.MixPresetStore.init(context)
        val values = keys.mapNotNull { key -> com.ketvoice.app.data.PersonalBackup.setting(key, prefs[key])?.let { key to Canonical.text(it) } }.toMap() +
            com.ketvoice.app.automix.MixPresetStore.syncEntries().mapValues { Canonical.text(it.value) }
        // Phone control's secret rides in this owner-only document (see RemoteKeyStore); no setting holds it.
        val uid = com.ketvoice.app.account.AccountSession.userId() ?: return values
        val remote = com.ketvoice.app.remote.RemoteKeyStore
        return values + (remote.KEY to Canonical.text(remote.ensure(context, uid, previous.live[remote.KEY]?.let(::remoteKey))))
    }

    private fun remoteKey(text: String): String? = runCatching { JSONArray("[$text]").getString(0) }.getOrNull()

    fun parse(key: String, text: String): Any? = runCatching {
        com.ketvoice.app.data.PersonalBackup.setting(key, JSONArray("[$text]").get(0))
    }.getOrNull()

    /** A setting missing from the account (or removed) stays as this device has it. */
    override suspend fun apply(context: Context, values: Map<String, String>) {
        val uid = com.ketvoice.app.account.AccountSession.userId()
        val remote = values[com.ketvoice.app.remote.RemoteKeyStore.KEY]?.let(::remoteKey)
        if (uid != null && remote != null) com.ketvoice.app.remote.RemoteKeyStore.adopt(context, uid, remote)
        val editor = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).edit()
        values.forEach { (key, text) ->
            if (key !in keys) return@forEach
            when (val value = parse(key, text)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        // A device still on 7.4 sends its levels and Teinté: the intensity is worked out again from them.
        com.ketvoice.app.automix.MixPresetStore.init(context)
        com.ketvoice.app.automix.MixPresetStore.adoptSynced(values.filterKeys { it.startsWith(com.ketvoice.app.automix.MixPresetStore.SYNC_PREFIX) }
            .mapNotNull { (k, text) -> runCatching { JSONArray("[$text]").getString(0) }.getOrNull()?.let { k to it } }.toMap())
        com.ketvoice.app.glass.GlassClarity.legacyKeys(values.filter { (key, text) -> key in keys && parse(key, text) != null }.keys)?.let { editor.remove("glassIntensity") }
        editor.commit()
        com.ketvoice.app.data.SettingsStore.load(context)
    }
}
