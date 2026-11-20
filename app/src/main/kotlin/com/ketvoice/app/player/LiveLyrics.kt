package com.ketvoice.app.player

import com.ketvoice.app.data.LyricsOffsets
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.LyricsTranslation
import com.ketvoice.app.data.MusicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * The sung line outside the app (6.1, owner's choice « Paroles sur l'écran verrouillé »): the lock screen and
 * shade notification ([LockscreenLyrics]) reads it from here.
 *
 * Nothing polls. The line is worked out when something changes (a title, its lyrics, a seek, a pause, the speed,
 * the voice stage delay, a translation, the screen coming on) and then once at the moment the line changes, from
 * the line timings: a three-minute instrumental costs one wake-up, and while the screen is off nothing is
 * scheduled at all, because neither the lock screen nor the home screen can be seen.
 */

/** What the lyrics clock knows of the deck being heard. */
internal data class Playhead(
    val mediaId: String,
    val positionMs: Long,
    /** The listener wants it to play (a pause or the end hides the line). */
    val playWhenReady: Boolean,
    /** The sound really moves (not buffering, not ducked to silence by a call): only then is a change scheduled. */
    val playing: Boolean,
    val speed: Float = 1f,
    val title: String = "",
    val artist: String = "",
)

/** The line on screen: the sung line big, the next one small, its translation when the lyrics screen shows one. */
internal data class LiveLine(
    val trackId: String,
    val title: String,
    val artist: String,
    /** Index of the sung line in the lyrics, or -1 before the first line and in a long break. */
    val index: Int,
    val current: String,
    val next: String?,
    val translation: String? = null,
)

/** One moment of a title's lyrics: what is sung, what comes next, and until when (lyrics time, ms). */
internal data class LyricMoment(val index: Int, val current: String, val next: String?, val endsAt: Long?)

/** Which line is sung at a position, from synced lyrics (lines in time order, as every provider gives them). */
internal object LyricTimeline {
    /** Shown before the first line and during a long break. */
    const val REST = "♪"
    /** A silence this long after a line's last word (when words are timed) becomes a break… */
    const val BREAK_MIN_MS = 8_000L
    /** …once the line has stayed this long after its last word. */
    const val BREAK_HOLD_MS = 2_000L

    private fun shown(text: String): String = text.trim().ifEmpty { REST }
    private fun upcoming(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it != REST }

    /** When a line's last word ends, or -1 when its words are not timed. */
    fun lineEnd(line: MusicApi.LyricLine): Long {
        val last = line.words.lastOrNull() ?: return -1L
        return if (last.endMs > 0) last.endMs else -1L
    }

    /** The moment at [positionMs] (lyrics time: the playhead plus the listener's offset, minus the voice stage delay), or null without timed lines. */
    fun at(lines: List<MusicApi.LyricLine>, positionMs: Long): LyricMoment? {
        val timed = lines.withIndex().filter { it.value.timeMs >= 0 }
        if (timed.isEmpty()) return null
        val k = timed.indexOfLast { it.value.timeMs <= positionMs }
        if (k < 0) return LyricMoment(-1, REST, upcoming(timed[0].value.text), timed[0].value.timeMs)
        val line = timed[k]
        val following = timed.getOrNull(k + 1)?.value
        val end = lineEnd(line.value)
        val nextStart = following?.timeMs
        val breakAt = if (end > 0 && (nextStart == null || nextStart - end >= BREAK_MIN_MS)) end + BREAK_HOLD_MS else null
        if (breakAt != null && (nextStart == null || breakAt < nextStart) && positionMs >= breakAt)
            return LyricMoment(-1, REST, following?.let { upcoming(it.text) }, nextStart)
        val endsAt = if (breakAt != null && (nextStart == null || breakAt < nextStart)) breakAt else nextStart
        return LyricMoment(line.index, shown(line.value.text), following?.let { upcoming(it.text) }, endsAt)
    }

    /** Real time (ms) until lyrics time reaches [endsAt] at [speed], with a little margin so the change has happened. */
    fun wallDelay(endsAt: Long, positionMs: Long, speed: Float): Long {
        val rate = if (speed.isFinite() && speed > .05f) speed else 1f
        return (ceil((endsAt - positionMs).coerceAtLeast(0L) / rate.toDouble()).toLong() + MARGIN_MS).coerceAtLeast(MIN_WAIT_MS)
    }

    const val MARGIN_MS = 20L
    const val MIN_WAIT_MS = 40L
}

/** Spaces out what is sent to Android: a quick run of lines shows the latest one, never a queue of stale ones. */
internal class PostThrottle(private val minIntervalMs: Long) {
    private var lastAt = Long.MIN_VALUE
    /** How long to wait at [now] before the next post. */
    fun waitAt(now: Long): Long = if (lastAt == Long.MIN_VALUE) 0L else (lastAt + minIntervalMs - now).coerceAtLeast(0L)
    fun posted(now: Long) { lastAt = now }
    fun reset() { lastAt = Long.MIN_VALUE }
}

internal object LiveLyrics {
    /** What is shown, and whether the notification wants it ([LiveState.notify]); null line: nothing to show. */
    data class LiveState(val line: LiveLine? = null, val notify: Boolean = false)

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state
    internal fun publish(value: LiveState) { _state.value = value }

    /** Who wants the line: the lock screen notification (the lyrics widgets of 6.4 are gone since 7.4). */
    data class Demand(val notification: Boolean) { val any get() = notification }

    /** Settings or permissions changed: the line is worked out again. */
    private val _demandEpoch = MutableStateFlow(0)
    val demandEpoch: StateFlow<Int> = _demandEpoch
    fun demandChanged() { _demandEpoch.value++ }

    /**
     * The line for these inputs and how long until it changes (null: only an event can change it). No line when the
     * lyrics belong to another title (still loading), are not timed, or the listener paused.
     */
    /** 7.5: with « Prononciation » on and no translation shown, the lock screen gives the line's pronunciation. */
    private fun romanized(lines: List<com.ketvoice.app.data.MusicApi.LyricLine>, text: String): String? {
        if (!com.ketvoice.app.data.AppSettings.lyricsRomanization.value) return null
        val japanese = lines.any { l -> l.text.any { com.ketvoice.app.util.Romanizer.scriptOf(it) == com.ketvoice.app.util.Romanizer.Script.Kana } }
        return com.ketvoice.app.util.Romanizer.line(text, japanese)
    }

    fun lineFor(head: Playhead?, lyrics: LyricsRepo.State, userOffsetMs: Int, stageDelayMs: Int,
                translation: LyricsTranslation.Live?): Pair<LiveLine?, Long?> {
        if (head == null || !head.playWhenReady) return null to null
        if (lyrics.trackId != head.mediaId || !lyrics.isTimed || lyrics.lines.isEmpty()) return null to null
        val position = LyricsOffsets.adjustedPosition(head.positionMs, userOffsetMs - stageDelayMs)
        val moment = LyricTimeline.at(lyrics.lines, position) ?: return null to null
        val translated = translation?.takeIf { it.trackId == head.mediaId }?.lines?.get(moment.index)
            ?.trim()?.takeIf { it.isNotEmpty() && it != moment.current }
            ?: romanized(lyrics.lines, moment.current)
        val line = LiveLine(head.mediaId, head.title, head.artist, moment.index, moment.current, moment.next, translated)
        val wait = moment.endsAt?.takeIf { head.playing }?.let { LyricTimeline.wallDelay(it, position, head.speed) }
        return line to wait
    }
}

/** The inputs that change the line, other than the playhead (read when needed). */
internal data class LyricsInputs(
    val lyrics: LyricsRepo.State,
    val offsets: Map<String, Int>,
    val stageDelayMs: Int,
    val translation: LyricsTranslation.Live?,
    val demandEpoch: Int,
)

/**
 * Runs [LiveLyrics] for the playback service. [poke] after any player event (seek, pause, title, speed, deck
 * swap, screen on); everything else is followed through flows.
 */
internal class LiveLyricsEngine(
    private val scope: CoroutineScope,
    private val playhead: () -> Playhead?,
    private val screenOn: () -> Boolean,
    private val demand: () -> LiveLyrics.Demand,
    private val inputs: kotlinx.coroutines.flow.Flow<LyricsInputs> = defaultInputs(),
) {
    private val pokes = MutableStateFlow(0L)
    private var job: Job? = null
    /** Times the engine woke on its own (for tests: no wake-up while nothing changes). */
    @Volatile var wakeups = 0; private set

    fun poke() { pokes.value++ }

    fun start() {
        if (job != null) return
        job = scope.launch { combine(inputs, pokes) { i, _ -> i }.collectLatest { run(it) } }
    }

    fun stop() {
        job?.cancel(); job = null
        LiveLyrics.publish(LiveLyrics.LiveState())
    }

    private suspend fun run(inputs: LyricsInputs) {
        val wanted = demand()
        if (!wanted.any) { LiveLyrics.publish(LiveLyrics.LiveState()); return }
        val offsetKey = inputs.lyrics.trackId?.let { LyricsOffsets.key(it) }
        val userOffset = offsetKey?.let { inputs.offsets[it] } ?: 0
        while (true) {
            val (line, wait) = LiveLyrics.lineFor(playhead(), inputs.lyrics, userOffset, inputs.stageDelayMs, inputs.translation)
            LiveLyrics.publish(LiveLyrics.LiveState(line, wanted.notification))
            // Screen off: nobody can see the line; the next event or the screen coming on brings it up to date.
            if (wait == null || !screenOn()) return
            delay(wait)
            wakeups++
        }
    }

    companion object {
        fun defaultInputs(): kotlinx.coroutines.flow.Flow<LyricsInputs> = combine(
            LyricsRepo.state, LyricsOffsets.values, com.ketvoice.app.stems.VoiceLevel.delayMs,
            LyricsTranslation.live, LiveLyrics.demandEpoch) { lyrics, offsets, stage, translation, epoch ->
            LyricsInputs(lyrics, offsets, stage, translation, epoch)
        }
    }
}
