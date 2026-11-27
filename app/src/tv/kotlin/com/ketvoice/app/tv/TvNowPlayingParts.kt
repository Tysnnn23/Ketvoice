package com.ketvoice.app.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.ketvoice.app.automix.KetmixLearning
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.AutomixGlyph
import com.ketvoice.app.ui.player.KetmixMixingBadge
import com.ketvoice.app.ui.player.LyricsPane
import kotlinx.coroutines.delay

private const val ART = "art"
private const val INFO = "info"
private const val KETMIX = "ketmix"
private const val PROGRESS = "progress"
private const val CONTROLS = "controls"
private const val LYRICS = "lyrics"

/**
 * Places Now Playing's blocks where [TvPlayerLayout] puts them for the current [split] and frame [aspect], both read
 * while laying out: the glide between the two layouts moves the blocks without composing anything again. The title
 * block is laid out at its large size and drawn smaller in the column (its layer scales, its text is not laid out
 * again); the lyrics come in from the right as the column forms.
 */
@Composable
internal fun TvPlayerStage(split: () -> Float, aspect: () -> Float, art: @Composable () -> Unit, info: @Composable () -> Unit,
                  ketmix: @Composable () -> Unit, progress: @Composable () -> Unit, controls: @Composable () -> Unit,
                  lyrics: (@Composable () -> Unit)?) {
    Layout(content = {
        Box(Modifier.layoutId(ART)) { art() }
        Box(Modifier.layoutId(INFO)) { info() }
        Box(Modifier.layoutId(KETMIX)) { ketmix() }
        Box(Modifier.layoutId(PROGRESS)) { progress() }
        Box(Modifier.layoutId(CONTROLS)) { controls() }
        if (lyrics != null) Box(Modifier.layoutId(LYRICS)) { lyrics() }
    }, modifier = Modifier.fillMaxSize().testTag("np-stage")) { measurables, constraints ->
        val f = TvPlayerLayout.at(split(), constraints.maxWidth.toDp().value, constraints.maxHeight.toDp().value, aspect())
        val placeables = measurables.map { m ->
            val id = m.layoutId as String
            val r = rectOf(id, f)
            val scale = if (id == INFO) f.titleScale else 1f
            Triple(id, r, m.measure(Constraints.fixed((r.w / scale).dp.roundToPx().coerceAtLeast(0), (r.h / scale).dp.roundToPx().coerceAtLeast(0))))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { (id, r, p) ->
                when (id) {
                    INFO -> p.placeWithLayer(r.centerX.dp.roundToPx() - p.width / 2, r.y.dp.roundToPx()) {
                        scaleX = f.titleScale; scaleY = f.titleScale; transformOrigin = TransformOrigin(.5f, 0f)
                    }
                    LYRICS -> p.placeWithLayer(r.x.dp.roundToPx(), r.y.dp.roundToPx()) {
                        alpha = f.stacked; translationX = (1f - f.stacked) * 48.dp.toPx()
                    }
                    else -> p.place(r.x.dp.roundToPx(), r.y.dp.roundToPx())
                }
            }
        }
    }
}

private fun rectOf(id: String, f: TvPlayerLayout.Frame): TvRect = when (id) {
    ART -> f.art
    INFO -> f.info
    KETMIX -> f.ketmix
    PROGRESS -> f.progress
    CONTROLS -> f.controls
    else -> f.lyrics
}

/** What the frame shows for a title: its cover, its Canvas when it has one, and the shape of each once known. */
internal class TvArtwork(val track: Track, val cover: String?, val canvas: String?) {
    var coverAspect by mutableFloatStateOf(1f)
    var canvasAspect by mutableStateOf<Float?>(null)
    var canvasFailed by mutableStateOf(false)
    val showsCanvas: Boolean get() = canvas != null && !canvasFailed

    /** The frame's shape: the Canvas's once it is known, else the cover's. */
    fun target(): Float = TvPlayerLayout.aspect(if (showsCanvas) canvasAspect ?: coverAspect else coverAspect)
}

@Composable
internal fun rememberArtwork(track: Track): TvArtwork {
    val cover = rememberCover(track)
    val canvas = rememberCanvasPath(track)
    val artwork = remember(track.id, cover, canvas) { TvArtwork(track, cover, canvas) }
    // The Canvas's shape is read from the file before it plays, so that the frame takes it at once.
    val probed by com.ketvoice.app.ui.player.rememberCanvasAspectRatio(canvas)
    LaunchedEffect(artwork, probed) { probed?.let { if (artwork.canvasAspect == null) artwork.canvasAspect = it } }
    return artwork
}

/** The title's Canvas when the listener keeps « Pochettes animées » on and one exists (fetched as on the phone). */
@Composable
private fun rememberCanvasPath(track: Track): String? {
    val paths by CanvasRepo.paths.collectAsState()
    val excluded by CanvasRepo.excluded.collectAsState()
    LaunchedEffect(track.id) { if (AppSettings.animatedArtwork.value) runCatching { CanvasRepo.resolve(track) } }
    return paths[track.id]?.takeIf { AppSettings.animatedArtwork.value && track.id !in excluded && !MediaOverrides.canvasHiddenInPlayer(track) }
}

/**
 * The frame's shape now, read while laying out: it glides to a new shape (a Canvas found after its cover), and during
 * a mix it follows the incoming artwork as it comes in.
 */
@Composable
internal fun rememberFrameAspect(artwork: TvArtwork, mix: TvMix): () -> Float {
    val target = artwork.target()
    val animated = remember { Animatable(target) }
    LaunchedEffect(artwork, target) {
        if (mix.mixing || Motion.reduced) animated.snapTo(target)
        else animated.animateTo(target, spring(dampingRatio = .9f, stiffness = 220f))
    }
    val latest by rememberUpdatedState(mix)
    return remember {
        {
            val base = animated.value
            // Only while an incoming cover of another shape comes in does the mix move the frame.
            if (base == 1f || latest.incoming() == null) base else TvRect.mix(base, 1f, latest.artIn())
        }
    }
}

/** The Canvas inside the frame (a test seam: host renders cannot decode video). */
internal object TvCanvasSlot {
    var content: @Composable (path: String, playing: Boolean, modifier: Modifier, onAspect: (Float) -> Unit, onFailure: () -> Unit) -> Unit =
        { path, playing, modifier, onAspect, onFailure ->
            // Hardware decoding, paused while the TV's screen does not show the app (CanvasVideo's own lifecycle rule).
            com.ketvoice.app.ui.player.CanvasVideo(path, playing, cover = null, modifier = modifier, onFailure = onFailure, onAspect = onAspect)
        }
}

/**
 * The artwork in its rounded frame: the cover, the Canvas over it once a frame of it is drawn (only here, never
 * behind the page), and during a mix the incoming title's cover fading in over both until the bass swap.
 */
@Composable
internal fun TvArtworkFrame(artwork: TvArtwork, mix: TvMix, playing: Boolean) {
    val shadow = tvTier().focusShadow
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxSize().graphicsLayer {
        if (shadow) shadowElevation = 26.dp.toPx()
        this.shape = shape; clip = true
    }.background(Color.White.copy(alpha = .06f)).testTag("np-artwork")) {
        Crossfade(artwork.cover, animationSpec = tween(Motion.duration(320)), label = "np-cover") { url ->
            FrameCover(url, onAspect = { artwork.coverAspect = it })
        }
        val canvas = artwork.canvas
        if (canvas != null && artwork.showsCanvas) TvCanvasSlot.content(canvas, playing, Modifier.fillMaxSize().testTag("np-canvas"),
            { artwork.canvasAspect = it }, { artwork.canvasFailed = true })
        val incoming = mix.incoming()
        if (incoming != null) FrameCover(rememberCover(incoming), Modifier.graphicsLayer { alpha = mix.artIn() }.testTag("np-artwork-incoming"))
    }
}

/** The frame's covers are decoded once at the largest size the frame takes, whatever the layout. */
private const val FRAME_DECODE_DP = 300f

@Composable
private fun FrameCover(url: String?, modifier: Modifier = Modifier, onAspect: ((Float) -> Unit)? = null) {
    val context = LocalContext.current
    val px = TvImages.decodePx(FRAME_DECODE_DP, LocalDensity.current.density)
    val light = tvTier() == TvTier.Light
    val request = remember(url, px, light) {
        // No image-loader cross-fade: the frame cross-fades its covers itself, on the frame clock.
        url?.let { ImageRequest.Builder(context).data(it).size(px).precision(Precision.INEXACT).allowRgb565(light).crossfade(false).build() }
    }
    if (request == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(KetIcons.Music, null, Modifier.fillMaxSize(.3f), tint = Color.White.copy(alpha = .45f))
        }
        return
    }
    AsyncImage(request, null, modifier.fillMaxSize(), contentScale = ContentScale.Crop, onSuccess = { s ->
        val size = s.painter.intrinsicSize
        if (onAspect != null && size.isSpecified && size.width > 0f && size.height > 0f) onAspect(size.width / size.height)
    })
}

/** The title and artist, large and centred; they cross-fade when the title changes (on the bass swap during a mix). */
@Composable
internal fun TvNowInfo(track: Track) {
    Crossfade(targetState = track, animationSpec = tween(Motion.duration(320)), label = "np-info") { t ->
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t.title, localize = false, style = TvDesign.Type.display, color = TvDesign.Ink.primary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.testTag("np-title"))
            Spacer(Modifier.height(4.dp))
            Text(t.artist, localize = false, style = TvDesign.Type.lead, color = TvDesign.Ink.secondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.testTag("np-artist"))
        }
    }
}

/** Ketmix's line: its sign while it mixes, then for a few seconds after a transition the 👍/👎 prompt. */
@Composable
internal fun TvKetmixLine(mix: TvMix, ask: State<KetmixLearning.Transition?>) {
    val t = ask.value
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            t != null -> TvRatePrompt(t)
            mix.mixing -> TvMixBadge(mix)
        }
    }
}

@Composable
private fun TvMixBadge(mix: TvMix) {
    // Twenty steps across a mix: the badge's rings follow it without composing again at every frame.
    val step by remember(mix) { derivedStateOf { (mix.progress.value * 20f).toInt() / 20f } }
    KetmixMixingBadge(step, Color.White, Modifier.graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
}

/**
 * « Cette transition vous a plu ? » with the remote's answer under it: Up 👍, Down 👎 (the focus does not move, see
 * [TvAwake.onKey]); then the thanks with the chosen thumb.
 */
@Composable
private fun TvRatePrompt(t: KetmixLearning.Transition) {
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    Column(Modifier.testTag("np-rate"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutomixGlyph(Color.White, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (t.rating == null) "Cette transition vous a plu ?" else "Merci, Ketmix s'en souviendra", style = TvDesign.Type.caption,
                color = TvDesign.Ink.primary, maxLines = 1)
            t.rating?.let { up ->
                Spacer(Modifier.width(8.dp))
                Icon(if (up) Ket27Glyphs.ThumbUp else Ket27Glyphs.ThumbDown, null, Modifier.size(16.dp).testTag(if (up) "np-rated-up" else "np-rated-down"),
                    tint = accent)
            }
        }
        if (t.rating == null) Text("↑ J'ai aimé · ↓ Je n'ai pas aimé", style = TvDesign.Type.caption, color = TvDesign.Ink.secondary,
            maxLines = 1, modifier = Modifier.testTag("np-rate-hint"))
    }
}

/** The progress line and the times. The position is read while drawing: the page never recomposes with it. */
@Composable
internal fun TvProgress(durationMs: Long, modifier: Modifier) {
    val position = Player.positionMs.collectAsState()
    Column(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxWidth().height(4.dp).testTag("np-progress")) {
            val fraction = if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = .22f), cornerRadius = r)
            drawRoundRect(Color.White.copy(alpha = .92f), size = Size(size.width * fraction, size.height), cornerRadius = r)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            TimeText({ TvTime.format(position.value) })
            Spacer(Modifier.weight(1f))
            TimeText({ "-" + TvTime.format((durationMs - position.value).coerceAtLeast(0)) })
        }
    }
}

/** A time that changes every second recomposes only itself. */
@Composable
private fun TimeText(text: () -> String) {
    Text(text(), localize = false, style = TvDesign.Type.caption, color = TvDesign.Ink.secondary)
}

/** The sizes (dp) of the controls, in [TvPlayerLayout.controls]'s order. */
private val CONTROL_SIZES = floatArrayOf(44f, 44f, 44f, 52f, 64f, 52f, 44f, 44f, 44f)

/**
 * The controls: like and Ketmix, the transport (shuffle, previous, play/pause, next, repeat), lyrics and queue. One
 * bar along the bottom in the centred layout, two rows under the artwork in the column; [stacked] is read while
 * laying out. Play/pause takes the focus first, and entering the controls lands on it.
 */
@Composable
internal fun TvControls(state: PlayerUiState, track: Track, lyricsShown: Boolean, hasLyrics: Boolean, modifier: Modifier,
                        playHead: FocusRequester, stacked: () -> Float, onLyrics: () -> Unit, onQueue: () -> Unit) {
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    val favorite = track.id in AppSettings.favoriteIds.value
    val sizes = CONTROL_SIZES
    Layout(content = {
        TvIconButton(if (favorite) Ket27Glyphs.HeartFilled else Ket27Glyphs.Heart, if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
            { com.ketvoice.app.ui.player.toggleFavorite(track) }, size = sizes[0].dp, active = favorite, accent = accent, tag = "np-like")
        KetmixToggle(sizes[1])
        TvIconButton(KetIcons.Shuffle, "Aléatoire", { Player.setShuffle(!state.shuffle) }, size = sizes[2].dp, active = state.shuffle,
            accent = accent, tag = "np-shuffle")
        TvIconButton(Icons.Filled.SkipPrevious, "Précédent", Player::previous, size = sizes[3].dp, tag = "np-previous")
        TvIconButton(if (state.playWhenReady) KetIcons.Pause else KetIcons.Play, if (state.playWhenReady) "Pause" else "Lecture",
            Player::toggle, Modifier.focusRequester(playHead), size = sizes[4].dp, tag = "np-play")
        TvIconButton(Icons.Filled.SkipNext, "Suivant", Player::next, size = sizes[5].dp, tag = "np-next")
        TvIconButton(if (state.repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Répéter", Player::cycleRepeat, size = sizes[6].dp,
            active = state.repeatOne || state.repeatAll, accent = accent, tag = "np-repeat")
        TvIconButton(KetIcons.Lyrics, "Paroles", onLyrics, Modifier.graphicsLayer { alpha = if (hasLyrics) 1f else .45f }, size = sizes[7].dp,
            active = lyricsShown, accent = accent, tag = "np-lyrics-toggle")
        TvIconButton(KetIcons.Playlist, "File d'attente", onQueue, size = sizes[8].dp, tag = "np-queue-open")
    }, modifier = modifier.fillMaxSize().focusProperties { onEnter = { runCatching { playHead.requestFocus() } } }.focusGroup()) { measurables, c ->
        val spots = TvPlayerLayout.controls(sizes, c.maxWidth.toDp().value, stacked())
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(c.maxWidth, c.maxHeight) {
            placeables.forEachIndexed { i, p -> p.place(spots[i].first.dp.roundToPx(), spots[i].second.dp.roundToPx()) }
        }
    }
}

/** Ketmix on or off, with its own two-ring sign (the same setting as the phone's switch, synced with the account). */
@Composable
private fun KetmixToggle(size: Float) {
    val on = AppSettings.automix.value
    TvRoundButton(if (on) "Ketmix activé" else "Ketmix", {
        AppSettings.automix.value = !on
        SettingsStore.save()
    }, size = size.dp, active = on, tag = "np-ketmix") { ink -> AutomixGlyph(if (on && ink == TvDesign.Ink.primary) com.ketvoice.app.theme.LocalTheme.current.accent else ink, Modifier.size((size * .5f).dp)) }
}

/** The lyrics are this much larger than the phone's (seen from the sofa), on top of « Taille des paroles ». */
private const val TV_LYRICS_SCALE = 1.3f

/**
 * The phone's Cascade lyrics, large: the sung line bright, the others dimmed (never blurred on TV, see
 * [com.ketvoice.app.ui.player.LyricsBlur.deviceSupported]). The lines are not focus stops on TV (the remote stays on
 * the controls); they follow the song by themselves.
 */
@Composable
internal fun TvLyricsPanel(track: Track) {
    val fade = tvTier() != TvTier.Light
    Box(Modifier.fillMaxSize().focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()
        // The lines leaving at the top fade out instead of being cut (one offscreen layer; light TVs keep the plain edge).
        .then(if (fade) Modifier.topFade() else Modifier)
        .testTag("np-lyrics")) {
        LyricsPane(track, expanded = true, onSource = {}, modifier = Modifier.fillMaxSize(),
            fontScale = TV_LYRICS_SCALE * com.ketvoice.app.ui.quest.QuestLayout.lyricsScale(AppSettings.lyricsTextSize.value))
    }
}

/** Fades what is drawn at the top edge (the lyrics' lines scrolling away) through an offscreen layer. */
private fun Modifier.topFade(): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .14f to Color.Black, 1f to Color.Black),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }

/**
 * The queue, a panel that slides in from the right over the page (the page stays as it is under it). The remote walks
 * it; the select key jumps to a title; Back closes it and gives the focus back to the controls.
 */
@Composable
internal fun TvQueuePanel(open: Boolean, state: PlayerUiState, onClose: () -> Unit) {
    val presence = animateFloatAsState(if (open) 1f else 0f, if (Motion.reduced) snap() else spring(dampingRatio = .9f, stiffness = 420f),
        label = "np-queue")
    val visible by remember { derivedStateOf { presence.value > .001f } }
    if (!open && !visible) return
    BackHandler(enabled = open, onBack = onClose)
    val first = remember { FocusRequester() }
    // Opened, the queue takes the focus (Now Playing gives it back to the controls when it closes).
    LaunchedEffect(open) { if (open) { delay(32); runCatching { first.requestFocus() } } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = presence.value }
            .background(Brush.horizontalGradient(0f to Color.Transparent, .55f to Color.Black.copy(alpha = .3f), 1f to Color.Black.copy(alpha = .55f))))
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(QUEUE_W.dp)
            .graphicsLayer { translationX = (1f - presence.value) * (QUEUE_W + 24f).dp.toPx() }
            .background(Color(0xFF131318))
            .focusProperties { onExit = { if (open) cancelFocusChange() } }.focusGroup()
            .padding(start = 28.dp, end = TvDesign.Safe.horizontal, top = TvDesign.Safe.vertical).testTag("np-queue")) {
            Text("À suivre", style = TvDesign.Type.title, color = TvDesign.Ink.primary, modifier = Modifier.padding(bottom = 14.dp))
            TvQueueList(state, first)
        }
    }
}

private const val QUEUE_W = 440f

@Composable
private fun TvQueueList(state: PlayerUiState, first: FocusRequester) {
    val library = rememberTvLibrary()
    val upcoming = state.queue.withIndex().drop((state.index + 1).coerceAtLeast(0)).take(80)
    if (upcoming.isEmpty()) { Text("La file d'attente est vide", style = TvDesign.Type.body, color = TvDesign.Ink.secondary); return }
    TvPivot(pivot = .2f) {
        LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(upcoming, key = { _, entry -> state.queueEntryIds.getOrNull(entry.index) ?: "${entry.value.id}:${entry.index}" }) { i, entry ->
                TvTrackRow(i, entry.value, library.cover(entry.value), false, if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { Player.jumpTo(entry.index) }, onMenu = {})
            }
        }
    }
}
