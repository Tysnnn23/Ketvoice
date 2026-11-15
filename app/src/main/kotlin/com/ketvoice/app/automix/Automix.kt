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

/** What the playback service lends to Ketmix: its decks. Everything is called on the main thread. */
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
 * Ketmix: mixes the end of a title into the start of the next one. Towards the end of what
 * plays, a second deck reads (silently, eight times faster than real time) the last seconds
 * of the title and the first seconds of the next one; [TransitionPlanner] decides what to
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
                  val promoted: Boolean = false, val bpm: Float = 0f,
                  /** Share of the mix at which the bass changes hands; -1 when it does not. */
                  val swapAt: Float = -1f,
                  /** What was measured of the two titles ("124 → 128 BPM · 8A → 9A"). */
                  val measures: String = "")

    /** The next title is resolved and read this long before the end of the current one, so that it is loaded well before any mix starts. */
    private const val PREP_LEAD_MS = 150_000L
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_MS = 6_000L
    private const val ANALYSIS_SPEED = 8f
    /** Behind another app the phone lends fewer cores: titles are read more slowly, so that the one playing never starves. */
    private const val BACKGROUND_ANALYSIS_SPEED = 3f
    /** Set by the activity: whether Ketvoice is the app on screen. */
    @Volatile var appInFront = true
    internal fun analysisSpeed(inFront: Boolean) = if (inFront) ANALYSIS_SPEED else BACKGROUND_ANALYSIS_SPEED
    private const val SETTLE_MS = 8_000L
    /** How long the decks' processors are waited for before a transition is decided. */
    private const val PATHS_WAIT_MS = 1_500L
    /** How long an incoming deck may play before its processors join; past that, a plain fade on the volume. */
    private const val PATH_JOIN_MS = 900L
    /** How close to its end a title must be for its echo to be played out past it. */
    private const val TAIL_END_MS = 1_000L

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /**
     * What Ketmix is doing or why it is not mixing, in the words shown to the listener (French
     * literals, translated where they are shown). Empty while Ketmix is off.
     */
    /** The bass changed hands (or the cut fell): the moment a listener can feel. */
    private val _swaps = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val swaps: kotlinx.coroutines.flow.SharedFlow<Unit> = _swaps

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
    /** Colour of the last transition played, so that the next one of its kind takes the other. */
    private var lastVariant = -1
    /** The last echo (1) or build (2), so that the next exit on the beat is the other one. */
    private var lastExit = 0

    /** Everything a transition owns, so that giving it up is one call whatever the moment. */
    private class Session(val host: AutomixHost, val main: ExoPlayer, val next: Player.NextEntry) {
        var deck: ExoPlayer? = null
        var plan: MixPlan? = null
        var precise = false
        /** The incoming deck's processors have not joined its sound path yet: it stays silent until they do. */
        var incomingPending = false
        var started = false
        var promoted = false
        var baseVolume = 1f
        var source: Player.DeckSource? = null
        var closed = false
        /** A title on repeat: its own looping is held off during the mix and given back afterwards. */
        var repeatBefore: Int? = null

        /** Leaves a single deck playing, untouched by any level, filter or speed of the transition. */
        fun close() {
            if (closed) return
            closed = true
            val incoming = deck
            if (promoted) {
                // The incoming title is the one being heard: it keeps playing, at its own tempo and level.
                host.release(main)
                // It keeps the speed it was mixed at: a change of speed while a title is heard is heard
                // as a hiccup. The playback service gives the deck its own speed back at the next title.
                if (incoming != null) {
                    host.control(incoming)?.reset()
                    runCatching { incoming.volume = baseVolume }
                }
            } else {
                host.control(main)?.reset()
                repeatBefore?.let { mode -> runCatching { main.repeatMode = mode } }
                // The outgoing title's speed is never touched by a mix; only its level is given back.
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
        // The title on repeat keeps looping on the deck that now plays it.
        current.repeatBefore?.let { mode -> runCatching { deck.repeatMode = mode } }
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
            if (current == null || state.failed) { say("Ketmix actif"); continue }
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
                say(if (duration > 0) "Ketmix actif · le mix du titre suivant se prépare avant la fin de celui-ci" else "Ketmix actif")
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
                    commit("failed: ${failure.javaClass.simpleName}")
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

    /** What is known of the transition being prepared, for [KetmixJournal]. */
    private var draft: KetmixJournal.Entry? = null

    private fun note(update: (KetmixJournal.Entry) -> KetmixJournal.Entry) { draft = draft?.let(update) }

    private fun commit(outcome: String) {
        draft?.let { KetmixJournal.add(it.copy(outcome = outcome, at = System.currentTimeMillis())) }
        draft = null
    }

    /** The transition is given up for this pair: say why, and do not try again. */
    private fun skip(reason: String): Boolean {
        commit("not mixed: $reason")
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
        state.repeatOne && !AppSettings.ketmixLoops.value -> "titre répété"
        SleepTimer.endOfTrack.value || SleepTimer.remainingSeconds.value in 1..180 -> "minuterie active"
        !state.repeatOne && !AppSettings.automixAlbums.value && !state.shuffle && sameAlbum(current, next.track) -> "album joué dans l'ordre"
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
            sameAlbumInOrder = !state.repeatOne && !state.shuffle && sameAlbum(current, next.track),
            repeatOne = state.repeatOne,
            loop = state.repeatOne && AppSettings.ketmixLoops.value && next.track.id == current.id,
            stopAfterCurrent = SleepTimer.endOfTrack.value || SleepTimer.remainingSeconds.value in 1..180,
            exactAudio = h.exact || AppSettings.bitPerfect.value,
            skipSilence = AppSettings.skipSilence.value,
            albumsMixed = AppSettings.automixAlbums.value,
            speed = main.playbackParameters.speed)
        val settings = MixSettings(AppSettings.automixIntensity.value, MixStyle.of(AppSettings.ketmixStyle.value))
        val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
        val nextDuration = next.track.durationMs.takeIf { it > 0 } ?: 600_000L
        // The context alone may already say that these two titles simply follow each other.
        TransitionPlanner.plan(MixFacts(duration), MixFacts(nextDuration), context, settings).let { first ->
            if (!first.mixes && first.reason != "gapless album") return skip(first.reason)
        }

        _ui.value = Ui(Phase.Preparing, current, next.track)
        draft = KetmixJournal.Entry(System.currentTimeMillis(), "${current.title} — ${current.artist}", "${next.track.title} — ${next.track.artist}", "")
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
        // The body of each title, where its drums play, gives the tempo its ending and its intro are read against.
        suspend fun body(id: String, length: Long, media: MediaItem): BeatAnalysis.Hint? {
            if (depth == AnalysisDepth.Light) return null
            val window = AnalysisStore.bodyWindow(length) ?: return null
            val known = AnalysisStore.body(id, length) ?: read(deck, control, media, window.first, AnalysisStore.BODY_MS, depth, ::valid, key = false)
                ?.also { AnalysisStore.putBody(id, length, it) }
            return known?.takeIf { it.hasBeat }?.let { BeatAnalysis.Hint(it.bpm, it.confidence) }
        }
        val outHint = body(current.id, duration, item)
        if (!valid()) return false
        var outro = AnalysisStore.outro(current.id, duration, depth)?.takeIf { outHint == null || it.tempoHint > 0f || it.confidence >= .8f }
        if (outro == null) {
            outro = read(deck, control, item, max(0L, duration - depth.outroMs), depth.outroMs, depth, ::valid, outHint)
            outro?.let { AnalysisStore.put(current.id, duration, false, it) }
        }
        if (!valid()) return false
        val inHint = if (next.track.id == current.id) outHint else body(next.track.id, next.track.durationMs, nextItem)
        if (!valid()) return false
        var intro = AnalysisStore.intro(next.track.id, next.track.durationMs, depth)?.takeIf { inHint == null || it.tempoHint > 0f || it.confidence >= .8f }
        if (intro == null) {
            intro = read(deck, control, nextItem, 0L, depth.introMs, depth, ::valid, inHint)
            intro?.let { AnalysisStore.put(next.track.id, next.track.durationMs, true, it) }
        }
        if (!valid()) return false
        scope.launch(Dispatchers.IO) { AnalysisStore.save() }
        control.reset()

        val out = MixFacts(duration, outro, vocalEndMs = Vocals.endMs(LyricsRepo.state.value.lines, duration))
        val incoming = MixFacts(nextDuration, intro, vocalStartMs = voice.await())
        val clockOk = clock.await()
        note { it.copy(clock = clockOk, outBpm = outro?.bpm ?: 0f, outConfidence = outro?.confidence ?: 0f, outKey = outro?.key ?: MusicalKey.UNREAD,
            inBpm = intro?.bpm ?: 0f, inConfidence = intro?.confidence ?: 0f, inKey = intro?.key ?: MusicalKey.UNREAD,
            sungUntilMs = out.vocalEndMs, sungFromMs = incoming.vocalStartMs,
            outEndMs = outro?.soundEndMs, ending = outro?.let { o -> if (o.fadeOut) "fade" else if (o.hardEnding) "hard" else "rings" } ?: "",
            analysis = listOfNotNull(if (outro == null) "outro not read" else null, if (intro == null) "intro not read" else null,
                outHint?.let { "out body %.0f BPM".format(java.util.Locale.ROOT, it.bpm) }, inHint?.let { "in body %.0f BPM".format(java.util.Locale.ROOT, it.bpm) },
                depth.name.lowercase()).joinToString(", ")) }
        // Without the decks' processors in the sound path only a trim or a plain fade can be played;
        // without beats to rely on (too late for them), the transitions that need none remain.
        val outSpeed = main.playbackParameters.speed
        // A transition that is not played in time leaves the incoming title on its own speed: when the outgoing
        // one plays a little off its own (a previous blend), the positions of the two do not run alike.
        fun planFor(effects: Boolean, beats: Boolean = true): MixPlan = TransitionPlanner.plan(out, incoming,
            context.copy(effects = effects, beats = beats, lastVariant = lastVariant, lastExit = lastExit), settings)
            .let { p -> if (p.mixes && p.kind != MixKind.Beatmatch && abs(outSpeed - 1f) > .0005f) p.copy(rate = 1f / outSpeed) else p }
        var plan = planFor(effects = clockOk)
        if (!plan.mixes) return skip(plan.reason)

        fun preRollOf(p: MixPlan) = if (p.beatTied) min(2_500.0, p.inMs / p.rate.toDouble()).toLong() else min(400L, p.inMs)
        // Too late for a mix on the beat (a seek near the end, a slow network): a transition at the very end may still fit.
        if (plan.beatTied && main.currentPosition > plan.outMs - preRollOf(plan) - 1_800L) {
            plan = planFor(effects = clockOk, beats = false)
            if (!plan.mixes) return skip(plan.reason)
        }
        // A build is heard from its start, bars before the cut: too late for its start, another transition.
        if (plan.kind == MixKind.Rise && main.currentPosition > plan.outMs - plan.buildMs - preRollOf(plan) - 1_500L) {
            plan = planFor(effects = clockOk, beats = false)
            if (!plan.mixes) return skip(plan.reason)
        }
        val preRoll = preRollOf(plan)
        val startOut = plan.outMs - preRoll
        if (main.currentPosition > startOut - 1_200L) return skip("late")
        s.plan = plan

        deck.volume = 0f
        deck.setMediaItem(nextItem, (plan.inMs - preRoll * plan.rate.toDouble()).toLong().coerceAtLeast(0L))
        // The only speed the incoming deck is given while it can be heard, set now while it is silent.
        deck.playbackParameters = DeckSync.speedOf(plan, main.playbackParameters.speed).let { if (it == 1f) PlaybackParameters.DEFAULT else PlaybackParameters(it) }
        deck.playWhenReady = false
        deck.prepare()
        val readyBy = SystemClock.elapsedRealtime() + 10_000L
        while (deck.playbackState != androidx.media3.common.Player.STATE_READY) {
            if (!valid() || deck.playerError != null) return deck.playerError != null
            if (SystemClock.elapsedRealtime() > readyBy || main.currentPosition > startOut - 300L) return skip("late")
            delay(30)
        }
        // Levels and filters tied to the music need the processors of both decks in the sound path. They
        // join it once a deck has decoded some sound: they are given a moment before anything is decided.
        val pathsBy = SystemClock.elapsedRealtime() + PATHS_WAIT_MS
        while (!(control.active && mainControl.active) && SystemClock.elapsedRealtime() < pathsBy && main.currentPosition < startOut - 900L) {
            if (!valid()) return false
            delay(40)
        }
        note { it.copy(pathOut = mainControl.active, pathIn = control.active) }
        // The incoming deck's own processors may only join once it plays: it is kept silent until they do.
        s.precise = clockOk && mainControl.active
        s.incomingPending = s.precise && !control.active
        if (!s.precise && plan.kind != MixKind.Trim && plan.kind != MixKind.Crossfade) {
            plan = planFor(effects = false)
            if (!plan.mixes || main.currentPosition > plan.outMs - 1_200L) return skip(plan.reason.ifBlank { "late" })
            s.plan = plan
            deck.playbackParameters = PlaybackParameters.DEFAULT
            deck.seekTo((plan.inMs - min(400L, plan.inMs)).coerceAtLeast(0L))
        }
        val finalPreRoll = preRollOf(plan)
        val finalStart = plan.outMs - finalPreRoll
        // Both automations are tied to positions in their title, so they are set ahead of time: the
        // sound a deck has already buffered when the mix starts then carries its level too.
        if (s.precise) {
            control.automation = DeckAutomation.incoming(plan); mainControl.automation = DeckAutomation.outgoing(plan)
            // An echo is only played out on silence at the real end of the title, never at the drain a change of speed causes.
            // From the last note's measured end, not from the announced duration, which may be too long.
            mainControl.tailFromUs = if (plan.tail) (max(plan.outMs, plan.cutMs) - TAIL_END_MS) * 1000L else Long.MAX_VALUE
        }
        // On repeat the player would jump back to the start by itself: for this loop the mix does it instead.
        if (plan.loop && main.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) {
            s.repeatBefore = main.repeatMode
            main.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        }
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
            say("Mix prévu dans $seconds s · ${MixWords.describe(plan)}" + MixWords.measures(plan).let { if (it.isEmpty()) "" else " · $it" })
            delay(if (finalStart - position > 500L) 80L else 6L)
        }

        say("Mix en cours · ${MixWords.describe(plan)}")
        note { it.copy(kind = plan.kind, reason = plan.reason, rate = DeckSync.speedOf(plan, main.playbackParameters.speed), overlapMs = plan.overlapMs, onPhrase = plan.onPhrase,
            precise = s.precise, mixAtMs = plan.outMs, inAtMs = plan.inMs, tail = plan.tail) }
        commit("mixed")
        if (plan.kind == MixKind.Beatmatch || plan.kind == MixKind.Filter) lastVariant = plan.variant
        // An echo and a build take turns: the next exit on the beat is the other one.
        if (plan.kind == MixKind.Echo) lastExit = 1
        if (plan.kind == MixKind.Rise) lastExit = 2
        s.baseVolume = main.volume
        if (s.precise && !s.incomingPending) deck.volume = s.baseVolume
        deck.play()
        val playedAt = SystemClock.elapsedRealtime()
        s.started = true
        _ui.value = Ui(Phase.Mixing, current, next.track, plan.kind, SystemClock.elapsedRealtime() + finalPreRoll, plan.overlapMs,
            bpm = outro?.bpm ?: 0f, swapAt = MixWords.swapShare(plan), measures = MixWords.measures(plan))
        mix(s, plan, epoch, playedAt)
        return true
    }

    /** Both decks play: keep them in time, hand the session over, then let the incoming title settle. */
    private suspend fun mix(s: Session, plan: MixPlan, epoch: Int, playedAt: Long = SystemClock.elapsedRealtime()) {
        val h = s.host
        val main = s.main
        val deck = s.deck ?: return
        val end = plan.outMs + plan.overlapMs
        val base = DeckSync.speedOf(plan, main.playbackParameters.speed)
        val promoteAt = when {
            plan.kind == MixKind.Beatmatch && plan.swapMs >= 0 -> plan.swapMs + (plan.beatMs / 2f).toLong()
            // A last note, or a cut on the bar, is followed at once: the next title is the one shown as soon as it is heard.
            plan.kind == MixKind.Trim || plan.kind == MixKind.Echo || plan.kind == MixKind.Rise -> plan.outMs
            else -> plan.outMs + plan.overlapMs * 55 / 100
        }
        var epochNow = epoch
        var smoothed = Double.NaN
        var speed = base
        var speedAt = 0L
        var reseeked = false
        // The moment the bass changes hands, on the outgoing title's clock.
        val swapMoment = MixWords.swapShare(plan).takeIf { it >= 0f }?.let { plan.outMs + (plan.overlapMs * it).toLong() }
        var swapped = false
        var worstSync = 0.0
        var speedChanges = 0
        while (true) {
            delay(40)
            if (host !== h || s.closed) return
            val heard = if (s.promoted) deck else main
            val other = if (s.promoted) main else deck
            if (Player.playbackEpoch != epochNow || h.main !== heard) return
            // Play and pause reach the deck the listener controls; the other one follows.
            if (other.playWhenReady != heard.playWhenReady) other.playWhenReady = heard.playWhenReady
            if (!heard.playWhenReady) continue
            if (s.incomingPending) {
                if (h.control(deck)?.active == true) {
                    // Its processors carry the levels from here: the deck can be heard.
                    s.incomingPending = false
                    deck.volume = s.baseVolume
                    KetmixJournal.amend { it.copy(pathIn = true) }
                } else if (SystemClock.elapsedRealtime() - playedAt > PATH_JOIN_MS) {
                    // They never joined: a plain crossfade on the decks' volume from here.
                    // The outgoing title keeps its own part (a build or an echo stays whole); the incoming one fades in on its volume.
                    s.incomingPending = false
                    s.precise = false
                    h.control(deck)?.automation = null
                    KetmixJournal.amend { it.copy(precise = false, pathIn = false) }
                }
            }
            val outPosition = main.currentPosition
            val inPosition = deck.currentPosition
            if (!swapped && swapMoment != null && outPosition >= swapMoment) { swapped = true; _swaps.tryEmit(Unit) }
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
            } else if (plan.beatTied && deck.isPlaying && main.isPlaying) {
                val error = DeckSync.errorMs(plan, outPosition, inPosition)
                smoothed = if (smoothed.isNaN()) error else smoothed * .6 + error * .4
                val heard = DeckSync.heard(plan, inPosition)
                val settled = DeckSync.settled(plan, inPosition)
                if (!settled && !reseeked && abs(smoothed) > 160.0) {
                    // Far off before it can be heard: put it where it should be, then fine-tune.
                    reseeked = true; smoothed = Double.NaN
                    deck.seekTo((plan.inMs + (outPosition - plan.outMs + 90L) * plan.rate.toDouble()).toLong().coerceAtLeast(0L))
                    continue
                }
                if (heard && plan.kind == MixKind.Beatmatch) worstSync = max(worstSync, abs(smoothed))
                if (speed != base || !settled) {
                    // Small corrections while it cannot be heard yet; back on its own speed early enough that the change,
                    // which reaches the sound only once the audio buffer has played out, lands before it is heard.
                    val now = SystemClock.elapsedRealtime()
                    val wanted = if (settled) base else DeckSync.speed(base, smoothed, audible = false)
                    if (wanted != speed && (settled || now - speedAt >= 200L)) {
                        deck.playbackParameters = if (wanted == 1f) PlaybackParameters.DEFAULT else PlaybackParameters(wanted)
                        speed = wanted; speedAt = now; speedChanges++
                    }
                }
            }
            if (outPosition >= end + 120L || ended) break
        }
        if (plan.kind == MixKind.Beatmatch && s.precise) KetmixJournal.amend { it.copy(syncWorstMs = worstSync.toInt(), speedChanges = speedChanges) }
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
                             depth: AnalysisDepth, valid: () -> Boolean, hint: BeatAnalysis.Hint? = null, key: Boolean = depth.key): SegmentAnalysis? {
        val tap = AnalysisTap(fromMs * 1000L, (fromMs + lengthMs) * 1000L, key, depth.thorough, hint)
        control.automation = DeckAutomation.MUTE
        deck.volume = 0f
        control.tap = tap
        deck.setMediaItem(item, fromMs)
        val speed = analysisSpeed(appInFront)
        deck.playbackParameters = PlaybackParameters(speed)
        deck.prepare()
        deck.play()
        val started = SystemClock.elapsedRealtime()
        val limit = started + (lengthMs / speed).toLong() + 9_000L
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
