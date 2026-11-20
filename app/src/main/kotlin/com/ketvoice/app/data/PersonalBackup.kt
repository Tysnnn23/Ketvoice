package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

internal data class PersonalBackupData(
    val playlists: List<LocalPlaylist>, val favorites: Set<String>, val settings: Map<String, Any>,
    val recent: List<Track>, val known: List<Track>, val counts: Map<String, Int>, val listenedMs: Long,
    val lyricOffsets: Map<String, Int> = emptyMap(),
    /** The listener's Ketmix mix points (6.1, DJ editor), keyed by digests of the titles' ids. */
    val ketmixPoints: Map<String, String> = emptyMap(),
    /** Blocked and favourite artists (7.0, `ArtistPrefs.encode` of each), empty in older backups. */
    val artistBlocked: String = "",
    val artistFavourites: String = "",
    /** Listening behind Replay and Wrapped (7.0): `days`, `years` and `month:YYYY-MM`, as the history stores them. */
    val listening: Map<String, String> = emptyMap()
)

/**
 * Portable metadata only: no accounts, credentials, source URLs or downloaded files. Since 7.0 it also carries
 * the blocked and favourite artists and the listening counters of Replay and Wrapped; Air Ket (`airket/`) sends it
 * to another phone and adds the downloads. Never in it: the Ketvoice, Discord and YouTube Music sessions, keys,
 * custom sources, local music links, caches and the queue.
 */
internal object PersonalBackup {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val booleans = setOf("reduceAnimations", "reduceDynamicBlur", "liquidGlass", "fullArtwork",
        "classicGradient", "animatedArtwork", "albumHeroCanvas", "downloadsWifiOnly", "panoramicCanvas",
        "sonicCanvas", "albumColors", "artworkDepth", "livingArtwork", "coverAccent", "volumeLeveling", "coverBackground", "ketmixStems", "ketmixStemsCharging", "ketmixSmartQueue", "ketmixOnSkip", "ketmixLibrary", "autoResume", "lyricVideoSound", "lockscreenLyrics", "autoOfflineFavorites", "smartOffline", "smartOfflineCharging", "canvasWithMobileData", "canvasPreloadWifi", "artistPhotos", "highRefreshRate",
        "syncedLyrics", "skipSilence", "equalizerEnabled", "progressiveQuality",
        "highPerformanceMode", "questKeepAwake", "filterNonAudio", "localMusicEnrich", "playNextOnSwipe",
        "noRepeatSuggestions", "stopOnSwipeFromRecents", "hideVolumeBar", "hideStatusBarInPlayer",
        "showQueueHistory", "hideTitleStatus", "languageChosen", "lastfmGenres", "smartAudioAlignment", "techStats",
        "haptics", "coverTintedGlass", "glassTilt", "glassTinted", "trueBlack", "meshBackground", "automixOn", "automixAlbums", "ketmixLoops", "smartShuffle", "ketmixHaptics")
    private val integers = mapOf("themeMode" to 0..2, "glassStyle" to 0..com.ketvoice.app.glass.GlassStyles.MAX, "glassTintLevel" to com.ketvoice.app.glass.GlassClarity.MIN..com.ketvoice.app.glass.GlassClarity.MAX, "lyricsTextSize" to 0..2, "albumHeader" to 0..2,
        "reverbAmount" to 0..100, "reverbSize" to 0..100, "reverbDamping" to 0..100,
        "lyricsMode" to 0..2, "automixIntensity" to 0..2, "ketmixAnalysis" to 0..2, "ketmixStyle" to 0..5, "ketmixStemsQuality" to 0..2, "voiceLevel" to 0..100, "canvasCacheMb" to 256..2048, "smartOfflineGb" to 0..10, "smartOfflinePercent" to 10..50, "downloadQualityMode" to 0..2, "appIconVariant" to 0..ViceCityEdition.ICON_VARIANT)
    private val longs = setOf("accentArgb", "cacheBytes")
    private val strings = setOf("lyricsSource", "translateLanguage", "translationSource", "wifiQuality",
        "mobileQuality", "equalizerGains", "language")

    private fun cleanId(id: String): Boolean = id.isNotBlank() && id.length <= 256 &&
        id.none(Char::isISOControl) && "://" !in id && '?' !in id && '#' !in id

    private fun spotifyPlaylist(url: String?): String? = url?.substringBefore('?')
        ?.takeIf { it.matches(Regex("https://open\\.spotify\\.com/playlist/[A-Za-z0-9]{10,40}")) }

    private fun safeTrack(track: Track): Track {
        require(cleanId(track.id) && track.title.isNotBlank() && track.title.length <= 200 &&
            track.artist.length <= 200 && track.album.length <= 200)
        val local = track.streamUrl?.takeIf { track.id.startsWith("local:") && it.startsWith("content://") && '?' !in it && '#' !in it }
        return track.copy(streamUrl = local, downloadUrl = null, canvasUrl = null, coverUrl = null,
            addon = track.addon?.takeIf(::cleanId), addonId = track.addonId?.takeIf(::cleanId),
            videoId = track.videoId?.takeIf(::cleanId), spotifyId = track.spotifyId?.takeIf(::cleanId),
            isrc = track.isrc?.takeIf(::cleanId), albumId = track.albumId?.takeIf(::cleanId),
            artistId = track.artistId?.takeIf(::cleanId), sourceOverride = track.sourceOverride?.takeIf(::cleanId),
            lyricsProvider = track.lyricsProvider.takeIf(::cleanId) ?: "LRCLIB")
    }

    /** The checked value of a setting a backup (or the account's sync) may carry, or null. */
    internal fun setting(key: String, raw: Any?): Any? = when {
        // The Vice City edition (6.1) is saved and restored only while it lasts; turning it off always goes through.
        key == "viceCityTheme" -> (raw as? Boolean)?.takeIf { !it || ViceCityEdition.themeAvailable() }
        // A season (6.2) is restored only while it lasts; turning it off always goes through.
        key == "seasonTheme" -> (raw as? String)?.takeIf { Seasons.storable(it) }
        key == "appIconVariant" -> (raw as? Number)?.toInt()
            ?.takeIf { it in integers.getValue(key) && ViceCityEdition.iconAllowed(it) }
        key in booleans || key.startsWith("design212_") && DesignChoices.all.any { "design212_${it.code}" == key } -> raw as? Boolean
        key in integers -> (raw as? Number)?.toInt()?.takeIf { it in integers.getValue(key) }
        key == "accentArgb" -> (raw as? Number)?.toLong()?.takeIf { it in 0L..0xffffffffL }
        key == "cacheBytes" -> (raw as? Number)?.toLong()?.takeIf { it in 500_000_000L..10_000_000_000L }
        key == "glassTint" -> (raw as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0f..1f }
        key in strings -> (raw as? String)?.takeIf { value ->
            when (key) {
                "wifiQuality", "mobileQuality" -> value in setOf("Normal", "Élevé", "Sans perte")
                "lyricsSource" -> value in MusicApi.lyricSources + listOf("auto", "off")
                "language" -> AppLanguage.all.any { it.label == value }
                "equalizerGains" -> value.matches(Regex("-?\\d{1,2}(,-?\\d{1,2}){0,9}"))
                "translateLanguage", "translationSource" -> value in TranslationLanguages.codes ||
                    TranslationLanguages.codes.any { TranslationLanguages.name(it) == value }
                else -> false
            }
        }
        else -> null
    }

    fun collect(context: Context): PersonalBackupData {
        val settings = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).all.mapNotNull { (key, raw) ->
            setting(key, raw)?.let { key to it }
        }.toMap()
        val offsets = context.getSharedPreferences("lyrics-offsets-v1", Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            (value as? Int)?.takeIf { key.matches(Regex("[0-9a-f]{64}")) && it in -10_000..10_000 }?.let { key to it }
        }.toMap()
        val points = context.getSharedPreferences(com.ketvoice.app.automix.MixPointStore.PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            (value as? String)?.takeIf { com.ketvoice.app.automix.MixPointStore.validEntry(key, it) }?.let { key to it }
        }.toMap()
        val artists = context.getSharedPreferences("artist-prefs", Context.MODE_PRIVATE)
        return PersonalBackupData(PlaylistStore.lists.value, AppSettings.favoriteIds.value.filter(::cleanId).toSet(),
            settings, ListeningHistory.tracks.value, ListeningHistory.knownTracks.value,
            ListeningHistory.playCounts.value.filterKeys(::cleanId), ListeningHistory.listenedMs.value, offsets, points,
            artists.getString("blocked", "").orEmpty(), artists.getString("favourites", "").orEmpty(), collectListening(context))
    }

    private const val MAX_LISTENING_VALUE = 400_000

    private fun listeningKey(key: String) = key == "days" || key == "years" ||
        (key.startsWith(ListeningMonths.PREFIX) && ListeningMonths.isKey(key.removePrefix(ListeningMonths.PREFIX)))

    private fun collectListening(context: Context): Map<String, String> {
        ListeningHistory.flushListenedMs()
        val prefs = context.getSharedPreferences("listening-history", Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.takeIf { listeningKey(key) && it.length <= MAX_LISTENING_VALUE }?.let { key to it }
        }.toMap()
    }

    private fun artistText(text: String): String {
        if (text.isEmpty()) return ""
        require(text.length <= 200_000)
        // Must read back as a list of artists, else it is dropped.
        return if (ArtistPrefs.decode(text).isNotEmpty() || text == "[]") text else ""
    }

    fun encode(data: PersonalBackupData): ByteArray {
        require(data.playlists.size <= 100 && data.favorites.size <= 5000 && data.recent.size <= 80 &&
            data.known.size <= 400 && data.counts.size <= 550 && data.lyricOffsets.size <= 5000 &&
            data.ketmixPoints.size <= com.ketvoice.app.automix.MixPointStore.LIMIT)
        fun trackArray(tracks: List<Track>) = JSONArray().apply { tracks.forEach { put(DownloadQueue.trackToJson(safeTrack(it))) } }
        val playlists = JSONArray().apply {
            data.playlists.forEach { playlist ->
                require(cleanId(playlist.id) && playlist.entries.size <= 500)
                val entries = JSONArray().apply { playlist.entries.forEach { entry ->
                    require(cleanId(entry.id) && entry.title.isNotBlank() && entry.title.length <= 200 && entry.artist.length <= 200)
                    put(JSONObject().put("id", entry.id).put("title", entry.title).put("artist", entry.artist)
                        .put("spotifyId", entry.spotifyId?.takeIf { it.matches(Regex("[A-Za-z0-9]{10,40}")) })
                        .put("track", entry.track?.let { DownloadQueue.trackToJson(safeTrack(it)) }))
                } }
                put(JSONObject().put("id", playlist.id).put("name", PlaylistRules.name(playlist.name))
                    .put("spotifyUrl", spotifyPlaylist(playlist.spotifyUrl)).put("createdAt", playlist.createdAt)
                    .put("pinned", playlist.pinned).put("entries", entries))
            }
        }
        val settings = JSONObject().apply { data.settings.forEach { (key, value) -> setting(key, value)?.let { put(key, it) } } }
        val root = JSONObject().put("kind", "ketvoice-personal-backup").put("version", 1)
            .put("playlists", playlists).put("favorites", JSONArray(data.favorites.filter(::cleanId)))
            .put("settings", settings).put("recent", trackArray(data.recent)).put("known", trackArray(data.known))
            .put("counts", JSONObject(data.counts.filter { (key, value) -> cleanId(key) && value in 0..1_000_000 }))
            .put("listenedMs", data.listenedMs.coerceAtLeast(0)).put("lyricOffsets", JSONObject(data.lyricOffsets.filter {
                (key, value) -> key.matches(Regex("[0-9a-f]{64}")) && value in -10_000..10_000
            })).put("ketmixPoints", JSONObject(data.ketmixPoints.filter { (key, value) -> com.ketvoice.app.automix.MixPointStore.validEntry(key, value) }))
            .put("artistBlocked", artistText(data.artistBlocked)).put("artistFavourites", artistText(data.artistFavourites))
            .put("listening", JSONObject(data.listening.filter { (key, value) -> listeningKey(key) && value.length <= MAX_LISTENING_VALUE }))
        return root.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): PersonalBackupData {
        require(bytes.size <= MAX_BYTES)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optString("kind") == "ketvoice-personal-backup" && root.optInt("version", -1) == 1)
        fun tracks(name: String, limit: Int): List<Track> {
            val rows = root.getJSONArray(name)
            require(rows.length() <= limit)
            return (0 until rows.length()).map { safeTrack(requireNotNull(DownloadQueue.trackFromJson(rows.optJSONObject(it)))) }
        }
        val array = root.getJSONArray("playlists")
        require(array.length() <= 100)
        val playlists = (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            val id = row.getString("id").also { require(cleanId(it)) }
            val entries = row.getJSONArray("entries")
            require(entries.length() <= 500)
            val parsed = (0 until entries.length()).map { j ->
                val entry = entries.getJSONObject(j)
                val entryId = entry.getString("id").also { require(cleanId(it)) }
                val title = entry.getString("title").also { require(it.isNotBlank() && it.length <= 200) }
                val artist = entry.optString("artist").also { require(it.length <= 200) }
                val spotify = entry.optString("spotifyId").takeIf { it.matches(Regex("[A-Za-z0-9]{10,40}")) }
                val track = entry.optJSONObject("track")?.let { safeTrack(requireNotNull(DownloadQueue.trackFromJson(it))) }
                PlaylistEntry(entryId, title, artist, spotify, track)
            }
            require(parsed.map { it.id }.distinct().size == parsed.size)
            LocalPlaylist(id = id, name = PlaylistRules.name(row.getString("name")), entries = parsed,
                spotifyUrl = spotifyPlaylist(row.optString("spotifyUrl")),
                createdAt = row.optLong("createdAt").coerceAtLeast(0), pinned = row.optBoolean("pinned"))
        }
        require(playlists.map { it.id }.distinct().size == playlists.size)
        val favoriteArray = root.getJSONArray("favorites")
        require(favoriteArray.length() <= 5000)
        val favorites = (0 until favoriteArray.length()).map { favoriteArray.getString(it).also { id -> require(cleanId(id)) } }.toSet()
        val rawSettings = root.getJSONObject("settings")
        val settings = rawSettings.keys().asSequence().mapNotNull { key -> setting(key, rawSettings.opt(key))?.let { key to it } }.toMap()
        val rawCounts = root.getJSONObject("counts")
        require(rawCounts.length() <= 550)
        val counts = rawCounts.keys().asSequence().associateWith { key ->
            require(cleanId(key)); rawCounts.getInt(key).also { require(it in 0..1_000_000) }
        }
        val rawOffsets = root.optJSONObject("lyricOffsets") ?: JSONObject()
        require(rawOffsets.length() <= 5000)
        val offsets = rawOffsets.keys().asSequence().associateWith { key ->
            require(key.matches(Regex("[0-9a-f]{64}")))
            rawOffsets.getInt(key).also { require(it in -10_000..10_000) }
        }
        // Backups made before 6.1 have no mix points.
        val rawPoints = root.optJSONObject("ketmixPoints") ?: JSONObject()
        require(rawPoints.length() <= com.ketvoice.app.automix.MixPointStore.LIMIT)
        val points = rawPoints.keys().asSequence().associateWith { key ->
            rawPoints.getString(key).also { require(com.ketvoice.app.automix.MixPointStore.validEntry(key, it)) }
        }
        // Backups made before 7.0 carry neither artists nor listening counters.
        val rawListening = root.optJSONObject("listening") ?: JSONObject()
        require(rawListening.length() <= 200)
        val listening = rawListening.keys().asSequence().filter(::listeningKey).associateWith { key ->
            rawListening.getString(key).also { require(it.length <= MAX_LISTENING_VALUE) }
        }
        return PersonalBackupData(playlists, favorites, settings, tracks("recent", 80), tracks("known", 400),
            counts, root.optLong("listenedMs").also { require(it >= 0) }, offsets, points,
            artistText(root.optString("artistBlocked")), artistText(root.optString("artistFavourites")), listening)
    }

    fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= MAX_BYTES)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    suspend fun merge(context: Context, data: PersonalBackupData) = apply(context, data, replace = false)

    /**
     * Restores a backup. With [replace] (Air Ket's « Remplacer »), this device's playlists and favourites become
     * the backup's; without it they are merged and nothing already here is overwritten. Settings always take the
     * backup's values; history, artists and listening counters are always merged.
     */
    suspend fun apply(context: Context, data: PersonalBackupData, replace: Boolean) {
        val checked = withContext(Dispatchers.IO) { decode(encode(data)) }
        if (replace) PlaylistStore.replaceFromSync(checked.playlists) else PlaylistStore.mergeBackup(checked.playlists)
        withContext(Dispatchers.IO) { ListeningHistory.mergeBackup(checked.recent, checked.known, checked.counts, checked.listenedMs) }
        val prefs = context.applicationContext.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        // A backup of 5.x carries the continuous glass tint: it lands on the nearest step of the transparency.
        val legacyTint = checked.settings["glassTint"] as? Float
        if (legacyTint != null && "glassTintLevel" !in checked.settings) {
            editor.putInt("glassTintLevel", com.ketvoice.app.glass.GlassClarity.fromLegacyTint(legacyTint))
        }
        checked.settings.forEach { (key, raw) ->
            if (key == "glassTint") return@forEach
            when (val value = setting(key, raw)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        val favorites = prefs.getStringSet("favoriteIds", emptySet()).orEmpty().toSet()
        editor.putStringSet("favoriteIds", if (replace) checked.favorites else favorites + checked.favorites)
        check(withContext(Dispatchers.IO) { editor.commit() })
        SettingsStore.load(context)
        LyricsOffsets.init(context)
        withContext(Dispatchers.IO) { LyricsOffsets.mergeBackup(checked.lyricOffsets) }
        com.ketvoice.app.automix.MixPointStore.init(context)
        withContext(Dispatchers.IO) { com.ketvoice.app.automix.MixPointStore.mergeBackup(checked.ketmixPoints) }
        mergeArtists(context, checked)
        withContext(Dispatchers.IO) { ListeningHistory.mergeStats(checked.listening) }
    }

    private fun mergeArtists(context: Context, data: PersonalBackupData) {
        ArtistPrefs.init(context)
        val blocked = ArtistPrefs.blocked + ArtistPrefs.decode(data.artistBlocked)
        val favourites = (ArtistPrefs.favourites.associateBy { it.key } + ArtistPrefs.decode(data.artistFavourites)) - blocked.keys
        ArtistPrefs.replaceFromSync(blocked.values.toList(), favourites.values.toList())
    }
}
