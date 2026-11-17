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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.ketvoice.app.stems.StemEngine
import com.ketvoice.app.stems.StemModel
import com.ketvoice.app.stems.StemStore
import com.ketvoice.app.stems.VoiceActivity
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
    /** A title must have played this long before the ones after it are prepared: a listener skipping through titles prepares nothing. */
    private const val PREP_FROM_MS = 4_000L
    /** Titles prepared ahead (their intro and their ending); one on a metered connection. */
    internal const val LOOKAHEAD = 3
    /** How long a skip waits for the next title's deck before it falls back to a plain fade. */
    private const val SKIP_READY_MS = 1_400L
    private const val SKIP_OVERLAP_MS = 3_200L
    private const val SKIP_LEAD_MS = 600L
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
    /** Reads and separates the coming titles while the current one plays (see [prepare]). */
    private var prepJob: Job? = null
    private var prepKey: String? = null
    private var preparedKey: String? = null
    /** Titles whose ending was prepared in this process, with their real length. */
    private val preparedEndings = HashMap<String, Long>()
    /** The deck whose clock was confirmed, for a skip that cannot wait for it. */
    private var clockOf: java.lang.ref.WeakReference<ExoPlayer>? = null
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
                // Played in time with the outgoing title, it glides back to its own tempo once it is alone
                // (6.0.0 Beta 4, Apple Music's way), its pitch kept, in steps too small to be heard.
                if (incoming != null) {
                    host.control(incoming)?.reset()
                    runCatching { incoming.volume = baseVolume }
                    if (runCatching { incoming.playbackParameters.speed }.getOrDefault(1f) != 1f) returnToTempo(incoming)
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

    private var tempoJob: Job? = null

    /** Brings [deck] back to its own speed after a mix in time, while it is the only deck and no mix starts. */
    private fun returnToTempo(deck: androidx.media3.exoplayer.ExoPlayer) {
        tempoJob?.cancel()
        tempoJob = scope.launch {
            delay(TempoReturn.WAIT_MS)
            while (isActive) {
                val h = host ?: break
                if (h.main !== deck || mixing) break
                val speed = runCatching { deck.playbackParameters.speed }.getOrDefault(1f)
                if (speed == 1f) break
                val next = TempoReturn.next(speed)
                runCatching { deck.playbackParameters = if (next == 1f) PlaybackParameters.DEFAULT else PlaybackParameters(next) }
                delay(TempoReturn.EVERY_MS)
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

    /** A mix is being prepared or played: other work on the phone's processor waits. */
    val busy: Boolean get() = job?.isActive == true

    /** True from the moment the incoming title plays until it is the only one. */
    val mixing: Boolean get() = session?.let { it.started && !it.closed } == true

    /** The listener chose something else, or moved in the title: the transition is dropped at once. */
    fun cancel() {
        prepJob?.cancel(); prepJob = null
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
                // Since 5.11.0 the titles that follow are read, measured and separated from the first seconds of this one:
                // the mix is ready long before the end, and a skip finds its title prepared.
                if (duration > 0 && position >= PREP_FROM_MS && prepKey != key && prepJob?.isActive != true) {
                    prepKey = key
                    prepJob = scope.launch {
                        try { prepare(target, main, current, duration) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { android.util.Log.w("KetvoiceAutomix", "preparation failed: ${failure.javaClass.simpleName}") }
                    }
                }
                say(when {
                    duration <= 0 -> "Ketmix actif"
                    prepJob?.isActive == true -> "Ketmix actif · préparation des titres suivants…"
                    preparedKey == key -> "Ketmix actif · mix du titre suivant prêt"
                    else -> "Ketmix actif · le mix du titre suivant se prépare avant la fin de celui-ci"
                })
                continue
            }
            // The mix itself takes over the standby deck from here.
            prepJob?.cancel(); prepJob = null
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
            speed = main.playbackParameters.speed,
            userOverlapMs = MixPointStore.pair(current.id, next.track.id)?.overlapMs ?: -1L)
        // The style the listener chose for this pair in the DJ editor (6.1) comes before the one of the settings.
        val settings = MixSettings(AppSettings.automixIntensity.value,
            MixPointStore.pair(current.id, next.track.id)?.style ?: MixStyle.of(AppSettings.ketmixStyle.value))
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
            // Behind other apps only a body already read is used: two more readings would cost the title being heard.
            val known = AnalysisStore.body(id, length) ?: if (!appInFront) null else read(deck, control, media, window.first, AnalysisStore.BODY_MS, depth, ::valid, key = false)
                ?.also { AnalysisStore.putBody(id, length, it) }
            return known?.takeIf { it.hasBeat }?.let { BeatAnalysis.Hint(it.bpm, it.confidence) }
        }
        val outHint = body(current.id, duration, item)
        if (!valid()) return false
        // The voices are really separated when the model is there (5.10.0): the parts of both titles that a mix can use are recorded.
        val separate = AppSettings.ketmixStems.value && StemModel.anyReady && !h.exact
        val outFrom = max(0L, duration - depth.outroMs)
        val stemOutFrom = max(outFrom, duration - StemEngine.MAX_PART_MS)
        val stemInUntil = min(depth.introMs, StemEngine.MAX_PART_MS)
        var outCapture: PcmCapture? = null
        var inCapture: PcmCapture? = null
        var outro = AnalysisStore.outro(current.id, duration, depth)?.takeIf { outHint == null || it.tempoHint > 0f || it.confidence >= .8f }
        if (outro == null) {
            outCapture = if (separate) PcmCapture(stemOutFrom * 1000L, duration * 1000L) else null
            outro = read(deck, control, item, outFrom, depth.outroMs, depth, ::valid, outHint, capture = outCapture, waveOf = current.id to duration)
            outro?.let { AnalysisStore.put(current.id, duration, false, it) }
        } else if (separate) outCapture = capture(deck, control, item, stemOutFrom, duration - stemOutFrom, ::valid)
        if (!valid()) return false
        val inHint = if (next.track.id == current.id) outHint else body(next.track.id, next.track.durationMs, nextItem)
        if (!valid()) return false
        var intro = AnalysisStore.intro(next.track.id, next.track.durationMs, depth)?.takeIf { inHint == null || it.tempoHint > 0f || it.confidence >= .8f }
        if (intro == null) {
            inCapture = if (separate) PcmCapture(0L, stemInUntil * 1000L) else null
            intro = read(deck, control, nextItem, 0L, depth.introMs, depth, ::valid, inHint, capture = inCapture, waveOf = next.track.id to next.track.durationMs)
            intro?.let { AnalysisStore.put(next.track.id, next.track.durationMs, true, it) }
        } else if (separate) inCapture = capture(deck, control, nextItem, 0L, stemInUntil, ::valid)
        if (!valid()) return false
        scope.launch(Dispatchers.IO) { AnalysisStore.save() }
        control.reset()

        // Where each title sings: from its lyrics, and from its separated voice when it has one (5.11.0),
        // so that a title without lyrics is not mixed over its last sung words either.
        val sung = if (!AppSettings.ketmixStems.value) null to null else withContext(Dispatchers.Default) {
            runCatching { StemStore.load(current.id, duration, false)?.let(VoiceActivity::endMs) }.getOrNull() to
                runCatching { StemStore.load(next.track.id, nextDuration, true)?.let(VoiceActivity::startMs) }.getOrNull()
        }
        // The points the listener chose in the DJ editor (6.1), remembered per title.
        val out = MixFacts(duration, outro, vocalEndMs = VoiceFacts.end(Vocals.endMs(LyricsRepo.state.value.lines, duration), sung.first),
            userExitMs = MixPointStore.exitOf(current.id, duration))
        val incoming = MixFacts(nextDuration, intro, vocalStartMs = VoiceFacts.start(voice.await(), sung.second),
            userEntryMs = MixPointStore.entryOf(next.track.id, nextDuration))
        val clockOk = clock.await()
        if (clockOk) clockOf = java.lang.ref.WeakReference(main)
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
            .let { p -> if (p.mixes && AppSettings.ketmixStems.value) p.copy(stems = true, voiceFromMs = incoming.vocalStartMs ?: -1L) else p }
        var plan = planFor(effects = clockOk)
        if (!plan.mixes) return skip(plan.reason)
        // The incoming voice alone is only rendered when this pair may be mixed voice over instrumental.
        val wantVocals = Mashup.fits(plan)
        // The network runs once per part of a title, on a thread of its own; its result is kept for later mixes.
        val compute = !AppSettings.ketmixStemsCharging.value || StemModel.charging()
        val nextId = next.track.id
        val stemWork = if (!separate) null else CoroutineScope(currentCoroutineContext()).async(stemThread) {
            val ctx = currentCoroutineContext()
            fun part(c: PcmCapture?, id: String, length: Long, intro: Boolean) = c?.pcm()?.let { pcm ->
                runCatching { StemEngine.instrumental(id, length, intro, pcm, c.rate, c.startUs, compute, withVocals = intro && wantVocals) { !ctx.isActive } }
                    .onFailure { android.util.Log.w("KetvoiceStems", "separation failed: ${it.javaClass.simpleName}") }.getOrNull()
            }
            part(outCapture, current.id, duration, false) to part(inCapture, nextId, nextDuration, true)
        }

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
        // The separated instrumentals join the decks before their automations do.
        if (stemWork != null && s.precise) {
            val budget = finalStart - main.currentPosition - 2_500L
            val stems = if (stemWork.isCompleted) runCatching { stemWork.getCompleted() }.getOrNull()
                else if (budget > 0) withTimeoutOrNull(budget) { runCatching { stemWork.await() }.getOrNull() } else null
            if (!valid()) return false
            stems?.first?.let { mainControl.stem = it }
            stems?.second?.let { control.stem = it }
            // The incoming voice over the outgoing instrumental, when both separations cover the blend.
            val outStem = stems?.first; val inStem = stems?.second
            if (Mashup.fits(plan) && outStem != null && inStem?.vocals != null &&
                outStem.covers(plan.outMs * 1000L, (plan.outMs + plan.overlapMs) * 1000L - 200_000L) &&
                inStem.covers((plan.inMs + 150L) * 1000L, (plan.inMs + ((plan.swapMs - plan.outMs) * plan.rate).toLong() + 1_500L) * 1000L)) {
                plan = plan.copy(mashup = true); s.plan = plan
            }
            note { it.copy(analysis = it.analysis + ", voices " + when {
                plan.mashup -> "over instrumental"
                stems?.first != null && stems.second != null -> "separated"
                stems?.first != null || stems?.second != null -> "partly separated"
                else -> "band cut" }) }
        }
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
                             depth: AnalysisDepth, valid: () -> Boolean, hint: BeatAnalysis.Hint? = null, key: Boolean = depth.key,
                             capture: PcmCapture? = null, waveOf: Pair<String, Long>? = null): SegmentAnalysis? {
        val tap = AnalysisTap(fromMs * 1000L, (fromMs + lengthMs) * 1000L, key, depth.thorough, hint)
        // The same reading draws the stretch's wave for the DJ editor (6.1), kept apart from the analysis.
        val wave = waveOf?.let { WaveTap(fromMs * 1000L, (fromMs + lengthMs) * 1000L) }
        control.automation = DeckAutomation.MUTE
        deck.volume = 0f
        val measured: PcmTap = if (wave != null) BothTaps(tap, wave) else tap
        control.tap = if (capture != null) BothTaps(measured, capture) else measured
        deck.setMediaItem(item, fromMs)
        val speed = analysisSpeed(appInFront)
        deck.playbackParameters = PlaybackParameters(speed)
        deck.prepare()
        deck.play()
        val started = SystemClock.elapsedRealtime()
        val limit = started + (lengthMs / speed).toLong() + 9_000L
        try {
            while (!(tap.done && capture?.done != false) && deck.playbackState != androidx.media3.common.Player.STATE_ENDED && deck.playerError == null &&
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
        if (wave != null && waveOf != null) withContext(Dispatchers.IO) {
            runCatching { wave.result()?.let { WaveStore.put(waveOf.first, waveOf.second, it) } }
        }
        return withContext(Dispatchers.Default) { runCatching { tap.result() }.getOrNull() }
    }

    /**
     * Prepares the mixes to come while the current title plays (5.11.0): the ending of this title, then
     * the intro and the ending of the next [LOOKAHEAD] titles are read on a silent deck, measured, and
     * their voices separated. All of it is kept, so the mix itself, a skip, or the same titles another
     * day find the work done.
     */
    private suspend fun prepare(h: AutomixHost, main: ExoPlayer, current: Track, duration: Long) {
        val epoch = Player.playbackEpoch
        val key = prepKey
        fun valid() = host === h && h.main === main && Player.playbackEpoch == epoch && AppSettings.automix.value &&
            main.playerError == null && !(h.exact || AppSettings.bitPerfect.value)
        if (!valid()) return
        val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
        // 6.0.0 Beta 5, background thrift: screen away and on battery, the phone's own battery manager
        // (MagicOS, HyperOS, One UI…) stops apps that work hard in the background, music or not. Then only
        // the next title is measured, and nothing is separated that was not separated already.
        val thrift = BackgroundThrift.active()
        val separate = !thrift && AppSettings.ketmixStems.value && StemModel.anyReady && (!AppSettings.ketmixStemsCharging.value || StemModel.charging())
        // The better model is about ten times slower: on battery it prepares only the next title.
        val slow = thrift || (separate && StemEngine.wanted() == com.ketvoice.app.stems.StemMask.HIGH && !StemModel.charging())
        fun known(id: String, length: Long, intro: Boolean) =
            (if (intro) AnalysisStore.intro(id, length, depth) else AnalysisStore.outro(id, length, depth)) != null &&
                (!separate || !StemEngine.needs(id, length, intro))
        val item = main.currentMediaItem ?: return
        val mainControl = h.control(main) ?: return
        if (clockOf?.get() !== main && clockConfirmed(main, mainControl)) clockOf = java.lang.ref.WeakReference(main)
        if (!valid()) return
        fun introLength(t: Track) = t.durationMs.takeIf { it > 0 } ?: 600_000L
        fun upcoming() = Player.automixUpcoming(if (Player.onMeteredNetwork() || slow) 1 else LOOKAHEAD).filter { it.track.id != current.id }
        fun done(t: Track) = known(t.id, introLength(t), true) && preparedEndings[t.id]?.let { known(t.id, it, false) } == true
        if (known(current.id, duration, false) && upcoming().all { done(it.track) }) { preparedKey = key; return }

        val deck = h.standby()
        val control = h.control(deck) ?: run { h.release(deck); return }
        val separations = ArrayDeque<kotlinx.coroutines.Deferred<Boolean>>()
        suspend fun part(media: MediaItem, id: String, length: Long, intro: Boolean) {
            if (!valid() || known(id, length, intro)) return
            val from = if (intro) 0L else max(0L, length - depth.outroMs)
            val span = if (intro) min(depth.introMs, length) else length - from
            val stemFrom = if (intro) 0L else max(from, length - StemEngine.MAX_PART_MS)
            val stemUntil = if (intro) min(span, StemEngine.MAX_PART_MS) else length
            val wantStem = separate && StemEngine.needs(id, length, intro)
            var recorded = if (wantStem) PcmCapture(stemFrom * 1000L, stemUntil * 1000L) else null
            val analysed = (if (intro) AnalysisStore.intro(id, length, depth) else AnalysisStore.outro(id, length, depth)) != null
            if (!analysed) {
                // The body of the title, where its drums play, gives the tempo this part is read against.
                val hint = if (depth == AnalysisDepth.Light) null else AnalysisStore.bodyWindow(length)?.let { window ->
                    (AnalysisStore.body(id, length) ?: if (!appInFront) null else read(deck, control, media, window.first, AnalysisStore.BODY_MS, depth, ::valid, key = false)
                        ?.also { AnalysisStore.putBody(id, length, it) })?.takeIf { it.hasBeat }?.let { BeatAnalysis.Hint(it.bpm, it.confidence) }
                }
                if (!valid()) return
                read(deck, control, media, from, span, depth, ::valid, hint, capture = recorded, waveOf = id to length)?.let { AnalysisStore.put(id, length, intro, it) }
            } else if (wantStem) recorded = capture(deck, control, media, stemFrom, stemUntil - stemFrom, ::valid)
            val sound = recorded?.takeIf { valid() }?.pcm() ?: return
            // No more than two parts wait for the network at once: each is ten megabytes of sound.
            while (separations.size >= 2) separations.removeFirst().await()
            val (rate, startUs) = recorded.rate to recorded.startUs
            separations += scope.async(stemThread) {
                val ctx = currentCoroutineContext()
                runCatching { StemEngine.prepare(id, length, intro, sound, rate, startUs) { !ctx.isActive } }.getOrDefault(false)
            }
        }
        try {
            part(item, current.id, duration, false)
            SmartQueue.reorder(current, duration)
            for (entry in upcoming()) {
                if (!valid()) return
                val t = entry.track
                if (done(t)) continue
                Player.warmForAutomix(t)
                val source = Player.resolveDeckSource(t) ?: continue
                if (!valid()) return
                val media = playbackMediaItem(t, source.url, source.cacheKey, source.stream, source.offlineDash)
                part(media, t.id, introLength(t), true)
                if (!valid()) return
                // Its ending is filed under its real length, which the deck knows once the title is loaded.
                var real = preparedEndings[t.id] ?: 0L
                if (real <= 0L) {
                    if (deck.currentMediaItem?.mediaId != media.mediaId || deck.duration <= 0) {
                        deck.volume = 0f; control.automation = DeckAutomation.MUTE
                        deck.setMediaItem(media); deck.playWhenReady = false; deck.prepare()
                        val by = SystemClock.elapsedRealtime() + 8_000L
                        while (deck.duration <= 0 && deck.playerError == null && SystemClock.elapsedRealtime() < by) { if (!valid()) return; delay(40) }
                    }
                    real = deck.duration
                }
                if (real >= TransitionPlanner.MIN_TITLE_MS) { part(media, t.id, real, false); if (valid()) preparedEndings[t.id] = real }
            }
            while (separations.isNotEmpty()) separations.removeFirst().await()
            if (valid()) preparedKey = key
        } finally {
            separations.forEach { it.cancel() }
            scope.launch(Dispatchers.IO) { AnalysisStore.save() }
            control.reset()
            h.release(deck)
            if (preparedEndings.size > 200) preparedEndings.clear()
        }
    }

    /**
     * The listener skips to the next title: when that title is prepared and loads at once, a real
     * mix of a few seconds takes them there, the incoming title entering where its music starts
     * (5.11.0). False when it cannot be done now: the caller then fades as before.
     */
    suspend fun skipMix(): Boolean {
        val h = host ?: return false
        if (!AppSettings.automix.value || h.exact || AppSettings.bitPerfect.value || mixing) return false
        val state = Player.state.value
        val current = state.current ?: return false
        if (state.repeatOne || state.failed) return false
        val next = Player.automixNext() ?: return false
        val main = h.main
        if (!main.isPlaying || main.duration - main.currentPosition < SKIP_OVERLAP_MS + 6_000L) return false
        val nextDuration = next.track.durationMs.takeIf { it > 0 } ?: 600_000L
        val intro = AnalysisStore.intro(next.track.id, nextDuration) ?: return false
        if (nextDuration < TransitionPlanner.MIN_TITLE_MS) return false
        // Only a title that needs no network question: downloaded, cached or already resolved.
        val source = Player.deckSource(next.track) ?: return false
        val mainControl = h.control(main) ?: return false
        prepJob?.cancel(); prepJob = null
        job?.cancel(); job = null
        session?.close(); session = null
        val epoch = Player.playbackEpoch
        val s = Session(h, main, next)
        s.source = source
        session = s
        val deck = h.standby()
        s.deck = deck
        var handedOver = false
        try {
            val control = h.control(deck) ?: return false
            // The entry the listener chose in the DJ editor, when there is one and it leaves room for the blend.
            val inMs = MixPointStore.entryOf(next.track.id, nextDuration).takeIf { it in 0..(nextDuration - SKIP_OVERLAP_MS - 8_000L) }
                ?: intro.soundStartMs.coerceAtLeast(0L)
            // The outgoing deck has already processed about half a second of sound that is still to be heard:
            // its part of the mix starts just past that, so that its level and filter move from their first step.
            val preRoll = min(SKIP_LEAD_MS - 60L, inMs)
            deck.volume = 0f
            deck.setMediaItem(playbackMediaItem(next.track, source.url, source.cacheKey, source.stream, source.offlineDash), inMs - preRoll)
            deck.playWhenReady = false
            deck.prepare()
            val readyBy = SystemClock.elapsedRealtime() + SKIP_READY_MS
            while (deck.playbackState != androidx.media3.common.Player.STATE_READY) {
                if (deck.playerError != null || SystemClock.elapsedRealtime() > readyBy || host !== h || h.main !== main ||
                    Player.playbackEpoch != epoch || !main.isPlaying) return false
                delay(20)
            }
            val outro = AnalysisStore.outro(current.id, main.duration)
            val gain = if (outro != null) Math.pow(10.0, ((outro.loudnessDb - intro.loudnessDb) / 20f).toDouble()).toFloat().coerceIn(.5f, 1f) else 1f
            val variant = if (lastVariant == 0) 1 else 0
            val plan = MixPlan(MixKind.Filter, outMs = main.currentPosition + SKIP_LEAD_MS, inMs = inMs, overlapMs = SKIP_OVERLAP_MS,
                gain = gain, reason = "skip", variant = variant, hold = .2f)
            s.plan = plan
            s.precise = clockOf?.get() === main && mainControl.active
            s.incomingPending = s.precise && !control.active
            if (s.precise) { control.automation = DeckAutomation.incoming(plan); mainControl.automation = DeckAutomation.outgoing(plan) }
            lastVariant = variant
            s.baseVolume = main.volume
            if (s.precise && !s.incomingPending) deck.volume = s.baseVolume
            deck.play()
            val playedAt = SystemClock.elapsedRealtime()
            s.started = true
            KetmixJournal.add(KetmixJournal.Entry(System.currentTimeMillis(), "${current.title} — ${current.artist}", "${next.track.title} — ${next.track.artist}",
                "mixed", kind = plan.kind, reason = "skip", overlapMs = plan.overlapMs, precise = s.precise, mixAtMs = plan.outMs, inAtMs = plan.inMs))
            say("Mix en cours · ${MixWords.describe(plan)}")
            _ui.value = Ui(Phase.Mixing, current, next.track, plan.kind, SystemClock.elapsedRealtime() + preRoll, plan.overlapMs)
            handedOver = true
            job = scope.launch {
                try { mix(s, plan, epoch, playedAt) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.w("KetvoiceAutomix", "skip mix failed: ${failure.javaClass.simpleName}") }
                finally {
                    s.close()
                    if (session === s) { session = null; if (_ui.value.phase != Phase.Idle) _ui.value = Ui() }
                }
            }
            return true
        } finally {
            if (!handedOver) { s.close(); if (session === s) session = null }
        }
    }

    /** What the DJ editor could load of one end of a title. */
    sealed class EditorLoad {
        data class Ready(val durationMs: Long) : EditorLoad()
        /** Nothing has played yet: there is no deck to read the title with. */
        object NoPlayer : EditorLoad()
        /** Bit-perfect output: the decks have no processors to read the sound from. */
        object Exact : EditorLoad()
        /** The title could not be loaded (no network, no source). */
        object Unavailable : EditorLoad()
    }

    /**
     * Reads, on a silent deck of its own, the stretch of [track] the DJ editor shows (6.1): its last
     * [MixEditor.TAIL_MS] when [tail], else its first [MixEditor.HEAD_MS]. Its wave is kept in [WaveStore]
     * and, when Ketmix has not measured that end of the title yet, its analysis too, read over the stretch
     * Ketmix always reads. Titles measured before 6.1 have no wave: this draws it when the editor opens.
     */
    suspend fun loadForEditor(track: Track, tail: Boolean, progress: (Float) -> Unit = {}): EditorLoad {
        val h = host ?: return EditorLoad.NoPlayer
        if (h.exact || AppSettings.bitPerfect.value) return EditorLoad.Exact
        // A mix being heard is not disturbed by a third deck reading fast.
        while (mixing) delay(250)
        val isCurrent = Player.state.value.current?.id == track.id
        val media = (if (isCurrent) h.main.currentMediaItem else null) ?: run {
            val source = Player.resolveDeckSource(track) ?: return EditorLoad.Unavailable
            playbackMediaItem(track, source.url, source.cacheKey, source.stream, source.offlineDash)
        }
        if (host !== h) return EditorLoad.NoPlayer
        val deck = h.standby()
        try {
            val control = h.control(deck) ?: return EditorLoad.Exact
            control.automation = DeckAutomation.MUTE
            deck.volume = 0f
            // The real length, which the deck knows once the title is loaded.
            var duration = if (isCurrent) h.main.duration.coerceAtLeast(0L) else 0L
            if (duration <= 0L) {
                deck.setMediaItem(media); deck.playWhenReady = false; deck.prepare()
                val by = SystemClock.elapsedRealtime() + 12_000L
                while (deck.duration <= 0 && deck.playerError == null && SystemClock.elapsedRealtime() < by) {
                    if (host !== h) return EditorLoad.NoPlayer
                    delay(40)
                }
                duration = deck.duration
            }
            if (duration <= 0L) return EditorLoad.Unavailable
            val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
            val window = min(if (tail) MixEditor.TAIL_MS else MixEditor.HEAD_MS, duration)
            val from = if (tail) duration - window else 0L
            // Filed as Ketmix files them: an ending under the title's real length, a start under the length the queue gives it.
            val key = if (tail) duration else track.durationMs.takeIf { it > 0 } ?: duration
            val wave = WaveTap(from * 1000L, (from + window) * 1000L)
            val analysisFrom = if (tail) max(0L, duration - depth.outroMs) else 0L
            val analysisUntil = if (tail) duration else min(depth.introMs, duration)
            val known = if (tail) AnalysisStore.outro(track.id, duration, depth) else AnalysisStore.intro(track.id, key, depth)
            val hint = if (depth == AnalysisDepth.Light) null
                else AnalysisStore.body(track.id, duration)?.takeIf { it.hasBeat }?.let { BeatAnalysis.Hint(it.bpm, it.confidence) }
            val analysis = if (known == null) AnalysisTap(analysisFrom * 1000L, analysisUntil * 1000L, depth.key, depth.thorough, hint) else null
            control.tap = if (analysis != null) BothTaps(wave, FromTap(analysisFrom * 1000L, analysis)) else wave
            deck.setMediaItem(media, from)
            val speed = analysisSpeed(appInFront)
            deck.playbackParameters = PlaybackParameters(speed)
            deck.prepare()
            deck.play()
            val started = SystemClock.elapsedRealtime()
            val limit = started + (window / speed).toLong() + 12_000L
            try {
                while (!wave.done && deck.playbackState != androidx.media3.common.Player.STATE_ENDED && deck.playerError == null &&
                    SystemClock.elapsedRealtime() < limit) {
                    if (host !== h) return EditorLoad.NoPlayer
                    // Playing, but no sound reaches the tap: this format bypasses the processors.
                    if (deck.isPlaying && wave.reachedUs == Long.MIN_VALUE && SystemClock.elapsedRealtime() - started > 5_000L) break
                    if (wave.reachedUs != Long.MIN_VALUE) progress(((wave.reachedUs / 1000L - from).toFloat() / window).coerceIn(0f, 1f))
                    delay(80)
                }
            } finally {
                runCatching { deck.pause() }
                control.tap = null
            }
            val drawn = withContext(Dispatchers.Default) { runCatching { wave.result() }.getOrNull() } ?: return EditorLoad.Unavailable
            withContext(Dispatchers.IO) {
                WaveStore.put(track.id, key, drawn)
                analysis?.let { runCatching { it.result() }.getOrNull() }?.let { AnalysisStore.put(track.id, key, !tail, it); AnalysisStore.save() }
            }
            progress(1f)
            return EditorLoad.Ready(duration)
        } finally {
            h.release(deck)
        }
    }

    /** Forgets that the transitions from the queue entry [entryId] were settled, so that they are planned again. */
    private fun forget(entryId: String) {
        val prefix = "$entryId>"
        settled.removeAll { it.startsWith(prefix) }
        attempts.keys.removeAll { it.startsWith(prefix) }
        retryAt.keys.removeAll { it.startsWith(prefix) }
    }

    /**
     * The listener changed the points of the transition about to play in the DJ editor: unless it is being heard,
     * it is planned again with them.
     */
    fun pointsChanged() {
        if (mixing) return
        val state = Player.state.value
        val entry = state.queueEntryIds.getOrNull(state.index) ?: return
        if (job?.isActive == true || session != null) {
            job?.cancel(); job = null
            session?.close(); session = null
            if (_ui.value.phase != Phase.Idle) _ui.value = Ui()
        }
        forget(entry)
    }

    /** How long before its start the DJ editor's « Écouter » plays a transition: room for the engine to prepare it, and to hear it come. */
    const val PREVIEW_LEAD_MS = 12_000L

    /**
     * « Écouter » in the DJ editor (6.1): the title playing is taken back to [PREVIEW_LEAD_MS] before the
     * transition ([plan], with the listener's points, already remembered) starts, and Ketmix plays it as it
     * will play it — by the engine itself, the decks, the analysis and the plan of a real mix, not a copy of it.
     * False when Ketmix cannot mix now (off, bit-perfect, nothing playing, a mix under way).
     */
    fun preview(plan: MixPlan): Boolean {
        val h = host ?: return false
        if (!AppSettings.automix.value || h.exact || AppSettings.bitPerfect.value || mixing || !plan.mixes) return false
        val state = Player.state.value
        val entry = state.queueEntryIds.getOrNull(state.index) ?: return false
        val duration = h.main.duration.takeIf { it > 0 } ?: return false
        // The engine starts on a title's ending only in its last PREP_LEAD_MS.
        val target = (plan.outMs - plan.buildMs - PREVIEW_LEAD_MS).coerceIn(max(0L, duration - PREP_LEAD_MS + 1_000L), duration)
        forget(entry)
        Player.seekTo(target)
        if (!state.playWhenReady) Player.toggle()
        return true
    }

    /** Plays [lengthMs] of [item] from [fromMs] on the silent deck, fast, and records its sound for the separation of its voice. */
    private suspend fun capture(deck: ExoPlayer, control: DeckControl, item: MediaItem, fromMs: Long, lengthMs: Long,
                                valid: () -> Boolean): PcmCapture? {
        val capture = PcmCapture(fromMs * 1000L, (fromMs + lengthMs) * 1000L)
        control.automation = DeckAutomation.MUTE
        deck.volume = 0f
        control.tap = capture
        deck.setMediaItem(item, fromMs)
        val speed = analysisSpeed(appInFront)
        deck.playbackParameters = PlaybackParameters(speed)
        deck.prepare()
        deck.play()
        val started = SystemClock.elapsedRealtime()
        val limit = started + (lengthMs / speed).toLong() + 9_000L
        try {
            while (!capture.done && deck.playbackState != androidx.media3.common.Player.STATE_ENDED && deck.playerError == null &&
                SystemClock.elapsedRealtime() < limit) {
                if (!valid()) return null
                if (deck.isPlaying && capture.rate == 0 && SystemClock.elapsedRealtime() - started > 4_000L) break
                delay(50)
            }
        } finally {
            runCatching { deck.pause() }
            control.tap = null
        }
        return capture
    }

    /** The separations run on one thread of low priority: the title being heard never waits for them. */
    private val stemThread by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ketvoice-stems")
        }.asCoroutineDispatcher()
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

/**
 * The way back to a title's own tempo after a mix in time (6.0.0 Beta 4): steps of a quarter of a percent,
 * half a second apart, the pitch kept by Media3's time stretching. Six percent take twelve seconds,
 * and no step is heard as a change of speed.
 */
internal object TempoReturn {
    const val STEP = .0025f
    const val EVERY_MS = 500L
    /** The blend's last bars ring out on the title's mixed speed before the glide starts. */
    const val WAIT_MS = 2_000L

    fun next(speed: Float): Float = when {
        kotlin.math.abs(speed - 1f) <= STEP -> 1f
        speed > 1f -> speed - STEP
        else -> speed + STEP
    }

    /** How long the glide back from [speed] takes. */
    fun durationMs(speed: Float): Long = (kotlin.math.ceil(kotlin.math.abs(speed - 1f) / STEP - 1e-4).toLong()) * EVERY_MS
}

/**
 * Background thrift (6.0.0 Beta 5, owner: playback cut in the background on an Honor phone): while no
 * screen of the app is shown and the phone is on battery, the work beside the music (Ketmix's preparation,
 * voice separation) is kept to the least, so that the manufacturer's battery manager has no reason to stop it.
 */
internal object BackgroundThrift {
    fun active(inFront: Boolean = Automix.appInFront, charging: Boolean = com.ketvoice.app.stems.StemModel.charging()): Boolean =
        !inFront && !charging
}

