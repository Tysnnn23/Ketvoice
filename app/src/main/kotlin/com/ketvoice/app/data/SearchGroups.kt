package com.ketvoice.app.data

import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only group recordings when identity is strong enough to preserve live/remix variants. */
internal object SearchGroups {
    data class Group(val tracks: List<Track>) { val primary: Track get() = tracks.first() }

    private fun normalized(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    internal fun sameRecording(a: Track, b: Track): Boolean {
        if (a.explicit != b.explicit) return false
        val aIsrc = a.isrc?.trim()?.takeIf { it.isNotEmpty() }
        val bIsrc = b.isrc?.trim()?.takeIf { it.isNotEmpty() }
        if (aIsrc != null && bIsrc != null) return aIsrc.equals(bIsrc, ignoreCase = true)
        if (normalized(a.title) != normalized(b.title) || normalized(a.artist) != normalized(b.artist)) return false
        if (a.durationMs <= 0 || b.durationMs <= 0 || abs(a.durationMs - b.durationMs) > 1_500) return false
        return a.album.isBlank() || b.album.isBlank() || normalized(a.album) == normalized(b.album)
    }

    fun group(tracks: List<Track>): List<Group> {
        val groups = mutableListOf<MutableList<Track>>()
        tracks.distinctBy { it.id }.forEach { track ->
            val group = groups.firstOrNull { sameRecording(it.first(), track) }
            if (group == null) groups += mutableListOf(track) else group += track
        }
        return groups.map { Group(it.toList()) }
    }

    suspend fun collectProgressively(searches: List<suspend () -> List<Track>>, onUpdate: (List<Track>) -> Unit) = coroutineScope {
        var current = emptyList<Track>()
        val mutex = Mutex()
        searches.map { search -> launch {
            val next = try { search() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            if (next.isNotEmpty()) {
                mutex.withLock {
                    current = (current + next).distinctBy { it.id }
                    onUpdate(current)
                }
            }
        } }.joinAll()
    }

    fun sourceId(track: Track): String? = when {
        track.addon?.startsWith("cs:") == true -> track.addon
        track.addon == "um" -> "api:um"
        track.addon == "last" -> "api:last"
        track.videoId != null || track.id.startsWith("yt:") -> "youtube"
        else -> null
    }

    fun sourceName(track: Track): String = when {
        track.addonId?.startsWith("tidal:", true) == true -> "TIDAL"
        track.addonId?.startsWith("qobuz:", true) == true -> "Qobuz"
        track.addon?.startsWith("cs:") == true -> "SRC"
        track.addon in setOf("um", "last", "upload") -> "API"
        else -> "YouTube"
    }
}
