@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.automix

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.SleepTimer
import com.ketvoice.app.player.playbackMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the playback service lends to Automix: its decks. Everything is called on the main thread. */
internal interface AutomixHost {
    /** The deck the listener hears and controls. */
    val main: ExoPlayer
    /** Exact (bit-perfect) output: nothing may be mixed. */
    val exact: Boolean
    fun control(deck: ExoPlayer): DeckControl?
    /** A second, silent deck. The caller gives it back with [release]. */
    fun standby(): ExoPlayer
    /** [next] becomes the deck the listener hears and controls; the previous one keeps playing and is returned. */
    fun promote(next: ExoPlayer): ExoPlayer
    fun release(deck: ExoPlayer)
}

/**
 * Mixes the end of a title into the start of the next one. Towards the end of what plays,
 * a second deck reads (silently, eight times faster than real time) the last seconds of
 * the title and the first seconds of the next one; [TransitionPlanner] decides what to
 * do with them; the second deck then starts in time with the first, both follow their
 * [DeckAutomation], and half-way the second deck becomes the one the listener controls.
 *
 * Anything unexpected (a seek, another title chosen, an error, too little time) gives up
 * the transition and leaves the title to end as it always did.
 */
internal object Automix {
    enum class Phase { Idle, Preparing, Ready, Mixing }

    /** What the interface shows. [startedAt] is on the SystemClock.elapsedRealtime clock. */
    data class Ui(val phase: Phase = Phase.Idle, val from: Track? = null, val to: Track? = null,
                  val kind: MixKind = MixKind.None, val startedAt: Long = 0L, val durationMs: Long = 0L,
                  val promoted: Boolean = false, val bpm: Float = 0f)

    /** The next title is resolved and read this long before the end of the current one, so that it is loaded well before any mix starts. */
    private const val PREP_LEAD_MS = 150_000L
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_MS = 6_000L
    private const val OUTRO_WINDOW_MS = 45_000L
    private const val INTRO_WINDOW_MS = 40_000L
    private const val ANALYSIS_SPEED = 8f
    private const val SETTLE_MS = 8_000L

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /**
     * What Automix is doing or why it is not mixing, in the words shown to the listener (French
     * literals, translated where they are shown). Empty while Automix is off.
     */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private fun say(text: String) {
        if (_status.value == text) return
        _status.value = text
        android.util.Log.i("KetvoiceAutomix", text)
    }
    private val retryAt = HashMap<String, Long>()
    private val attempts = HashMap<String, Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: AutomixHost? = null
    private var watcher: Job? = null
    private var job: Job? = null
    private var session: Session? = null
    /** Pairs of queue entries already mixed or given up for this playback. */
    private val settled = HashSet<String>()

    /** Everything a transition owns, so that giving it up is one call whatever the moment. */
    private class Session(val host: AutomixHost, val main: ExoPlayer, val next: Player.NextEntry) {
        var deck: ExoPlayer? = null
        var plan: MixPlan? = null
        var precise = false
        var started = false
        var promoted = false
        var baseVolume = 1f
        var source: Player.DeckSource? = null
        var closed = false

        /** Leaves a single deck playing, untouched by any level, filter or speed of the transition. */
        fun close() {
            if (closed) return
            closed = true
            val incoming = deck
            if (promoted) {
                // The incoming title is the one being heard: it keeps playing, at its own tempo and level.
                host.release(main)
                if (incoming != null) {
                    host.control(incoming)?.reset()
                    runCatching { incoming.playbackParameters = PlaybackParameters.DEFAULT; incoming.volume = baseVolume }
                }
            } else {
                host.control(main)?.reset()
                if (started) runCatching { main.volume = baseVolume }
                if (incoming != null) { host.control(incoming)?.reset(); host.release(incoming) }
            }
        }
    }

    fun attach(target: AutomixHost) {
        host = target
        if (watcher?.isActive != true) watcher = scope.launch { watch() }
    }

    fun detach(target: AutomixHost) {
        if (host !== target) return
        cancel()
        host = null
    }

    /** True from the moment the incoming title plays until it is the only one. */
    val mixing: Boolean get() = session?.let { it.started && !it.closed } == true

    /** The listener chose something else, or moved in the title: the transition is dropped at once. */
    fun cancel() {
        job?.cancel(); job = null
        session?.close(); session = null
        if (_ui.value.phase != Phase.Idle) _ui.value = Ui()
    }

    /**
     * The outgoing title reached its end while the incoming one already plays: the hand-over
     * happens now. False when Automix has nothing playing, so the usual "next title" runs.
     */
    fun takeOverEnd(): Boolean {
        val current = session ?: return false
        // Once handed over, an end is the incoming title's own end: the usual "next title" applies.
        if (!current.started || current.closed || current.promoted) return false
        val deck = current.deck ?: return false
        if (!deck.isPlaying) return false
        promote(current)
        return true
    }

    private fun promote(current: Session) {
        val deck = current.deck ?: return
        val source = current.source ?: return
        current.host.promote(deck)
        current.promoted = true
        Player.adoptAutomixed(current.next, source)
        _ui.value = _ui.value.copy(promoted = true)
    }

    private suspend fun watch() {
        while (true) {
            delay(250)
            val target = host ?: continue
            if (!AppSettings.automix.value) { if (job != null || session != null) cancel(); say(""); continue }
            if (job?.isActive == true) continue
            val state = Player.state.value
            val current = state.current
            if (current == null || state.failed) { say("Automix actif"); continue }
            val next = Player.automixNext()
            if (next == null) { say("Pas de mix · dernier titre de la file"); continue }
            val key = "${state.queueEntryIds.getOrNull(state.index)}>${next.entryId}#${Player.playbackEpoch}"
            // Why nothing will happen is said at once, long before the end of the title.
            blocker(state, current, next, target)?.let { say("Pas de mix · $it"); continue }
            if (key in settled) continue
            if (!state.isPlaying) continue
            val main = target.main
            val duration = main.duration
            val position = main.currentPosition
            if (duration <= 0 || position < 8_000 || duration - position > PREP_LEAD_MS) {
                say(if (duration > 0) "Automix actif · le mix du titre suivant se prépare avant la fin de celui-ci" else "Automix actif")
                continue
            }
            if (retryAt[key]?.let { SystemClock.elapsedRealtime() < it } == true) continue
            if (settled.size > 48) { settled.clear(); attempts.clear(); retryAt.clear() }
            if (duration - position < 3_500) { settled += key; continue }
            val count = (attempts[key] ?: 0) + 1
            attempts[key] = count
            val fresh = Session(target, main, next)
            session = fresh
            job = scope.launch {
                try {
                    if (transition(fresh, current, duration, key)) settled += key
                    else if (retryAt[key] != null && count >= MAX_ATTEMPTS) { settled += key; say("Pas de mix · titre suivant indisponible") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("KetvoiceAutomix", "transition failed: ${failure.javaClass.simpleName}")
                    settled += key
                    say("Pas de mix · le titre suivant n'a pas pu être préparé")
                } finally {
                    fresh.close()
                    if (session === fresh) { session = null; if (_ui.value.phase != Phase.Idle) _ui.value = Ui() }
                }
            }
        }
    }

    private fun sameAlbum(a: Track, b: Track): Boolean = a.album.isNotBlank() &&
        MediaOverrides.normalize(a.album) == MediaOverrides.normalize(b.album) &&
        MediaOverrides.normalize(MediaOverrides.mainArtist(a.artist)) == MediaOverrides.normalize(MediaOverrides.mainArtist(b.artist))

    private fun kindLabel(kind: MixKind): String = when (kind) {
        MixKind.Beatmatch -> "calé sur le tempo"
        MixKind.Crossfade -> "fondu enchaîné"
        MixKind.Trim -> "enchaînement sans silence"
        MixKind.None -> "sans mix"
    }

    /** The transition is given up for this pair: say why, and do not try again. */
    private fun skip(reason: String): Boolean {
        say("Pas de mix · " + when (reason) {
            "album in order" -> "album joué dans l'ordre"
            "gapless album" -> "album sans blanc entre les titres"
            "short title" -> "titre suivant trop court"
            "repeat one" -> "titre répété"
            "sleep timer" -> "minuterie active"
            "exact audio" -> "sortie bit-perfect"
            "late" -> "trop tard pour préparer le mix"
            "deck" -> "le titre suivant n'a pas pu être lu"
            else -> "les deux titres ne s'enchaînent pas"
        })
        return true
    }

    /** What rules a mix out whatever the music is, in the words of the status line, or null. */
    private fun blocker(state: com.ketvoice.app.player.PlayerUiState, current: Track, next: Player.NextEntry, h: AutomixHost): String? = when {
        h.exact || AppSettings.bitPerfect.value -> "sortie bit-perfect"
        state.repeatOne -> "titre répété"
        SleepTimer.endOfTrack.value || SleepTimer.remainingSeconds.value in 1..180 -> "minuterie active"
        !AppSettings.automixAlbums.value && !state.shuffle && sameAlbum(current, next.track) -> "album joué dans l'ordre"
        next.track.durationMs in 1 until 30_000L -> "titre suivant trop court"
        else -> null
    }

    /** True when this pair is done with (mixed, or not to be mixed); false to try again while time remains. */
    private suspend fun transition(s: Session, current: Track, duration: Long, key: String): Boolean {
        val h = s.host
        val main = s.main
        val next = s.next
        val epoch = Player.playbackEpoch
        fun valid() = host === h && h.main === main && Player.playbackEpoch == epoch && AppSettings.automix.value &&
            Player.automixNext()?.entryId == next.entryId && main.playerError == null && !s.closed
        val state = Player.state.value
        val context = MixContext(
            sameAlbumInOrder = !state.shuffle && sameAlbum(current, next.track),
            repeatOne = state.repeatOne,
            stopAfterCurrent = SleepTimer.endOfTrack.value || SleepTimer.remainingSeconds.value in 1..180,
            exactAudio = h.exact || AppSettings.bitPerfect.value,
            skipSilence = AppSettings.skipSilence.value,
            albumsMixed = AppSettings.automixAlbums.value)
        val settings = MixSettings(AppSettings.automixIntensity.value)
        val nextDuration = next.track.durationMs.takeIf { it > 0 } ?: 600_000L
        // The context alone may already say that these two titles simply follow each other.
        TransitionPlanner.plan(MixFacts(duration), MixFacts(nextDuration), context, settings).let { first ->
            if (!first.mixes && first.reason != "gapless album") return skip(first.reason)
        }

        _ui.value = Ui(Phase.Preparing, current, next.track)
        say("Préparation du titre suivant…")
        Player.warmForAutomix(next.track)
        // The next title is resolved now (or taken from what was prefetched): it is loaded long before any mix starts.
        val source = Player.resolveDeckSource(next.track)
        if (!valid()) return false
        if (source == null) {
            retryAt[key] = SystemClock.elapsedRealtime() + RETRY_MS
            say("Titre suivant indisponible · nouvel essai dans quelques secondes")
            return false
        }
        s.source = source
        val item = main.currentMediaItem ?: return skip("audio")
        val nextItem = playbackMediaItem(next.track, source.url, source.cacheKey, source.stream, source.offlineDash)
        val mainControl = h.control(main) ?: return skip("audio")
        val deck = h.standby()
        s.deck = deck
        val control = h.control(deck) ?: return skip("audio")

        val voice = scope.async(Dispatchers.IO) { withTimeoutOrNull(6_000L) { runCatching { firstVoice(next.track) }.getOrNull() } }
        val clock = scope.async { clockConfirmed(main, mainControl) }
        var outro = AnalysisStore.outro(current.id, duration)
        if (outro == null) {
            outro = read(deck, control, item, max(0L, duration - OUTRO_WINDOW_MS), OUTRO_WINDOW_MS, ::valid)
            outro?.let { AnalysisStore.put(current.id, duration, false, it) }
        }
        if (!valid()) return false
        var intro = AnalysisStore.intro(next.track.id, next.track.durationMs)
        if (intro == null) {
            intro = read(deck, control, nextItem, 0L, INTRO_WINDOW_MS, ::valid)
            intro?.let { AnalysisStore.put(next.track.id, next.track.durationMs, true, it) }
        }
        if (!valid()) return false
        scope.launch(Dispatchers.IO) { AnalysisStore.save() }
        control.reset()

        val out = MixFacts(duration, outro, vocalEndMs = Vocals.endMs(LyricsRepo.state.value.lines, duration))
        val incoming = MixFacts(nextDuration, intro, vocalStartMs = voice.await())
        val clockOk = clock.await()
        fun planFor(beats: Boolean): MixPlan = TransitionPlanner.plan(
            if (beats) out else out.copy(analysis = outro?.copy(confidence = 0f)), incoming, context, settings)
        var plan = planFor(beats = clockOk)
        if (!plan.mixes) return skip(plan.reason)

        fun preRollOf(p: MixPlan) = if (p.kind == MixKind.Beatmatch) min(2_500.0, p.inMs / p.rate.toDouble()).toLong() else min(400L, p.inMs)
        // Too late for a beat mix (a seek near the end, a slow network): the simpler transition at the very end may still fit.
        if (plan.kind == MixKind.Beatmatch && main.currentPosition > plan.outMs - preRollOf(plan) - 1_800L) {
            plan = planFor(beats = false)
            if (!plan.mixes) return skip(plan.reason)
        }
        val preRoll = preRollOf(plan)
        val startOut = plan.outMs - preRoll
        if (main.currentPosition > startOut - 1_200L) return skip("late")
        s.plan = plan

        deck.volume = 0f
        deck.setMediaItem(nextItem, (plan.inMs - preRoll * plan.rate.toDouble()).toLong().coerceAtLeast(0L))
        deck.playbackParameters = PlaybackParameters(plan.rate)
        deck.playWhenReady = false
        deck.prepare()
        val readyBy = SystemClock.elapsedRealtime() + 10_000L
        while (deck.playbackState != androidx.media3.common.Player.STATE_READY) {
            if (!valid() || deck.playerError != null) return deck.playerError != null
            if (SystemClock.elapsedRealtime() > readyBy || main.currentPosition > startOut - 300L) return skip("late")
            delay(30)
        }
        // Levels and filters tied to the music need the processors of both decks in the sound path.
        s.precise = clockOk && control.active && mainControl.active
        if (!s.precise && plan.kind == MixKind.Beatmatch) {
            plan = planFor(beats = false)
            if (!plan.mixes || main.currentPosition > plan.outMs - 1_200L) return skip(plan.reason.ifBlank { "late" })
            s.plan = plan
            deck.playbackParameters = PlaybackParameters.DEFAULT
            deck.seekTo((plan.inMs - min(400L, plan.inMs)).coerceAtLeast(0L))
        }
        val finalPreRoll = preRollOf(plan)
        val finalStart = plan.outMs - finalPreRoll
        // Both automations are tied to positions in their title, so they are set ahead of time: the
        // sound a deck has already buffered when the mix starts then carries its level too.
        if (s.precise) { control.automation = DeckAutomation.incoming(plan); mainControl.automation = DeckAutomation.outgoing(plan) }
        _ui.value = Ui(Phase.Ready, current, next.track, plan.kind, bpm = outro?.bpm ?: 0f)

        // Wait for the place in the outgoing title where the incoming one starts.
        while (true) {
            // A next title that cannot play is not tried again and again: the usual end takes over.
            if (deck.playerError != null) return skip("deck")
            if (!valid()) return false
            val position = main.currentPosition
            if (main.playbackState == androidx.media3.common.Player.STATE_ENDED) return true
            if (position > plan.outMs + 300L) return skip("late")
            if (position >= finalStart) break
            val seconds = ((finalStart - position) / 1000L).coerceAtLeast(0L)
            say("Mix prévu dans $seconds s · ${kindLabel(plan.kind)}")
            delay(if (finalStart - position > 500L) 80L else 6L)
        }

        say("Mix en cours")
        s.baseVolume = main.volume
        if (s.precise) deck.volume = s.baseVolume
        deck.play()
        s.started = true
        _ui.value = Ui(Phase.Mixing, current, next.track, plan.kind, SystemClock.elapsedRealtime() + finalPreRoll, plan.overlapMs,
            bpm = outro?.bpm ?: 0f)
        mix(s, plan, epoch)
        return true
    }

    /** Both decks play: keep them in time, hand the session over, then let the incoming title settle. */
    private suspend fun mix(s: Session, plan: MixPlan, epoch: Int) {
        val h = s.host
        val main = s.main
        val deck = s.deck ?: return
        val end = plan.outMs + plan.overlapMs
        val promoteAt = when {
            plan.kind == MixKind.Beatmatch && plan.swapMs >= 0 -> plan.swapMs + (plan.beatMs / 2f).toLong()
            // A last note is followed at once: the next title is the one shown as soon as it is heard.
            plan.kind == MixKind.Trim -> plan.outMs
            else -> plan.outMs + plan.overlapMs * 55 / 100
        }
        var epochNow = epoch
        var smoothed = Double.NaN
        var speed = plan.rate
        var speedAt = 0L
        var reseeked = false
        while (true) {
            delay(40)
            if (host !== h || s.closed) return
            val heard = if (s.promoted) deck else main
            val other = if (s.promoted) main else deck
            if (Player.playbackEpoch != epochNow || h.main !== heard) return
            // Play and pause reach the deck the listener controls; the other one follows.
            if (other.playWhenReady != heard.playWhenReady) other.playWhenReady = heard.playWhenReady
            if (!heard.playWhenReady) continue
            val outPosition = main.currentPosition
            val inPosition = deck.currentPosition
            val ended = main.playbackState == androidx.media3.common.Player.STATE_ENDED ||
                main.playbackState == androidx.media3.common.Player.STATE_IDLE
            if (deck.playerError != null && !s.promoted) return
            if (!s.promoted && (outPosition >= promoteAt || ended)) {
                if (deck.isPlaying || deck.playbackState == androidx.media3.common.Player.STATE_READY) {
                    promote(s)
                    epochNow = Player.playbackEpoch
                } else if (ended) return
            }
            if (!s.precise) {
                // Without the processors, a plain crossfade on the decks' own volume.
                val x = ((outPosition - plan.outMs).toFloat() / plan.overlapMs).coerceIn(0f, 1f)
                main.volume = s.baseVolume * cos(Math.PI / 2 * x).toFloat()
                deck.volume = s.baseVolume * plan.gain * sin(Math.PI / 2 * x).toFloat()
            } else if (plan.kind == MixKind.Beatmatch && deck.isPlaying && main.isPlaying) {
                val error = DeckSync.errorMs(plan, outPosition, inPosition)
                smoothed = if (smoothed.isNaN()) error else smoothed * .6 + error * .4
                val audible = inPosition >= plan.inMs
                if (!audible && !reseeked && abs(smoothed) > 160.0) {
                    // Far off before it is heard: put it where it should be, then fine-tune.
                    reseeked = true; smoothed = Double.NaN
                    deck.seekTo((plan.inMs + (outPosition - plan.outMs + 90L) * plan.rate.toDouble()).toLong().coerceAtLeast(0L))
                    continue
                }
                val wanted = DeckSync.speed(plan.rate, smoothed, audible)
                val now = SystemClock.elapsedRealtime()
                if (wanted != speed && now - speedAt >= (if (audible) 500L else 200L)) {
                    deck.playbackParameters = PlaybackParameters(wanted)
                    speed = wanted; speedAt = now
                }
            }
            if (outPosition >= end + 120L || ended) break
        }
        if (!s.promoted) return
        // The outgoing title is over: one deck from here on.
        h.release(main)
        _ui.value = Ui()
        if (!s.precise) {
            val from = s.baseVolume * plan.gain
            for (step in 1..40) {
                delay(SETTLE_MS / 40)
                if (h.main !== deck || Player.playbackEpoch != epochNow || s.closed) return
                deck.volume = from + (s.baseVolume - from) * step / 40f
            }
            return
        }
        // Back to the title's own tempo, in steps too small to hear.
        for (step in DeckSync.glide(plan.rate)) {
            delay(500)
            if (h.main !== deck || Player.playbackEpoch != epochNow || s.closed) return
            deck.playbackParameters = if (step == 1f) PlaybackParameters.DEFAULT else PlaybackParameters(step)
        }
        // The automation ends at the title's own level; once past it, nothing is left of the transition.
        val automationEnd = (DeckAutomation.incoming(plan).endUs / 1000L) + 600L
        val limit = SystemClock.elapsedRealtime() + SETTLE_MS + 30_000L
        while (deck.currentPosition < automationEnd && SystemClock.elapsedRealtime() < limit) {
            delay(250)
            if (h.main !== deck || Player.playbackEpoch != epochNow || s.closed) return
        }
    }

    /** Plays [lengthMs] of [item] from [fromMs] on the silent deck, fast, and measures it. */
    private suspend fun read(deck: ExoPlayer, control: DeckControl, item: MediaItem, fromMs: Long, lengthMs: Long,
                             valid: () -> Boolean): SegmentAnalysis? {
        val tap = AnalysisTap(fromMs * 1000L, (fromMs + lengthMs) * 1000L)
        control.automation = DeckAutomation.MUTE
        deck.volume = 0f
        control.tap = tap
        deck.setMediaItem(item, fromMs)
        deck.playbackParameters = PlaybackParameters(ANALYSIS_SPEED)
        deck.prepare()
        deck.play()
        val started = SystemClock.elapsedRealtime()
        val limit = started + (lengthMs / ANALYSIS_SPEED).toLong() + 9_000L
        try {
            while (!tap.done && deck.playbackState != androidx.media3.common.Player.STATE_ENDED && deck.playerError == null &&
                SystemClock.elapsedRealtime() < limit) {
                if (!valid()) return null
                // Playing, but no sound reaches the tap: this format bypasses the processors.
                if (deck.isPlaying && tap.reachedUs == Long.MIN_VALUE && SystemClock.elapsedRealtime() - started > 4_000L) break
                delay(50)
            }
        } finally {
            runCatching { deck.pause() }
            control.tap = null
        }
        delay(60)
        return withContext(Dispatchers.Default) { runCatching { tap.result() }.getOrNull() }
    }

    /**
     * Checks, on the deck being heard, that a position in the title is the player's internal
     * clock minus [DeckControl.offsetUs]. Automation tied to the music relies on it; when it
     * cannot be confirmed, transitions fall back to a plain crossfade on the decks' volume.
     */
    private suspend fun clockConfirmed(main: ExoPlayer, control: DeckControl): Boolean {
        val samples = ArrayList<Long>()
        repeat(14) {
            delay(110)
            if (!main.isPlaying) return@repeat
            val heard = control.heardUs
            if (heard == C.TIME_UNSET) return@repeat
            val ageUs = (System.nanoTime() - control.heardAtNanos) / 1000L
            if (ageUs < 0 || ageUs > 150_000L) return@repeat
            samples += heard + (ageUs * main.playbackParameters.speed).toLong() - main.currentPosition * 1000L
        }
        if (samples.size < 5) return false
        samples.sort()
        return abs(samples[samples.size / 2] - control.offsetUs) <= 45_000L
    }

    /** Where the next title starts singing, from lyrics already on the device or its first lyrics source. */
    private suspend fun firstVoice(track: Track): Long? {
        if (AppSettings.lyricsMode.value == 2 || AppSettings.lyricsSource.value == MusicApi.SRC_OFF) return null
        Downloader.localLyrics(track.id)?.second?.takeIf { it.isNotEmpty() }?.let { return Vocals.startMs(it) }
        return Vocals.startMs(MusicApi.lyrics(track, false, AppSettings.lyricsSource.value, false).second)
    }
}
