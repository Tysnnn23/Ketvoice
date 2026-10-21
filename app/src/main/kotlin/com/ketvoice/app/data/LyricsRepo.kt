package com.ketvoice.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Progressive loading: native syllables > native words > lines > plain text. */
object LyricsRepo {
    data class State(
        val loading: Boolean = false,
        val source: String = "",
        val sourceKey: String = AppSettings.lyricsSource.value,
        val lines: List<MusicApi.LyricLine> = emptyList(),
        val isTimed: Boolean = true,
        val wordTimed: Boolean = false,
        val syllableTimed: Boolean = false
    ) {
        fun indexAt(positionMs: Long): Int = if (!isTimed) -1
            else lines.indexOfLast { it.timeMs >= 0 && it.timeMs <= positionMs }
        fun activeText(positionMs: Long): String? = lines.getOrNull(indexAt(positionMs).coerceAtLeast(0))?.text
        fun syncDescription(): String = when {
            lines.isEmpty() -> if (loading) "recherche" else "indisponible"
            syllableTimed -> "syllabe par syllabe"
            wordTimed -> "mot par mot"
            isTimed -> "ligne par ligne"
            else -> "texte non synchronisé"
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var currentTrackId: String? = null
    private var generation = 0
    private var job: Job? = null
    private val lock = Any()

    fun load(track: Track, force: Boolean = false, refresh: Boolean = false) {
        if (!force && currentTrackId == track.id && (_state.value.loading || _state.value.lines.isNotEmpty())) return
        job?.cancel()
        currentTrackId = track.id
        val gen = ++generation
        val source = AppSettings.lyricsSource.value
        val mode = AppSettings.lyricsMode.value
        if (mode == 2 || source == MusicApi.SRC_OFF) {
            _state.value = State(source = "Désactivées", sourceKey = MusicApi.SRC_OFF)
            return
        }
        // Un titre téléchargé emporte ses meilleures paroles : elles passent
        // avant toute requête réseau, même en mode hors ligne.
        if (!refresh) {
            val local = runCatching { Downloader.localLyrics(track.id) }.getOrNull()
            if (local != null && local.second.isNotEmpty()) {
                _state.value = State(
                    loading = false,
                    source = "Téléchargé · ${local.first}",
                    sourceKey = source,
                    lines = local.second,
                    isTimed = local.second.any { it.timeMs >= 0 },
                    wordTimed = local.second.any { it.words.size >= 2 },
                    syllableTimed = local.second.any { it.syllableTimed }
                )
                return
            }
        }
        _state.value = State(loading = true, sourceKey = source)
        job = scope.launch {
            var bestRank = -1
            var bestPriority = Int.MAX_VALUE
            val sources = if (source == MusicApi.SRC_AUTO) MusicApi.lyricSources else listOf(source)
            coroutineScope {
                sources.forEachIndexed { priority, src -> launch {
                    val result = runCatching { MusicApi.lyrics(track, mode == 1, src, refresh) }.getOrNull() ?: return@launch
                    if (result.second.isEmpty()) return@launch
                    val rank = ExtraLyricsProviders.rank(result.second)
                    synchronized(lock) {
                        if (gen != generation || currentTrackId != track.id) return@synchronized
                        val completeEnough = _state.value.lines.isEmpty() || result.second.size >= _state.value.lines.size * .55
                        if (completeEnough && (rank > bestRank || (rank == bestRank && priority < bestPriority))) {
                            bestRank = rank
                            bestPriority = priority
                            _state.value = State(false, result.first, source, result.second,
                                result.second.any { it.timeMs >= 0 }, result.second.any { it.words.size >= 2 },
                                result.second.any { it.syllableTimed })
                        }
                    }
                } }
            }
            if (gen == generation && _state.value.loading) _state.value = State(source = MusicApi.sourceName(source), sourceKey = source)
        }
    }
    fun clear() {
        generation++
        job?.cancel()
        currentTrackId = null
        _state.value = State()
    }
}
