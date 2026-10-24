package com.ketvoice.app.data

import android.content.Context
import android.os.StatFs
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CapsuleState(val tracks: List<Track> = emptyList(), val readyIds: Set<String> = emptySet(),
    val minutes: Int = 0, val budgetBytes: Long = 0, val busy: Boolean = false, val attempted: Int = 0,
    val message: String = "")

/** Explicit, sequential preparation. Never evicts or replaces an existing download. */
object OfflineCapsule {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CapsuleState())
    val state: StateFlow<CapsuleState> = _state
    private var context: Context? = null
    private var job: Job? = null
    private val gson = Gson()
    fun init(app: Context) {
        if (context != null) return
        context = app.applicationContext
        _state.value = runCatching {
            gson.fromJson(app.getSharedPreferences("offline-capsule", Context.MODE_PRIVATE).getString("plan", "{}"), CapsuleState::class.java)
                ?.let { it.copy(tracks = it.tracks.orEmpty().take(100), readyIds = it.readyIds.orEmpty(), busy = false, message = "") }
        }.getOrNull() ?: CapsuleState()
    }
    private fun save() {
        val safe = _state.value.copy(tracks = _state.value.tracks.map { it.copy(streamUrl = null, downloadUrl = null) }, busy = false)
        context?.getSharedPreferences("offline-capsule", Context.MODE_PRIVATE)?.edit()?.putString("plan", gson.toJson(safe))?.apply()
    }
    fun prepare(tracks: List<Track>, minutes: Int, budget: Long) {
        val app = context ?: return
        if (job?.isActive == true || tracks.isEmpty() || minutes !in 1..1440 || budget !in 1..(2L * 1024 * 1024 * 1024)) return
        val chosen = tracks.distinctBy { it.id }.take(100).map { it.copy(streamUrl = null, downloadUrl = null) }
        _state.value = CapsuleState(chosen, minutes = minutes, budgetBytes = budget, busy = true, message = "Préparation de la capsule…")
        save()
        job = scope.launch {
            try {
                var used = 0L
                for (track in chosen) {
                    ensureActive()
                    var item = Downloader.itemFor(track.id)
                    val remaining = budget - used
                    if (item == null && remaining > 16_384) {
                        val free = withContext(Dispatchers.IO) { (StatFs(app.filesDir.path).availableBytes - 16 * 1024 * 1024).coerceAtLeast(0) }
                        if (free > 16_384) Downloader.prepareCapsuleTrack(track, minOf(remaining, free))
                        item = Downloader.itemFor(track.id)
                    }
                    val bytes = item?.let(Downloader::storedBytes)
                    if (bytes != null && bytes <= remaining) {
                        used += bytes
                        _state.value = _state.value.copy(readyIds = _state.value.readyIds + track.id)
                    }
                    _state.value = _state.value.copy(attempted = _state.value.attempted + 1)
                    save()
                }
                _state.value = _state.value.copy(message = "Préparation terminée. Seuls les fichiers réellement enregistrés sont indiqués prêts.")
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(message = "Préparation arrêtée. Les téléchargements déjà enregistrés sont conservés.")
            } catch (_: Exception) {
                _state.value = _state.value.copy(message = "Préparation interrompue. Vérifiez le réseau et l'espace disponible.")
            } finally { _state.value = _state.value.copy(busy = false); save() }
        }
    }
    fun cancel() { job?.cancel() }
    fun readyTracks(): List<Track> = _state.value.tracks.filter { it.id in _state.value.readyIds }
        .mapNotNull { Downloader.itemFor(it.id)?.takeIf { item -> item.file.isFile }?.track }
}
