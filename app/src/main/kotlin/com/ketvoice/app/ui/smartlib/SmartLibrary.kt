package com.ketvoice.app.ui.smartlib

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.smartlib.Duplicates
import com.ketvoice.app.smartlib.MergePlan
import com.ketvoice.app.smartlib.Mood
import com.ketvoice.app.smartlib.MoodMix
import com.ketvoice.app.smartlib.MoodMixes
import com.ketvoice.app.smartlib.SavedCopy
import com.ketvoice.app.smartlib.SmartFacts
import com.ketvoice.app.smartlib.TrackFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** The smart library's state on the screen: the ambiances, the page of one, the duplicates already reviewed. */
object SmartLibrary {
    /** The ambiance whose page is open. */
    val openMood = mutableStateOf<Mood?>(null)
    private val _mixes = MutableStateFlow<List<MoodMix>>(emptyList())
    val mixes: StateFlow<List<MoodMix>> = _mixes
    private val _building = MutableStateFlow(false)
    val building: StateFlow<Boolean> = _building
    private val _kept = MutableStateFlow<Set<String>>(emptySet())
    /** Duplicate groups the listener chose to keep as they are. */
    val kept: StateFlow<Set<String>> = _kept
    @Volatile private var seed = System.nanoTime()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        SmartFacts.init(context)
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("ketvoice-smartlib", Context.MODE_PRIVATE)
        prefs = store
        _kept.value = store.getStringSet("kept-duplicates", emptySet()).orEmpty().toSet()
    }

    /** Everything the listener has: downloads, playlist titles, files of the device, titles listened to. */
    fun pool(): List<Track> {
        val lists = PlaylistStore.lists.value.flatMap { list -> list.entries.mapNotNull { it.track } }
        return (Downloader.items.value.map { it.track } + lists + LocalMusic.tracks.value + ListeningHistory.knownTracks.value)
            .distinctBy { it.id }.filter { ArtistPrefs.allows(it) }
    }

    /** The facts of [tracks], Ketmix's latest measurements included. */
    suspend fun facts(tracks: List<Track>): Map<String, TrackFacts> = withContext(Dispatchers.Default) {
        SmartFacts.refreshSignals()
        SmartFacts.build(tracks)
    }

    /**
     * Builds the ambiances again. With [learn], the catalogue is first asked for the styles and years of
     * albums it was never asked about (a few dozen at a time). [fresh] draws another selection.
     */
    suspend fun rebuild(learn: Boolean = true, fresh: Boolean = false) {
        if (_building.value) return
        _building.value = true
        try {
            if (fresh) seed = System.nanoTime()
            val pool = pool()
            if (learn) runCatching { SmartFacts.learnAlbums(pool, 40) }
            val facts = facts(pool)
            _mixes.value = withContext(Dispatchers.Default) { MoodMixes.build(pool, facts, ListeningHistory.playCounts.value, seed) }
        } finally { _building.value = false }
    }

    internal fun setMixesForTests(mixes: List<MoodMix>) { _mixes.value = mixes }

    /* ── Duplicates ── */

    fun copies(): List<SavedCopy> = Duplicates.copies(
        Downloader.items.value.map { SavedCopy(it.track, downloadBytes = it.bytes, format = it.format) }, PlaylistStore.lists.value)

    fun keep(key: String) {
        val next = _kept.value + key
        _kept.value = next
        prefs?.edit()?.putStringSet("kept-duplicates", next)?.apply()
    }

    internal fun resetKeptForTests() { _kept.value = emptySet(); prefs = null }

    /** Applies the playlist side of [plan]: one entry per playlist, pointing at the kept title. Downloads are not touched. */
    suspend fun mergePlaylists(plan: MergePlan) {
        plan.repoint.forEach { (playlist, entry) -> PlaylistStore.resolve(playlist, entry, plan.keep) }
        plan.removeEntries.forEach { (playlist, entry) -> PlaylistStore.remove(playlist, entry) }
    }

    /** Deletes the downloads of [copies]; only called once the listener confirmed. Returns how many were deleted. */
    fun deleteDownloads(copies: List<SavedCopy>): Int =
        copies.count { copy -> Downloader.itemFor(copy.track.id)?.let(Downloader::delete) == true }
}
