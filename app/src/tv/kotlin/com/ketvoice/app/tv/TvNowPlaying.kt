package com.ketvoice.app.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.KetmixLearning
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.rememberKetmixAsk
import kotlinx.coroutines.delay

/** Now Playing's side panel. */
enum class TvPanel { None, Queue }

/**
 * Now Playing, full screen (1.1.0, rebuilt). No video behind anything: the background is a calm field of the cover's
 * colours ([TvColorField]). With synced lyrics, the artwork (the title's Canvas when it has one, else its cover) sits
 * on the left at its own shape with the title, a thin progress line and the controls under it, and the lyrics fill
 * the right side in large type; without lyrics (or with them hidden) the artwork is large and centred, the title
 * under it and the controls along the bottom. The two layouts glide into each other ([TvPlayerLayout]). The queue is
 * a side panel. After a few seconds without a key the controls step back; any key brings them back (that first key
 * only wakes them). While Ketmix blends two titles the pictures blend with it ([TvMixVisual]); after a transition,
 * with « Ketmix apprend » on, Up and Down rate it without moving the focus.
 */
@Composable
fun TvNowPlayingPage() {
    val state by Player.state.collectAsState()
    val current = state.current
    if (current == null) {
        Box(Modifier.fillMaxSize().background(TvDesign.Ink.page), contentAlignment = Alignment.Center) {
            TvEmpty("Rien en lecture", "Choisissez un titre dans l'Accueil, la Bibliothèque ou la Recherche.")
        }
        return
    }
    LaunchedEffect(current.id) { LyricsRepo.load(current) }
    val mix = rememberTvMix()
    val shown = mix.shown(current)
    val artwork = rememberArtwork(shown)
    val awake = remember { TvAwake() }
    val chrome = rememberChrome(awake)
    var panel by remember { mutableStateOf(TvPanel.None) }
    TvIdleTimer(awake, panel == TvPanel.None)
    val playHead = remember { FocusRequester() }
    // The queue closed: once it has let the focus go, the focus goes back to play/pause.
    val queued = remember { booleanArrayOf(false) }
    LaunchedEffect(panel) {
        if (panel == TvPanel.Queue) queued[0] = true
        else if (queued[0]) { delay(32); runCatching { playHead.requestFocus() } }
    }
    val ask = rememberKetmixAsk()
    val split = rememberLyricsSplit(current)
    val fade = Modifier.graphicsLayer { alpha = chrome.value }
    Box(Modifier.fillMaxSize().testTag("tv-now-playing").onPreviewKeyEvent { awake.onKey(it, ask.value.takeIf { panel == TvPanel.None }) }) {
        TvColorField(rememberFieldPalette(shown, mix), Modifier.fillMaxSize())
        TvPlayerStage(
            split = split.value,
            aspect = rememberFrameAspect(artwork, mix),
            art = { TvArtworkFrame(artwork, mix, state.isPlaying) },
            info = { TvNowInfo(shown) },
            ketmix = { TvKetmixLine(mix, ask) },
            progress = { TvProgress(state.durationMs, fade) },
            controls = {
                TvControls(state, current, split.lyrics, split.hasLyrics, fade, playHead, stacked = split.value,
                    onLyrics = split::toggle, onQueue = { panel = TvPanel.Queue })
            },
            lyrics = if (split.composed) ({ TvLyricsPanel(current) }) else null,
        )
        TvQueuePanel(panel == TvPanel.Queue, state, onClose = { panel = TvPanel.None })
    }
    LaunchedEffect(Unit) { delay(16); runCatching { playHead.requestFocus() } }
}

/** Controls step back after this long without a key (never while the queue is shown). */
const val IDLE_MS = 8_000L

/** Whether the controls are shown, and when the last key came. */
internal class TvAwake {
    var awake by mutableStateOf(true)
    var lastKey by mutableLongStateOf(System.currentTimeMillis())

    suspend fun sleepAfterIdle(allowed: Boolean) {
        awake = true
        if (!allowed) return
        delay(IDLE_MS)
        awake = false
    }

    /**
     * A key on the page. While Ketmix asks about the last transition (and nobody rated it yet), Up is 👍 and Down 👎:
     * the focus stays where it is. Asleep, any other first press only brings the controls back.
     */
    fun onKey(event: KeyEvent, ask: KetmixLearning.Transition?): Boolean {
        val sleeping = !awake
        lastKey = System.currentTimeMillis()
        if (event.type != KeyEventType.KeyDown) return false
        if (ask != null && ask.rating == null && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
            KetmixLearning.rate(event.key == Key.DirectionUp)
            return true
        }
        return sleeping
    }
}

/** Puts the controls to sleep after [IDLE_MS] without a key; its own scope, so that a key never recomposes the page. */
@Composable
private fun TvIdleTimer(awake: TvAwake, allowed: Boolean) {
    LaunchedEffect(awake.lastKey, allowed) { awake.sleepAfterIdle(allowed) }
}

/** The controls' opacity: they fade out when [TvAwake] sleeps (read in their layer, never in their composition). */
@Composable
private fun rememberChrome(awake: TvAwake): State<Float> =
    animateFloatAsState(if (awake.awake) 1f else 0f, tween(if (awake.awake) 180 else 900), label = "tv-chrome")

/**
 * Whether the lyrics are shown (the title has synced lyrics and the listener did not hide them) and how far the
 * layout is from the centred one towards the one with lyrics, 0..1, animated.
 */
internal class TvLyricsSplit(private val wanted: MutableState<Boolean>, private val has: State<Boolean>, private val animated: State<Float>) {
    val hasLyrics: Boolean get() = has.value
    val lyrics: Boolean get() = has.value && wanted.value
    val value: () -> Float = { animated.value }
    /** The lyrics stay composed while they show or glide out (derived: the page does not follow the glide's frames). */
    private val composedState = derivedStateOf { lyrics || animated.value > .001f }
    val composed: Boolean get() = composedState.value

    fun toggle() { wanted.value = !wanted.value }
}

@Composable
private fun rememberLyricsSplit(track: Track): TvLyricsSplit {
    val wanted = remember { mutableStateOf(true) }
    val lyrics by LyricsRepo.state.collectAsState()
    val has = AppSettings.syncedLyrics.value && lyrics.trackId == track.id && lyrics.lines.isNotEmpty()
    val animated = animateFloatAsState(if (has && wanted.value) 1f else 0f,
        if (Motion.reduced) snap() else spring(dampingRatio = .9f, stiffness = 140f), label = "np-split")
    val hasState = rememberUpdatedState(has)
    return remember { TvLyricsSplit(wanted, hasState, animated) }
}

/**
 * The Ketmix mix being heard, as Now Playing draws it: its progress (read while drawing), where the pictures change
 * hands, and the two titles. [shown] is the outgoing title until the swap, then the incoming one.
 */
internal class TvMix(val ui: Automix.Ui, val progress: State<Float>) {
    val mixing: Boolean get() = ui.phase == Automix.Phase.Mixing && ui.from != null && ui.to != null
    val swap: Float = TvMixVisual.swapPoint(ui.swapAt)
    private val swappedState = derivedStateOf { TvMixVisual.swapped(progress.value, swap) }

    fun shown(current: Track): Track = if (!mixing) current else if (swappedState.value) ui.to!! else ui.from!!

    /** The incoming title while its artwork is still coming in, else null. */
    fun incoming(): Track? = if (mixing && !swappedState.value) ui.to else null

    fun artIn(): Float = TvMixVisual.artIn(progress.value, swap)
    fun fieldIn(): Float = if (mixing) TvMixVisual.fieldIn(progress.value) else 0f
}

/** The mix's progress, 0..1, each frame while it is heard (the host tests' infinite-animation policy stops the loop). */
@Composable
private fun rememberTvMix(): TvMix {
    val ui by Automix.ui.collectAsState()
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ui.phase, ui.startedAt, ui.durationMs) {
        if (ui.phase != Automix.Phase.Mixing) { progress.floatValue = 0f; return@LaunchedEffect }
        progress.floatValue = mixProgress(ui)
        while (true) androidx.compose.animation.core.withInfiniteAnimationFrameMillis { progress.floatValue = mixProgress(ui) }
    }
    return remember(ui) { TvMix(ui, progress) }
}

private fun mixProgress(ui: Automix.Ui): Float =
    com.ketvoice.app.ui.player.MixVisuals.progress(android.os.SystemClock.elapsedRealtime(), ui.startedAt, ui.durationMs)

/**
 * The tones the field draws now: the shown title's, blending from the previous title's over a second when the title
 * changes, and along the whole mix from the outgoing title's to the incoming one's while Ketmix blends.
 */
@Composable
private fun rememberFieldPalette(shown: Track, mix: TvMix): () -> TvPalette {
    val mine by rememberTvPalette(rememberCover(shown))
    val out by rememberTvPalette(rememberCover(if (mix.mixing) mix.ui.from else null))
    val into by rememberTvPalette(rememberCover(if (mix.mixing) mix.ui.to else null))
    val from = remember { mutableStateOf(mine) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(mine, mix.mixing) {
        // Inside a mix the field follows the mix; when it ends, it already shows the incoming title's tones.
        if (mix.mixing || from.value == mine) { from.value = mine; fade.snapTo(1f); return@LaunchedEffect }
        fade.snapTo(0f)
        fade.animateTo(1f, tween(Motion.duration(1_000)))
        from.value = mine
    }
    return { if (mix.mixing) out.lerp(into, mix.fieldIn()) else from.value.lerp(mine, fade.value) }
}

/** The cover of [track] as the phone shows it (a found album cover, a listener's override, else its own). */
@Composable
internal fun rememberCover(track: Track?): String? {
    val covers by com.ketvoice.app.data.ArtworkRepo.covers.collectAsState()
    return remember(track?.id, covers) { track?.let { com.ketvoice.app.data.MediaOverrides.cover(it, covers["t:" + it.id] ?: it.coverUrl) } }
}
