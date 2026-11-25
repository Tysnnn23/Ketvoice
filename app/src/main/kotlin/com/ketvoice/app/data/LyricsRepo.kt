package com.ketvoice.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object LyricsRepo {
    const val SOURCE_CUSTOM = "custom"
    data class State(
        val loading: Boolean = false,
        val source: String = "",
        val sourceKey: String = AppSettings.lyricsSource.value,
        val lines: List<MusicApi.LyricLine> = emptyList(),
        val isTimed: Boolean = true,
        val wordTimed: Boolean = false,
        val syllableTimed: Boolean = false,
        /** The title these lines belong to (6.1): the lock screen and the widget never show another title's lines. */
        val trackId: String? = null
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
    /**
     * Windows 2.0's lyrics engine (desktop only): more sources asked at once, the richest timing first ([LyricsRank]),
     * the listener's choice of source per title, and lyrics timed word by word by the app's AI swapped in. Null on the
     * phone, where every default below keeps the lookup exactly as it was.
     */
    interface Hooks {
        /** The sources the automatic lookup asks, from [default] (the built-in ones, then the custom sources). */
        fun sources(default: List<String>): List<String> = default
        /** The source the listener chose for this title (asked with the others, and shown whenever it has lyrics). */
        fun pinned(trackId: String): String? = null
        /** How rich an answer's timing is; the highest is shown. */
        fun rank(lines: List<MusicApi.LyricLine>): Int = ExtraLyricsProviders.rank(lines)
        /** Each source's answer as it arrives ([order]: its place among the sources asked). */
        fun answered(track: Track, source: String, label: String, lines: List<MusicApi.LyricLine>, order: Int) {}
        /** The state about to be shown (never the listener's own lyrics): word timing by AI may replace line timing. */
        fun shown(track: Track, state: State): State = state
    }
    @Volatile var hooks: Hooks? = null
    private fun shown(track: Track, state: State): State = hooks?.shown(track, state) ?: state

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
            _state.value = State(source = "Désactivées", sourceKey = MusicApi.SRC_OFF, trackId = track.id)
            return
        }

        // The listener's own file for this title comes first (5.9.1), whatever the online sources say.
        runCatching { CustomLyrics.lines(track.id) }.getOrNull()?.let { (entry, lines) ->
            _state.value = State(loading = false, source = "Personnalisées · ${entry.name}", sourceKey = SOURCE_CUSTOM, lines = lines,
                isTimed = lines.any { it.timeMs >= 0 }, wordTimed = lines.any { it.words.size >= 2 }, syllableTimed = lines.any { it.syllableTimed }, trackId = track.id)
            return
        }
        // 7.0: a local file's own .lrc, or the lyrics fetched ahead for it.
        if (!refresh) runCatching { LocalLyrics.lines(track.id) }.getOrNull()?.let { (label, lines) ->
            _state.value = shown(track, State(loading = false, source = label, sourceKey = source, lines = lines, isTimed = lines.any { it.timeMs >= 0 },
                wordTimed = lines.any { it.words.size >= 2 }, syllableTimed = lines.any { it.syllableTimed }, trackId = track.id))
            return
        }
        if (!refresh) {
            val local = runCatching { Downloader.localLyrics(track.id) }.getOrNull()
            if (local != null && local.second.isNotEmpty()) {
                _state.value = shown(track, State(
                    loading = false,
                    source = "Téléchargé · ${local.first}",
                    sourceKey = source,
                    lines = local.second,
                    isTimed = local.second.any { it.timeMs >= 0 },
                    wordTimed = local.second.any { it.words.size >= 2 },
                    syllableTimed = local.second.any { it.syllableTimed },
                    trackId = track.id
                ))
                return
            }
        }
        _state.value = State(loading = true, sourceKey = source, trackId = track.id)
        val order = AppSettings.lyricsOrder.value
        val hooks = hooks
        val pinned = if (source == MusicApi.SRC_AUTO) hooks?.pinned(track.id) else null
        if (source == MusicApi.SRC_AUTO && LyricsOrder.custom(order) && pinned == null) {
            job = scope.launch { inOrder(track, LyricsOrder.parse(order) + SourceExtras.lyricsSources(), mode == 1, refresh, gen, source) }
            return
        }
        job = scope.launch {
            var bestRank = -1
            var bestPriority = Int.MAX_VALUE
            // Custom sources whose manifest declares lyrics come last (an extra provider, Windows 2.0).
            val sources = if (source == MusicApi.SRC_AUTO) (MusicApi.lyricSources + SourceExtras.lyricsSources()).let { hooks?.sources(it) ?: it }
                else listOf(source)
            coroutineScope {
                sources.forEachIndexed { priority, src -> launch {
                    val result = runCatching { MusicApi.lyrics(track, mode == 1, src, refresh) }.getOrNull() ?: return@launch
                    if (result.second.isEmpty()) return@launch
                    hooks?.answered(track, src, result.first, result.second, priority)
                    // The listener's choice for this title wins whenever it has lyrics.
                    val rank = if (pinned != null && src == pinned) Int.MAX_VALUE else hooks?.rank(result.second) ?: ExtraLyricsProviders.rank(result.second)
                    synchronized(lock) {
                        if (gen != generation || currentTrackId != track.id) return@synchronized
                        val completeEnough = rank == Int.MAX_VALUE || _state.value.lines.isEmpty() || result.second.size >= _state.value.lines.size * .55
                        if (completeEnough && (rank > bestRank || (rank == bestRank && priority < bestPriority))) {
                            bestRank = rank
                            bestPriority = priority
                            _state.value = shown(track, State(false, result.first, source, result.second,
                                result.second.any { it.timeMs >= 0 }, result.second.any { it.words.size >= 2 },
                                result.second.any { it.syllableTimed }, trackId = track.id))
                        }
                    }
                } }
            }
            if (gen == generation && _state.value.loading) _state.value = State(source = MusicApi.sourceName(source), sourceKey = source, trackId = track.id)
        }
    }
    /**
     * « Ordre des sources de paroles » (8.4): the sources are asked together, but the lyrics shown are those of the
     * first source in the listener's order that has some; the sources after it are then stopped.
     */
    private suspend fun inOrder(track: Track, sources: List<String>, plain: Boolean, refresh: Boolean, gen: Int, sourceKey: String) {
        val result = LyricsOrder.firstInOrder(sources, { it.second.isNotEmpty() }) { src -> MusicApi.lyrics(track, plain, src, refresh) }?.second
        if (result != null) synchronized(lock) {
            if (gen != generation || currentTrackId != track.id) return@synchronized
            _state.value = shown(track, State(false, result.first, sourceKey, result.second, result.second.any { it.timeMs >= 0 },
                result.second.any { it.words.size >= 2 }, result.second.any { it.syllableTimed }, trackId = track.id))
        }
        if (gen == generation && _state.value.loading) _state.value = State(source = MusicApi.sourceName(sourceKey), sourceKey = sourceKey, trackId = track.id)
    }

    fun clear() {
        generation++
        job?.cancel()
        currentTrackId = null
        _state.value = State()
    }
}
