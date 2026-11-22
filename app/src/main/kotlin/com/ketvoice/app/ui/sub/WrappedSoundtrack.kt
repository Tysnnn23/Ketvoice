package com.ketvoice.app.ui.sub

import android.content.Context
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * « Bande-son du récap annuel » (8.4): which title each page of Wrapped plays, from where, and how one clip passes
 * into the next. Pure, so the host tests read it as it is.
 */
internal object RecapClips {
    /** How long a crossfade from one page's clip to the next lasts. */
    const val FADE_MS = 1_200L
    /** A clip is long enough for a page and a fade; a page held longer (the last one) simply goes on playing. */
    const val CLIP_MS = WrappedPages.PAGE_MS + FADE_MS

    /**
     * The title of each page: the most played one opens and closes the story, the artist's page plays the artist's
     * most played title, the others take the next titles of the top list in turn, so that the story is a medley.
     */
    fun forPages(pages: List<WrappedPage>, recap: Wrapped.Recap): List<Track?> {
        val top = recap.tracks.map { it.first }.distinctBy { it.id }
        if (top.isEmpty()) return pages.map { null }
        var next = 1
        fun following(): Track = top[(next++).mod(top.size)]
        return pages.map { page ->
            when (page) {
                WrappedPage.Intro, WrappedPage.Summary, WrappedPage.Tracks -> top.first()
                WrappedPage.Artist -> recap.topArtist?.name?.let { name -> top.firstOrNull { it.artist.split(',').any { a -> a.trim().equals(name, true) } } } ?: top.first()
                WrappedPage.Albums -> recap.albums.firstOrNull()?.let { album -> top.firstOrNull { it.album.equals(album.title, true) } } ?: following()
                WrappedPage.Discoveries -> recap.newArtists.firstNotNullOfOrNull { name -> top.firstOrNull { it.artist.equals(name, true) } } ?: following()
                else -> following()
            }
        }
    }

    /**
     * Where a title's best part starts: its chorus when synced lyrics are known (the line sung most often, at its
     * occurrence nearest to 40 % of the title, a second before it), otherwise a third of the way in. Always leaves a
     * whole clip before the end.
     */
    fun startMs(durationMs: Long, lines: List<MusicApi.LyricLine>?, clipMs: Long = CLIP_MS): Long {
        if (durationMs <= 0L) return 45_000L
        val latest = (durationMs - clipMs - 2_000L).coerceAtLeast(0L)
        val chorus = lines?.let { chorusMs(it, durationMs) }
        val start = chorus?.minus(1_000L) ?: (durationMs * 0.33).toLong()
        return start.coerceIn(0L, latest)
    }

    private fun chorusMs(lines: List<MusicApi.LyricLine>, durationMs: Long): Long? {
        val timed = lines.filter { it.timeMs >= 0 && it.text.isNotBlank() }
        if (timed.size < 4) return null
        val byText = timed.groupBy { it.text.lowercase().filter(Char::isLetterOrDigit) }.filterKeys { it.length >= 6 }
        val repeated = byText.values.filter { it.size >= 2 }.maxByOrNull { it.size } ?: return null
        val aim = durationMs * 0.4
        return repeated.minBy { kotlin.math.abs(it.timeMs - aim) }.timeMs
    }

    /** The volumes of the clip that leaves and the one that comes, [elapsedMs] into a fade: equal power, no dip. */
    fun crossfade(elapsedMs: Long, fadeMs: Long = FADE_MS): Pair<Float, Float> {
        val t = if (fadeMs <= 0L) 1f else (elapsedMs.toFloat() / fadeMs).coerceIn(0f, 1f)
        return cos(t * PI / 2).toFloat() to sin(t * PI / 2).toFloat()
    }
}

/** One player of clips, outside the playback service: nothing it plays reaches the queue, history, stats, scrobbling or Ketmix. */
internal interface ClipDeck {
    /** Starts [track] at [startMs], at [volume]. */
    fun play(track: Track, startMs: Long, volume: Float)
    fun volume(value: Float)
    fun stop()
    fun release()
}

/** What the soundtrack needs of the listener's own playback. */
internal interface ListenerPlayback {
    val playing: Boolean
    fun pause()
    fun resume()
}

/**
 * The soundtrack of one Wrapped story: the page shown plays its clip, and moving to another page crossfades into
 * that page's clip (a page with the same title goes on). The listener's own music is paused while the story sounds
 * and played again when it closes or is muted, only if it was playing. Visuals follow reduced motion; the sound
 * does not need to.
 */
internal class RecapSoundtrack(
    private val scope: CoroutineScope,
    private val clips: List<Track?>,
    private val listener: ListenerPlayback,
    private val newDeck: () -> ClipDeck,
    private val bestStart: suspend (Track) -> Long,
    private val step: Long = 40L,
    private val fadeMs: Long = RecapClips.FADE_MS,
) {
    private val decks = arrayOfNulls<ClipDeck>(2)
    private var heard = 0
    private var sounding = false
    private var playingId: String? = null
    private var page = 0
    private var fade: Job? = null
    private var pausedListener = false
    var muted = false
        private set
    private var closed = false

    private fun deck(index: Int): ClipDeck = decks[index] ?: newDeck().also { decks[index] = it }

    /** The story is on screen, at [page]. */
    fun open(page: Int, muted: Boolean) {
        this.page = page
        this.muted = muted
        if (!muted) sound()
    }

    /** The story moved to [index]. */
    fun show(index: Int) {
        page = index
        if (!muted && !closed) sound()
    }

    fun mute(on: Boolean) {
        if (closed || on == muted) return
        muted = on
        if (on) { silence(); giveBack() } else sound()
    }

    /** The story closed: the decks go, the listener's music comes back if the story paused it. */
    fun close() {
        if (closed) return
        closed = true
        silence()
        decks.forEachIndexed { i, d -> runCatching { d?.release() }; decks[i] = null }
        giveBack()
    }

    private fun silence() {
        fade?.cancel(); fade = null
        decks.forEach { runCatching { it?.stop() } }
        playingId = null; sounding = false
    }

    private fun giveBack() {
        if (pausedListener) { pausedListener = false; if (!listener.playing) listener.resume() }
    }

    private fun sound() {
        val track = clips.getOrNull(page) ?: return
        if (listener.playing) { listener.pause(); pausedListener = true }
        if (track.id == playingId) return
        playingId = track.id
        fade?.cancel()
        // The deck heard now fades out; when a fade was cut short, the other one is taken over for the new clip.
        val leaving = if (sounding) heard else -1
        val coming = if (leaving >= 0) 1 - leaving else heard
        fade = scope.launch {
            val start = runCatching { bestStart(track) }.getOrDefault(45_000L)
            if (closed || muted || playingId != track.id) return@launch
            deck(coming).play(track, start, if (leaving >= 0) 0f else 1f)
            heard = coming; sounding = true
            if (leaving < 0) return@launch
            var elapsed = 0L
            while (elapsed < fadeMs) {
                val (out, into) = RecapClips.crossfade(elapsed, fadeMs)
                decks[leaving]?.volume(out); decks[coming]?.volume(into)
                delay(step); elapsed += step
            }
            decks[coming]?.volume(1f)
            decks[leaving]?.stop()
        }
    }
}

/**
 * The clips' player on the phone: an ExoPlayer of its own, fed like a Ketmix preview deck (download, cache or the
 * usual sources) but never handed to the playback service, so nothing is counted, scrobbled or queued. It does not
 * take the audio focus: the story pauses the listener's music itself.
 */
internal class ExoClipDeck(private val context: Context, private val scope: CoroutineScope) : ClipDeck {
    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private var job: Job? = null

    private fun player(): androidx.media3.exoplayer.ExoPlayer = exo ?: androidx.media3.exoplayer.ExoPlayer.Builder(context)
        .setMediaSourceFactory(com.ketvoice.app.player.playbackMediaSourceFactory(context))
        .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build().also { exo = it }

    override fun play(track: Track, startMs: Long, volume: Float) {
        job?.cancel()
        job = scope.launch {
            // Resolved off the main thread (it may ask the network), like a Ketmix preview deck.
            val source = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.ketvoice.app.player.Player.resolveDeckSource(track) }.getOrNull()
            } ?: return@launch
            val item = com.ketvoice.app.player.playbackMediaItem(track, source.url, source.cacheKey, source.stream, source.offlineDash)
            player().apply { this.volume = volume; setMediaItem(item, startMs); prepare(); playWhenReady = true }
        }
    }

    override fun volume(value: Float) { exo?.volume = value.coerceIn(0f, 1f) }
    override fun stop() { job?.cancel(); runCatching { exo?.stop() } }
    override fun release() { job?.cancel(); runCatching { exo?.release() }; exo = null }
}

/** The listener's own playback, as the soundtrack pauses and resumes it. */
internal object ListenerPlayer : ListenerPlayback {
    override val playing: Boolean get() = com.ketvoice.app.player.Player.state.value.playWhenReady
    override fun pause() = com.ketvoice.app.player.Player.pause()
    override fun resume() { if (!com.ketvoice.app.player.Player.state.value.playWhenReady) com.ketvoice.app.player.Player.toggle() }
}

/**
 * Where a title's clip starts: from its synced lyrics when they are at hand (a downloaded title's own, or LRCLIB
 * within a few seconds), else [RecapClips.startMs]'s fallback. Nothing of it is counted anywhere.
 */
internal suspend fun recapClipStart(track: Track): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val lines = runCatching { com.ketvoice.app.data.Downloader.localLyrics(track.id)?.second }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { MusicApi.lyrics(track, source = MusicApi.SRC_LRCLIB).second }.getOrNull() }
    RecapClips.startMs(track.durationMs, lines)
}
