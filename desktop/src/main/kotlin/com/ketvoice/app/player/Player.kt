package com.ketvoice.app.player

import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.YouTubeApi
import com.ketvoice.desktop.audio.AudioEngine
import com.ketvoice.desktop.audio.AudioEvent
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.player.DesktopStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** The desktop queue and transport as the UI and the shared Ketmix code read it (the Android `PlayerUiState` subset). */
data class PlayerUiState(
    val queue: List<Track> = emptyList(),
    val queueEntryIds: List<String> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    /** Polled from the engine about 4 times a second while playing. */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatOne: Boolean = false,
    val repeatAll: Boolean = false,
    val playWhenReady: Boolean = false,
    /** « Lecture automatique »: titles of the same style follow when the queue runs out. */
    val autoplay: Boolean = true,
) {
    val current: Track? get() = queue.getOrNull(index)
}

/**
 * Ketvoice for Windows' player facade: the queue and its order with the same rules as the Android
 * `Player` (entry ids per queue slot, « Lire ensuite » pins, visible shuffle, history-first « précédent »,
 * autoplay), driving an injected [AudioEngine] with gapless preloading of the next title.
 */
object Player {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume
    private val _loading = MutableStateFlow(false)
    /** A title is being resolved, or the engine is buffering. */
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** French messages for the UI (it localizes them); a failed title is skipped already. */
    val error: SharedFlow<String> = _error
    private val _format = MutableStateFlow<AudioEvent.Format?>(null)
    /** What the decoder reports about the title heard (the Lossless badge); null until it says, reset on each load. */
    val format: StateFlow<AudioEvent.Format?> = _format

    internal const val UNAVAILABLE = "Flux indisponible · réessayez"
    internal const val NOTHING_PLAYABLE = "Aucun flux disponible"
    private const val TICK_MS = 250L
    private const val RESTART_THRESHOLD_MS = 3_000L

    internal class NextEntry(val index: Int, val track: Track, val entryId: String)

    private val pinnedEntries = HashSet<String>()
    private var engine: AudioEngine? = null
    private var resolve: suspend (Track) -> AudioSource? = DesktopStreams::source
    /** Titles of the same style as a seed (its YouTube Music radio, else its artist's catalogue radio). */
    internal var autoplayCandidates: suspend (Track) -> List<Track> = ::radioOf
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eventsJob: Job? = null
    private var loadJob: Job? = null
    private var preloadJob: Job? = null
    private var autoplayJob: Job? = null
    private var ticker: Job? = null

    /** Bumped for every title that starts; late answers for an older one are dropped. */
    private var generation = 0L
    private var recordedGeneration = -1L
    /** The entry the engine has loaded, and the one it holds ready to follow it. */
    private var loadedEntry: String? = null
    private var preloadedEntry: String? = null
    private var currentSource: AudioSource? = null
    private var preloadedSource: AudioSource? = null
    private val history = PlayHistory()
    private var linearOrder: List<String> = emptyList()
    private val autoEntries = HashSet<String>()
    private val autoplayHeard = HashSet<String>()
    private var failures = 0

    /**
     * Plugs the audio engine in. [resolve] turns a title into what the engine reads (downloads first,
     * then the app's sources); [scope] runs resolution, events and the position ticks.
     */
    @Synchronized
    fun attach(engine: AudioEngine, resolve: suspend (Track) -> AudioSource? = DesktopStreams::source,
               scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        listOf(eventsJob, loadJob, preloadJob, autoplayJob, ticker).forEach { it?.cancel() }
        this.engine = engine
        this.resolve = resolve
        this.scope = scope
        loadedEntry = null; preloadedEntry = null; currentSource = null
        engine.volume = _volume.value
        eventsJob = scope.launch { engine.events.collect(::onEvent) }
    }

    /** Replaces the queue with [tracks] and plays from [index] (shuffled around it when shuffle is on). */
    @Synchronized
    fun playQueue(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        setQueue(tracks, index)
        val s = _state.value
        if (s.shuffle) {
            val shuffled = QueueOrder.shuffle(s.queue, s.queueEntryIds, s.index, recent = recentIds(), spread = true)
            linearOrder = shuffled.linearOrder
            _state.value = s.copy(queue = shuffled.queue, queueEntryIds = shuffled.entryIds, index = shuffled.index)
        }
        startAt(_state.value.index)
    }

    /** Replaces the queue and stands on [index], without playing. */
    @Synchronized
    fun setQueue(tracks: List<Track>, index: Int = 0) {
        synchronized(pinnedEntries) { pinnedEntries.clear() }
        history.clear(); linearOrder = emptyList(); autoEntries.clear(); autoplayHeard.clear()
        autoplayJob?.cancel()
        _state.value = _state.value.copy(
            queue = tracks,
            queueEntryIds = tracks.map { UUID.randomUUID().toString() },
            index = if (tracks.isEmpty()) -1 else index.coerceIn(0, tracks.lastIndex),
            positionMs = 0L,
            durationMs = tracks.getOrNull(index.coerceAtLeast(0))?.durationMs ?: 0L,
            isPlaying = false,
            playWhenReady = false,
        )
        stopEngine()
    }

    /** Phone control: the queue another device played, from where it stood there. */
    @Synchronized
    fun resumeFrom(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean) {
        if (tracks.isEmpty()) return
        setQueue(tracks, index)
        _state.value = _state.value.copy(positionMs = positionMs.coerceAtLeast(0L))
        if (play) play()
    }

    /** Plays the queue's title at [index]; the queue stays as it is. */
    @Synchronized
    fun jumpTo(index: Int) { if (index in _state.value.queue.indices) startAt(index) }

    @Synchronized fun toggle() { if (_state.value.isPlaying) pause() else play() }

    @Synchronized
    fun play() {
        val s = _state.value
        val entry = s.queueEntryIds.getOrNull(s.index) ?: return
        val e = engine ?: return
        if (loadedEntry != entry) { startAt(s.index, startMs = s.positionMs); return }
        e.play()
        _state.value = s.copy(isPlaying = true, playWhenReady = true)
        noteStarted()
        startTicker()
    }

    @Synchronized
    fun pause() {
        engine?.pause()
        stopTicker()
        _state.value = _state.value.copy(isPlaying = false, playWhenReady = false)
        runCatching { ListeningHistory.flushListenedMs() }
    }

    @Synchronized
    fun next() {
        // Ketmix: « Suivant » may mix into the next title instead of cutting to it (ketmixOnSkip).
        if (runCatching { skipHook?.invoke() }.getOrNull() == true) return
        val s = _state.value
        when (val step = QueueSteps.next(s.queue.size, s.index, s.repeatOne, s.repeatAll, s.autoplay, ended = false)) {
            is QueueStep.Jump -> startAt(step.index)
            QueueStep.Extend -> extend()
            QueueStep.Replay -> startAt(s.index)
            QueueStep.Stop -> Unit
        }
    }

    /** Back to the start after the first seconds; otherwise the title the listener came from. */
    @Synchronized
    fun previous() {
        val s = _state.value
        if (s.index < 0) return
        val position = engine?.takeIf { loadedEntry != null }?.positionMs() ?: s.positionMs
        if (position > RESTART_THRESHOLD_MS) { seekTo(0); return }
        val target = history.popInto(s.queueEntryIds, s.index) ?: QueueSteps.previous(s.queue.size, s.index, s.repeatAll)
        if (target == null) seekTo(0) else startAt(target, fromHistory = true)
    }

    @Synchronized
    fun seekTo(ms: Long) {
        val position = ms.coerceAtLeast(0L)
        if (loadedEntry != null) engine?.seekTo(position)
        _state.value = _state.value.copy(positionMs = position)
    }

    @Synchronized
    fun setVolume(v: Float) {
        val value = v.coerceIn(0f, 1f)
        _volume.value = value
        engine?.volume = value
    }

    @Synchronized
    fun setShuffle(on: Boolean) {
        val s = _state.value
        if (on == s.shuffle) return
        val result = if (on) QueueOrder.shuffle(s.queue, s.queueEntryIds, s.index, recent = recentIds(), spread = true)
            else QueueOrder.restore(s.queue, s.queueEntryIds, s.index, linearOrder)
        linearOrder = result.linearOrder
        _state.value = s.copy(queue = result.queue, queueEntryIds = result.entryIds, index = result.index, shuffle = on)
        planPreload()
    }

    @Synchronized
    fun setRepeat(one: Boolean, all: Boolean) {
        _state.value = _state.value.copy(repeatOne = one, repeatAll = all && !one)
        planPreload()
    }

    @Synchronized
    fun setAutoplay(on: Boolean) {
        _state.value = _state.value.copy(autoplay = on)
        if (on) topUpAutoplay() else autoplayJob?.cancel()
    }

    /** Puts [track] right after the current title; Ketmix's smart queue never moves a title in front of it. */
    @Synchronized
    fun playNext(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) { setQueue(listOf(track)); return }
        val at = (s.index + 1).coerceIn(0, s.queue.size)
        val entry = UUID.randomUUID().toString()
        synchronized(pinnedEntries) { pinnedEntries += entry }
        insert(at, track, entry, after = s.queueEntryIds.getOrNull(s.index))
    }

    /** Adds [track] at the end of the listener's queue (before the titles autoplay added). */
    @Synchronized
    fun enqueue(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) { setQueue(listOf(track)); return }
        val firstAuto = s.queueEntryIds.indexOfFirst { it in autoEntries }.takeIf { it > s.index } ?: s.queue.size
        insert(firstAuto, track, UUID.randomUUID().toString(), after = s.queueEntryIds.getOrNull(firstAuto - 1))
    }

    private fun insert(at: Int, track: Track, entry: String, after: String?) {
        val s = _state.value
        val ids = s.queueEntryIds.toMutableList().apply { add(at.coerceAtMost(size), entry) }
        linearOrder = QueueOrder.syncLinear(linearOrder, ids, after)
        _state.value = s.copy(queue = s.queue.toMutableList().apply { add(at, track) }, queueEntryIds = ids,
            index = if (at <= s.index) s.index + 1 else s.index)
        planPreload()
    }

    /** Removes the entry at [index]; removing the current title plays the one that takes its place. */
    @Synchronized
    fun removeAt(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        val q = s.queue.toMutableList().apply { removeAt(index) }
        val ids = s.queueEntryIds.toMutableList().apply { if (index in indices) removeAt(index) }
        autoEntries.retainAll(ids.toSet())
        linearOrder = QueueOrder.syncLinear(linearOrder, ids)
        if (q.isEmpty()) {
            _state.value = s.copy(queue = q, queueEntryIds = ids, index = -1, isPlaying = false, playWhenReady = false, positionMs = 0L)
            stopEngine(); return
        }
        if (index != s.index) {
            _state.value = s.copy(queue = q, queueEntryIds = ids, index = if (index < s.index) s.index - 1 else s.index)
            planPreload(); return
        }
        _state.value = s.copy(queue = q, queueEntryIds = ids, index = index.coerceAtMost(q.lastIndex), positionMs = 0L)
        if (s.isPlaying) startAt(_state.value.index, fromHistory = true) else { loadedEntry = null; planPreload() }
    }

    /** The title after the current one as Ketmix sees it: on repeat-one, the title itself when loops are on. */
    internal fun automixNext(): NextEntry? {
        val s = _state.value
        if (s.repeatOne) {
            if (!AppSettings.ketmixLoops.value) return null
            val current = s.current ?: return null
            return NextEntry(s.index, current, s.queueEntryIds.getOrNull(s.index) ?: return null)
        }
        val step = QueueSteps.next(s.queue.size, s.index, s.repeatOne, s.repeatAll, autoplay = false, ended = true)
        val target = (step as? QueueStep.Jump)?.index ?: return null
        return NextEntry(target, s.queue[target], s.queueEntryIds.getOrNull(target) ?: return null)
    }

    internal fun automixUpcoming(count: Int): List<NextEntry> {
        val s = _state.value
        if (s.repeatOne) return listOfNotNull(automixNext())
        val out = ArrayList<NextEntry>(count)
        var index = s.index
        while (out.size < count) {
            val step = QueueSteps.next(s.queue.size, index, false, s.repeatAll, autoplay = false, ended = true)
            val target = (step as? QueueStep.Jump)?.index ?: break
            if (target == s.index || out.any { it.index == target }) break
            out += NextEntry(target, s.queue[target], s.queueEntryIds.getOrNull(target) ?: break)
            index = target
        }
        return out
    }

    /** The listener asked for this entry to play next (see [playNext]). */
    /** Ketmix on the desktop: asked first when the listener skips; true when it mixes into the next title instead. */
    @Volatile internal var skipHook: (() -> Boolean)? = null

    /** What the engine plays now. */
    @Synchronized internal fun loadedSource(): AudioSource? = currentSource.takeIf { loadedEntry != null }

    /** The source the engine holds ready after the current title when it is [entryId] (what a Ketmix transition plays into). */
    @Synchronized internal fun preloadedSource(entryId: String): AudioSource? = preloadedSource.takeIf { preloadedEntry == entryId }

    internal fun pinnedNext(entryId: String): Boolean = synchronized(pinnedEntries) { entryId in pinnedEntries }

    /** A publication was edited: the queue shows its new title, cover and audio. */
    fun refreshPublication(track: Track) {
        if (com.ketvoice.app.data.ImportedSong.id(track) == null) return
        _state.value = _state.value.let { s -> s.copy(queue = s.queue.map { if (it.id == track.id) track else it }) }
    }

    @Synchronized
    fun moveQueue(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices || from == to) return
        val q = s.queue.toMutableList()
        q.add(to, q.removeAt(from))
        val keys = s.queueEntryIds.toMutableList()
        if (from in keys.indices && to in keys.indices) keys.add(to, keys.removeAt(from))
        val newIndex = when {
            from == s.index -> to
            from < s.index && to >= s.index -> s.index - 1
            from > s.index && to <= s.index -> s.index + 1
            else -> s.index
        }
        _state.value = s.copy(queue = q, queueEntryIds = keys, index = newIndex)
        planPreload()
    }

    // ------------------------------------------------------------ engine

    /** Resolves and loads the title at [index]. [fromHistory]: going back, the title left is not remembered. */
    private fun startAt(index: Int, startMs: Long = 0L, play: Boolean = true, fromHistory: Boolean = false) {
        val s = _state.value
        val track = s.queue.getOrNull(index) ?: return
        val entry = s.queueEntryIds.getOrNull(index) ?: return
        val e = engine ?: return
        if (!fromHistory && index != s.index) s.queueEntryIds.getOrNull(s.index)?.let(history::push)
        val gen = ++generation
        stopTicker()
        loadJob?.cancel()
        _state.value = s.copy(index = index, positionMs = startMs, durationMs = track.durationMs, isPlaying = false, playWhenReady = play)
        _loading.value = true
        _format.value = null
        loadJob = scope.launch {
            val source = try { resolve(track) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            synchronized(this@Player) {
                if (gen != generation) return@launch
                if (source == null) { _loading.value = false; failed(); return@launch }
                if (preloadedEntry != null) { preloadedEntry = null; e.preloadNext(null) }
                e.load(source, startMs, play)
                loadedEntry = entry
                currentSource = source
                _loading.value = false
                _state.value = _state.value.copy(isPlaying = play, playWhenReady = play)
                if (play) { noteStarted(); startTicker() }
                planPreload()
                topUpAutoplay()
            }
        }
    }

    private fun onEvent(event: AudioEvent) = synchronized(this) {
        when (event) {
            // The engine reports a gapless takeover as Advanced and a real end (nothing preloaded followed) as Ended.
            is AudioEvent.Advanced -> { _format.value = null; onEnded() }
            is AudioEvent.Format -> _format.value = event
            AudioEvent.Ended -> { preloadedEntry = null; preloadedSource = null; onEnded() }
            is AudioEvent.Error -> if (loadedEntry != null) { _loading.value = false; failed() }
            is AudioEvent.Buffering -> { val (buffering) = event; _loading.value = buffering }
            else -> Unit
        }
    }

    private fun onEnded() {
        val s = _state.value
        val current = s.queueEntryIds.getOrNull(s.index) ?: return
        failures = 0
        val step = QueueSteps.next(s.queue.size, s.index, s.repeatOne, s.repeatAll, s.autoplay, ended = true)
        val target = when (step) { is QueueStep.Jump -> step.index; QueueStep.Replay -> s.index; else -> null }
        val entry = target?.let { s.queueEntryIds.getOrNull(it) }
        if (entry != null && entry == preloadedEntry) {
            // Gapless: the engine already plays the deck it held ready.
            if (target != s.index) history.push(current)
            generation++
            preloadedEntry = null
            loadedEntry = entry
            currentSource = preloadedSource
            _state.value = s.copy(index = target, positionMs = 0L, durationMs = s.queue[target].durationMs,
                isPlaying = true, playWhenReady = true)
            noteStarted()
            planPreload()
            topUpAutoplay()
            return
        }
        when (step) {
            is QueueStep.Jump -> startAt(step.index)
            QueueStep.Replay -> startAt(s.index, fromHistory = true)
            QueueStep.Extend -> extend()
            QueueStep.Stop -> {
                stopTicker()
                loadedEntry = null
                _state.value = s.copy(isPlaying = false, playWhenReady = false, positionMs = 0L)
            }
        }
    }

    /** A title could not be played: say so and go on with the next one, until none of the queue plays. */
    private fun failed() {
        val s = _state.value
        failures++
        stopTicker()
        loadedEntry = null
        _state.value = s.copy(isPlaying = false)
        if (failures >= s.queue.size.coerceAtLeast(1)) {
            failures = 0
            _state.value = _state.value.copy(playWhenReady = false)
            _error.tryEmit(NOTHING_PLAYABLE)
            return
        }
        _error.tryEmit(UNAVAILABLE)
        val step = QueueSteps.next(s.queue.size, s.index, repeatOne = false, s.repeatAll, autoplay = false, ended = true)
        if (step is QueueStep.Jump) startAt(step.index) else _state.value = _state.value.copy(playWhenReady = false)
    }

    /** Gives the engine the title that follows (gapless), or nothing when the queue stops here. */
    private fun planPreload() {
        val e = engine ?: return
        val s = _state.value
        if (loadedEntry == null || loadedEntry != s.queueEntryIds.getOrNull(s.index)) return
        val step = QueueSteps.next(s.queue.size, s.index, s.repeatOne, s.repeatAll, autoplay = false, ended = true)
        val target = when (step) { is QueueStep.Jump -> step.index; QueueStep.Replay -> s.index; else -> null }
        val entry = target?.let { s.queueEntryIds.getOrNull(it) }
        if (entry != null && entry == preloadedEntry) return
        preloadJob?.cancel()
        if (entry == null) {
            if (preloadedEntry != null) { preloadedEntry = null; e.preloadNext(null) }
            return
        }
        val track = s.queue[target]
        val replay = target == s.index
        val gen = generation
        preloadJob = scope.launch {
            val source = if (replay) currentSource else
                try { resolve(track) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            synchronized(this@Player) {
                val now = _state.value
                if (gen != generation || now.queueEntryIds.getOrNull(target) != entry) return@launch
                e.preloadNext(source)
                preloadedEntry = if (source != null) entry else null
                preloadedSource = source
            }
        }
    }

    private fun stopEngine() {
        loadJob?.cancel(); preloadJob?.cancel(); stopTicker()
        generation++
        if (loadedEntry != null) engine?.pause()
        if (preloadedEntry != null) engine?.preloadNext(null)
        loadedEntry = null; preloadedEntry = null; currentSource = null
        _loading.value = false
        _format.value = null
    }

    /** One play in the history (plays, days, recents) per title that really starts. */
    private fun noteStarted() {
        if (recordedGeneration == generation) return
        recordedGeneration = generation
        _state.value.current?.let { runCatching { ListeningHistory.record(it) } }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        val e = engine ?: return
        ticker = scope.launch {
            var last = e.positionMs()
            while (isActive) {
                delay(TICK_MS)
                val position = e.positionMs().coerceAtLeast(0L)
                val duration = e.durationMs()
                // Real listening time: the position delta between two ticks; a seek or a new title is a jump, not listening.
                val delta = position - last
                last = position
                if (delta in 1..2_500L) runCatching { ListeningHistory.addListenedMs(delta) }
                if (position > 2_000L) failures = 0
                synchronized(this@Player) {
                    val s = _state.value
                    val d = if (duration > 0) duration else s.durationMs
                    if (s.isPlaying && (s.positionMs != position || s.durationMs != d)) _state.value = s.copy(positionMs = position, durationMs = d)
                }
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    // ------------------------------------------------------------ autoplay

    private fun recentIds(): Set<String> = runCatching { ListeningHistory.tracks.value.take(50).mapTo(HashSet()) { it.id } }.getOrDefault(emptySet())

    /** Adds titles of the same style a few titles before the queue runs out (shared [AutoplayRules]). */
    private fun topUpAutoplay(): Job? {
        val s = _state.value
        if (!s.autoplay || s.repeatAll || s.repeatOne || s.current == null) return null
        if (s.queue.size - 1 - s.index > AutoplayRules.AHEAD) return null
        if (autoplayJob?.isActive == true) return autoplayJob
        val seed = s.queue.withIndex().lastOrNull { s.queueEntryIds.getOrNull(it.index) !in autoEntries }?.value ?: s.queue.last()
        val job = scope.launch {
            val pool = try { autoplayCandidates(seed) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }
            synchronized(this@Player) {
                val now = _state.value
                if (!now.autoplay) return@launch
                val recent = NoRepeatWindow.recent(ListeningHistory.heardAt.value, System.currentTimeMillis())
                val batch = NoRepeatWindow.pick(pool, recent) { candidates ->
                    AutoplayRules.pick(candidates, seed, now.queue, autoplayHeard, { ArtistPrefs.allows(it) }, { null })
                }
                if (batch.isEmpty()) return@launch
                batch.forEach { autoplayHeard += AutoplayRules.heardKeys(it) }
                val keys = batch.map { UUID.randomUUID().toString() }
                autoEntries += keys
                val ids = now.queueEntryIds + keys
                linearOrder = QueueOrder.syncLinear(linearOrder, ids)
                _state.value = now.copy(queue = now.queue + batch, queueEntryIds = ids)
                planPreload()
            }
        }
        autoplayJob = job
        return job
    }

    /** « Suivant » at the very end with autoplay on: wait for the batch and play its first title. */
    private fun extend() {
        val end = _state.value.queue.size
        val job = topUpAutoplay() ?: return
        scope.launch {
            job.join()
            synchronized(this@Player) {
                val s = _state.value
                if (s.queue.size > end && s.index == end - 1) startAt(end)
                else if (s.index == end - 1) { stopTicker(); loadedEntry = null; _state.value = s.copy(isPlaying = false, playWhenReady = false) }
            }
        }
    }

    private suspend fun radioOf(seed: Track): List<Track> {
        val video = seed.videoId?.takeIf { it.isNotBlank() } ?: seed.id.removePrefix("yt:").takeIf { seed.id.startsWith("yt:") }
        val radio = video?.let { v ->
            YouTubeApi.request("next", JSONObject().put("videoId", v).put("playlistId", "RDAMVM$v").put("isAudioOnly", true),
                authenticated = false)?.let { runCatching { AutoplayRules.parseRadio(it, v) }.getOrNull() }
        }.orEmpty()
        if (radio.isNotEmpty()) return radio
        return runCatching { CatalogRepo.artistRadio(MediaOverrides.mainArtist(seed.artist), seed.artistId?.takeIf { seed.addon == "um" }) }
            .getOrDefault(emptyList())
    }

    /** Tests: back to an empty, detached player. */
    @Synchronized
    internal fun resetForTest() {
        listOf(eventsJob, loadJob, preloadJob, autoplayJob, ticker).forEach { it?.cancel() }
        engine = null; loadedEntry = null; preloadedEntry = null; currentSource = null
        history.clear(); linearOrder = emptyList(); autoEntries.clear(); autoplayHeard.clear(); failures = 0
        synchronized(pinnedEntries) { pinnedEntries.clear() }
        autoplayCandidates = ::radioOf
        _state.value = PlayerUiState(); _loading.value = false; _volume.value = 1f; _format.value = null
    }
}
