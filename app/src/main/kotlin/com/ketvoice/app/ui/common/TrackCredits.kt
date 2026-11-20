package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * « À propos de ce titre » (7.3, owner: « Info about this song »): who made a title and how it plays, from what the
 * app already knows — the title itself, the catalogue (Deezer's public track and album pages) and the decoder. A row
 * is shown only when its value is known. The catalogue lists no writers, composers or producers, so the sheet never
 * invents them.
 */
internal object TrackCredits {
    /** The title whose sheet is open; set from a title's ⋯ menu or the player's. */
    val open = mutableStateOf<Track?>(null)

    /** What the catalogue adds about a title. Everything may be missing. */
    data class Details(val main: List<String> = emptyList(), val featured: List<String> = emptyList(),
                       val released: String? = null, val label: String? = null, val genres: List<String> = emptyList(),
                       val bpm: Int? = null, val isrc: String? = null)

    /** One labelled line of the sheet (labels in French, translated at display; values as they are). */
    data class Row(val label: String, val value: String, val tag: String)

    /** The artists named by a title's own artist line, split where catalogues join them. */
    fun splitArtists(line: String): List<String> =
        line.split(Regex("\\s*(?:,|&|/|;| feat\\.? | ft\\.? )\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    /** The sheet's rows for [track], with the catalogue's [details] and the [quality] line, empty ones left out. */
    fun rows(track: Track, details: Details, quality: String?, year: (String) -> String? = ::yearOf): List<Row> {
        val main = details.main.ifEmpty { splitArtists(track.artist) }
        val featured = details.featured.filter { f -> main.none { it.equals(f, ignoreCase = true) } }
        return listOfNotNull(
            Row(if (main.size > 1) "Artistes" else "Artiste", main.joinToString(", "), "credits-artists").takeIf { main.isNotEmpty() },
            Row("Avec", featured.joinToString(", "), "credits-featured").takeIf { featured.isNotEmpty() },
            Row("Album", track.album.trim(), "credits-album").takeIf { track.album.isNotBlank() },
            details.released?.let(year)?.let { Row("Année", it, "credits-year") },
            details.label?.trim()?.takeIf { it.isNotBlank() }?.let { Row("Label", it, "credits-label") },
            Row("Genre", details.genres.joinToString(", "), "credits-genre").takeIf { details.genres.isNotEmpty() },
            Row("Durée", duration(track.durationMs), "credits-duration").takeIf { track.durationMs > 0 },
            details.bpm?.takeIf { it in 30..300 }?.let { Row("Tempo", "$it BPM", "credits-bpm") },
            (details.isrc ?: track.isrc)?.trim()?.takeIf { it.isNotBlank() }?.let { Row("ISRC", it.uppercase(), "credits-isrc") },
            quality?.takeIf { it.isNotBlank() }?.let { Row("Qualité audio", it, "credits-quality") })
    }

    /** The year of a « 2024-03-01 » catalogue date. */
    fun yearOf(date: String): String? = Regex("^(\\d{4})").find(date.trim())?.groupValues?.get(1)?.takeIf { it != "0000" }

    private fun duration(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    /** Reads a Deezer track page (and its album's for the label and genres). */
    fun parse(track: JSONObject?, album: JSONObject?): Details {
        if (track == null) return Details()
        val contributors = track.optJSONArray("contributors")
        val main = mutableListOf<String>(); val featured = mutableListOf<String>()
        if (contributors != null) for (i in 0 until contributors.length()) {
            val c = contributors.optJSONObject(i) ?: continue
            val name = c.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
            if (c.optString("role").equals("Featured", ignoreCase = true)) featured += name else main += name
        }
        val genres = album?.optJSONObject("genres")?.optJSONArray("data")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        fun text(o: JSONObject?, key: String) = o?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
        return Details(main.distinct(), featured.distinct(), text(track, "release_date") ?: text(album, "release_date"),
            text(album, "label"), genres, track.optDouble("bpm", 0.0).toInt().takeIf { it > 0 }, text(track, "isrc"))
    }

    /** The catalogue's details of a Deezer title, off the main thread; nothing for other sources. */
    suspend fun fetch(track: Track): Details = withContext(Dispatchers.IO) {
        val id = track.addonId?.takeIf { track.addon == "um" && it.isNotBlank() && it.all(Char::isDigit) } ?: return@withContext Details()
        val page = com.ketvoice.app.data.CatalogRepo.get("track/$id")
        val albumId = page?.optJSONObject("album")?.optLong("id")?.takeIf { it > 0 }
        parse(page, albumId?.let { com.ketvoice.app.data.CatalogRepo.get("album/$it") })
    }
}

/** The quality line: the Lossless grade when the codec says so, else what the decoder reads for the title playing. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun rememberQualityLine(track: Track): String? {
    val playing = com.ketvoice.app.player.Player.state.collectAsState().value.current?.id == track.id
    val grade = if (playing) rememberPlayingGrade(track.id) else rememberSavedGrade(track.id)
    val decoded by produceState<String?>(null, track.id, playing) {
        value = if (playing) com.ketvoice.app.player.Player.decodedAudioFormat()?.let { f ->
            listOfNotNull(f.sampleMimeType?.substringAfter('/')?.uppercase(),
                f.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(java.util.Locale.ROOT, it / 1000f).replace(".0 ", " ") },
                f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kb/s" }).joinToString(" · ").takeIf { it.isNotBlank() }
        } else null
    }
    return listOfNotNull(grade?.label, decoded).joinToString(" · ").takeIf { it.isNotBlank() }
}

/** Draws the credits sheet that was asked for. Place it once, at the root. */
@Composable
internal fun TrackCreditsHost(modifier: Modifier = Modifier) {
    val track = TrackCredits.open.value ?: return
    androidx.activity.compose.BackHandler { TrackCredits.open.value = null }
    Box(modifier.fillMaxSize()) {
        SpringSheet({ TrackCredits.open.value = null }, insets = Modifier.statusBarsPadding().navigationBarsPadding()) { room ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) { TrackCreditsPanel(track, room - 16.dp) }
        }
    }
}

/** The sheet's body: the title, then its credits as label and value rows. */
@Composable
internal fun TrackCreditsPanel(track: Track, max: Dp, details: TrackCredits.Details? = null) {
    val theme = LocalTheme.current
    val fetched by produceState(details, track.id) { if (details == null) value = runCatching { TrackCredits.fetch(track) }.getOrNull() ?: TrackCredits.Details() }
    val quality = rememberQualityLine(track)
    val rows = remember(track, fetched, quality) { TrackCredits.rows(track, fetched ?: TrackCredits.Details(), quality) }
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl)
    val shape = RoundedCornerShape(Ket27.Metrics.alertRadius)
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(8.dp).heightIn(max = max).clip(shape).background(theme.card)
        .pointerInput(Unit) { detectTapGestures { } }.testTag("track-credits")) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(36.dp, 5.dp).clip(CircleShape)
            .background(theme.secondary.copy(alpha = .4f)))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 18.dp)) {
            Text("À propos de ce titre", color = theme.secondary, style = Ket27.Type.footnote, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.title, cover, 56.dp, 10.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(track.title, localize = false, color = theme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (track.explicit) EBadge(Modifier.padding(start = 6.dp))
                    }
                    Text(track.artist, localize = false, color = theme.secondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            rows.forEach { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp).testTag(row.tag)) {
                    Text(row.label, color = theme.secondary, style = Ket27.Type.footnote)
                    Text(row.value, localize = false, color = theme.onBackground, fontSize = 16.sp, lineHeight = 21.sp)
                }
                Box(Modifier.fillMaxWidth().height(.5.dp).background(theme.onBackground.copy(alpha = .10f)))
            }
            if (fetched == null) ActivitySpinner(Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally).size(22.dp))
        }
    }
}
