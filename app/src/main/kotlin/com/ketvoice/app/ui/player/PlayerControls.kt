@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.ui.player

import com.ketvoice.app.ui.adaptive.hoverHighlight
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import com.ketvoice.app.ui.sub.framedPicture
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.ketvoice.app.R
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.gradientFor
import com.ketvoice.app.ui.quest.questPointer
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.symbolPulse
import com.ketvoice.app.ui.motion.artworkDepth
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag

private val noRipple = MutableInteractionSource()

@Composable
fun ArtworkCanvas(
    track: Track,
    canvasPath: String?,
    playing: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = 0.dp,
    badge: Boolean = false,
    depth: Boolean = false,
    forceCanvas: Boolean = false,
    onCanvasFailure: () -> Unit = {},
    /** Told the width over height of the picture or video once it is known. */
    onMediaAspect: ((Float) -> Unit)? = null,
    /** The rectangle of the picture or video to show, filling the artwork; null shows it as it comes. */
    frame: com.ketvoice.app.data.Frame? = null,
    /** 7.6: shows a Canvas whole inside these insets (what buttons and words cover), see [CanvasFit]; null fills the box. */
    fit: androidx.compose.foundation.layout.PaddingValues? = null,
    /** The cover's colour, under a fitted Canvas until its still is there. */
    ambient: Color = Color(0xFF1C1C1E)
) {
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl)
    val gradient = remember(track.id) { gradientFor(track.id) }
    data class Art(val id: String, val cover: String?, val canvas: String?)

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            // In the player the scene shows through while one cover steps back for the next (6.2).
            .then(if (depth) Modifier else Modifier.background(Brush.linearGradient(gradient)))
    ) {
        AnimatedContent(
            modifier = Modifier.fillMaxSize().then(if (depth && canvasPath == null) Modifier.artworkDepth() else Modifier),
            targetState = Art(track.id, cover, canvasPath),

            contentKey = { if (it.canvas != null) "${it.id}:video:${it.canvas}" else "${it.id}:art:${it.cover.orEmpty()}" },
            transitionSpec = {
                // In the player (6.2), a new title comes in the way Apple Music does it; elsewhere, and for a cover
                // that only finished loading, a plain cross-fade.
                if (depth && initialState.id != targetState.id)
                    trackChange(TrackChange.direction(Player.state.value.queue.map { it.id }, initialState.id, targetState.id))
                else fadeIn(tween(Motion.duration())).togetherWith(fadeOut(tween(Motion.duration())))
            },
            label = "artwork"
        ) { data ->
            val video = data.canvas != null && (forceCanvas || AppSettings.animatedArtwork.value)
            // Width over height of what is shown, once known: a Canvas is upright and a cover square until then.
            var aspect by remember(data) { mutableFloatStateOf(0f) }
            val measured: (Float) -> Unit = { value -> if (value.isFinite() && value > 0f) aspect = value; onMediaAspect?.invoke(value) }
            // Framed: the whole picture is laid out at its own shape, then moved so that the rectangle fills the artwork.
            val placed = if (frame == null) Modifier.fillMaxSize()
                else Modifier.fillMaxSize().framedPicture(frame, if (aspect > 0f) aspect else if (video) 9f / 16f else 1f)
            Box(Modifier.fillMaxSize().then(if (frame != null) Modifier.clipToBounds() else Modifier)) {
                when {
                    // 7.6: the owner's framing wins; otherwise the whole Canvas, placed by CanvasFit.
                    video && frame == null && fit != null -> FittedCanvas(data.canvas!!, playing, ambient, Modifier.fillMaxSize(),
                        fit, onCanvasFailure, measured)
                    video -> run {
                        // Match the banner-era player: edge-to-edge video in the
                        // artwork region, blending into the separate blurred scene.
                        // FIT introduced the unwanted side bars in normal mode.
                        Box(placed) {
                            // 7.5 motion75: the Canvas's still shows at once while its video starts (no empty frame).
                            com.ketvoice.app.ui.sub.rememberCanvasPoster(data.canvas)?.let { still ->
                                androidx.compose.foundation.Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            CanvasVideo(path = data.canvas!!, playing = playing, cover = data.cover,
                                modifier = Modifier.fillMaxSize(),
                                resizeMode = CanvasPresentation.NORMAL_RESIZE_MODE,
                                onFailure = onCanvasFailure, onAspect = measured)
                        }
                    }
                    data.cover != null ->
                        run {
                            AsyncImage(
                                model = data.cover,
                                contentDescription = null,
                                // In the player, a cover without a Canvas moves very gently (6.2).
                                modifier = if (depth) placed.generatedCoverMotion(playing) else placed,
                                contentScale = ContentScale.Crop,
                                onSuccess = { loaded ->
                                    val size = loaded.painter.intrinsicSize
                                    if (size.width > 0f && size.height > 0f) measured(size.width / size.height)
                                }
                            )
                        }
                    else -> Box(Modifier.fillMaxSize()
                        .then(if (depth) Modifier.background(Brush.linearGradient(gradientFor(data.id))) else Modifier)
                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.Transparent))))
                }
            }
        }
        // While this title is being mixed with another, the other cover dissolves over it.
        MixArtworkOverlay(track)
        if (badge && canvasPath != null && (forceCanvas || AppSettings.animatedArtwork.value)) {
            Text(
                "CANVAS",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * 7.5 motion75: the last Canvas player let go, kept prepared and paused for a short while, so that the same Canvas
 * shown again (an album page reopened, the player opened over it) starts from a decoded frame instead of a cold
 * start. One at most; anything else is released at once.
 */
internal object CanvasPlayerPark {
    private const val KEEP_MS = 20_000L
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var parked: Pair<String, ExoPlayer>? = null
    private val expire = Runnable { drop() }

    fun take(path: String): ExoPlayer? {
        val (where, exo) = parked ?: return null
        if (where != path) return null
        parked = null; handler.removeCallbacks(expire)
        return exo
    }

    fun park(path: String, exo: ExoPlayer) {
        // A player that failed, or a Lite device short of decoders, is not kept.
        if (exo.playerError != null || com.ketvoice.app.LiteTuning.canvasReleasedWhenHidden()) { runCatching { exo.release() }; return }
        drop()
        runCatching { exo.playWhenReady = false; exo.clearVideoSurface() }
        parked = path to exo
        handler.postDelayed(expire, KEEP_MS)
    }

    fun drop() {
        handler.removeCallbacks(expire)
        parked?.second?.let { runCatching { it.release() } }
        parked = null
    }
}

/**
 * Canvas videos shown at once (Ketvoice Lite on a modest device plays one: [LiteTuning.canvasAtOnce]); the
 * newest shown has the decoder, the others show their cover meanwhile.
 */
internal object CanvasSlots {
    val holders = androidx.compose.runtime.mutableStateListOf<Any>()
    fun plays(token: Any, atOnce: Int): Boolean =
        atOnce == Int.MAX_VALUE || holders.indexOf(token).let { it >= 0 && it >= holders.size - atOnce }
}

@Composable
private fun rememberStarted(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return visible
}

@Composable
internal fun CanvasVideo(path: String, playing: Boolean, cover: String?, modifier: Modifier = Modifier,
                        resizeMode: Int = CanvasPresentation.NORMAL_RESIZE_MODE,
                        onFailure: () -> Unit = {}, onAspect: ((Float) -> Unit)? = null) {
    val token = remember { Any() }
    DisposableEffect(token) { CanvasSlots.holders.add(token); onDispose { CanvasSlots.holders.remove(token) } }
    val visible = rememberStarted()
    val decoder = CanvasSlots.plays(token, com.ketvoice.app.LiteTuning.canvasAtOnce()) &&
        (visible || !com.ketvoice.app.LiteTuning.canvasReleasedWhenHidden())
    if (decoder) CanvasVideoPlayer(path, playing, visible, modifier, resizeMode, onFailure, onAspect)
    else if (cover != null) AsyncImage(cover, null, modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun CanvasVideoPlayer(path: String, playing: Boolean, visible: Boolean, modifier: Modifier,
                              resizeMode: Int, onFailure: () -> Unit, onAspect: ((Float) -> Unit)?) {
    val context = LocalContext.current
    // 7.5 motion75: a page closed a moment ago left its player prepared; the same Canvas opened again takes it.
    val exo = remember(path) { CanvasPlayerPark.take(path) ?: run {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            // Ketvoice Lite keeps a few seconds of a Canvas in memory instead of Media3's 50 s (a short loop needs no more).
            .apply {
                com.ketvoice.app.LiteTuning.canvasBuffer()?.let { b ->
                    setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(b.minMs, b.maxMs, b.playMs, b.rebufferMs)
                        .setTargetBufferBytes(b.bytes).setPrioritizeTimeOverSizeThresholds(false).build())
                }
                // On a modest device, a decoder that refuses a file hands it to the next one instead of failing.
                if (com.ketvoice.app.LiteTuning.canvasMaxVideo() != null)
                    setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            }
            .build()
            .apply {
                volume = 0f
                // A Canvas is a silent loop: its sound track is never decoded (less work beside the music).
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    // A modest device takes a rendition every hardware decoder plays, when the Canvas offers several.
                    .apply { com.ketvoice.app.LiteTuning.canvasMaxVideo()?.let { (w, h) -> setMaxVideoSize(w, h) } }
                    .build()
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                // A published Canvas plays from its public HTTPS address; a cached or chosen one from its file.
                setMediaItem(MediaItem.fromUri(if (path.startsWith("https://")) Uri.parse(path) else Uri.fromFile(File(path))))
                prepare()
            }
    } }
    LaunchedEffect(playing, visible, exo) { exo.playWhenReady = playing && visible }
    // 7.5 motion75: the video shows once a frame of it is drawn; until then what lies under it (its still) stays.
    var drawn by remember(exo) { mutableStateOf(false) }
    val latestFailure by rememberUpdatedState(onFailure)
    val latestAspect by rememberUpdatedState(onAspect)
    DisposableEffect(exo) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { latestFailure() }
            override fun onRenderedFirstFrame() { drawn = true }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0)
                    latestAspect?.invoke(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
    DisposableEffect(exo) { onDispose { CanvasPlayerPark.park(path, exo) } }
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.canvas_view, null) as PlayerView).apply {
                useController = false
                this.resizeMode = resizeMode
                player = exo
            }
        },
        modifier = modifier.graphicsLayer { alpha = if (drawn) 1f else 0f },
        onReset = null,
        onRelease = { it.player = null },
        update = { it.player = exo; it.resizeMode = resizeMode }
    )
}

@Composable
fun PlayerTransport(
    playing: Boolean,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    previousModifier: Modifier = Modifier,
    playModifier: Modifier = Modifier,
    nextModifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp, max = 168.dp)
            .fillMaxHeight(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportButton(onPrevious, previousModifier) {
            TransportGlyph("previous", Modifier.size(48.dp))
        }
        val playSrc = remember { MutableInteractionSource() }
        val playPressed by playSrc.collectIsPressedAsState()
        val transportHaptic = com.ketvoice.app.ui.motion.rememberHaptics()
        val playbackStateLabel = com.ketvoice.app.data.AppText.t(if (playing) "Lecture en cours" else "En pause")
        Box(
            playModifier
                .pressScale(min = 0.88f, isPressed = playPressed)
                .size(74.dp)
                .clip(CircleShape)
                .semantics { stateDescription = playbackStateLabel }
                .clickable(playSrc, null, role = androidx.compose.ui.semantics.Role.Button) { transportHaptic(com.ketvoice.app.ui.motion.Haptics.Kind.Press); onToggle() },
            contentAlignment = Alignment.Center
        ) {
            MorphingTransportGlyph(playing, Modifier.size(46.dp))
        }
        TransportButton(onNext, nextModifier) {
            TransportGlyph("next", Modifier.size(48.dp))
        }
    }
}

@Composable
fun TransportGlyph(kind: String, modifier: Modifier = Modifier, tint: Color = Color.White) {
    val description = when (kind) { "previous" -> "Précédent"; "next" -> "Suivant"; "pause" -> "Pause"; else -> "Lecture" }
    Canvas(modifier.semantics { contentDescription = com.ketvoice.app.data.AppText.t(description) }) {
        fun triangle(x: Float, width: Float, top: Float, bottom: Float) {
            val mid = (top + bottom) / 2
            val r = width * .10f
            val p = Path().apply {
                moveTo(x + r, top + r)
                quadraticTo(x, top, x, top + r * 2)
                lineTo(x, bottom - r * 2)
                quadraticTo(x, bottom, x + r * 2, bottom - r)
                lineTo(x + width - r, mid + r)
                quadraticTo(x + width + r, mid, x + width - r, mid - r)
                close()
            }
            drawPath(p, tint)
        }
        if (kind == "pause") {
            listOf(.18f, .59f).forEach { f -> drawRoundRect(tint, Offset(size.width * f, size.height * .08f),
                Size(size.width * .23f, size.height * .84f), CornerRadius(3.dp.toPx())) }
        } else if (kind == "play") triangle(size.width * .17f, size.width * .72f, size.height * .03f, size.height * .97f)
        else scale(if (kind == "previous") -1f else 1f, 1f) {
            triangle(size.width * .02f, size.width * .48f, size.height * .19f, size.height * .81f)
            triangle(size.width * .49f, size.width * .48f, size.height * .19f, size.height * .81f)
        }
    }
}

@Composable
fun QueueGlyph(modifier: Modifier = Modifier, tint: Color = Color.White.copy(alpha = .7f)) {
    Canvas(modifier) {
        for (y in listOf(.2f, .5f, .8f)) {
            drawCircle(tint, size.width * .052f, Offset(size.width * .08f, size.height * y))
            drawLine(tint, Offset(size.width * .28f, size.height * y), Offset(size.width * .95f, size.height * y),
                size.height * .085f, StrokeCap.Round)
        }
    }
}

@Composable
private fun TransportButton(onClick: () -> Unit, modifier: Modifier = Modifier, inner: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier
            .pressScale(min = 0.84f, isPressed = pressed)
            .size(66.dp)
            .clip(CircleShape)
            .clickable(src, null) { onClick() },
        contentAlignment = Alignment.Center
    ) { inner() }
}

@Composable
fun PlayerBottomBar(
    lyricsActive: Boolean = false,
    onLyrics: () -> Unit,
    onOutput: () -> Unit,
    onProfile: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmallCircleButton(onClick = onLyrics, active = lyricsActive, transparent = !lyricsActive) { tint ->
            LyricsGlyph(Modifier.size(25.dp).semantics { contentDescription = com.ketvoice.app.data.AppText.t("Paroles") }, tint)
        }
        Spacer(Modifier.weight(1f))
        GlassPanel(Modifier.height(48.dp).width(140.dp), RoundedCornerShape(999.dp),
            GlassParams.navigation(dark = true), enabled = AppSettings.liquidGlass.value) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(noRipple, null) { onOutput() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    castOutputIcon(),
                    "Sortie audio",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.16f)))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(noRipple, null) { onProfile() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    "Compte",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        }
        Spacer(Modifier.weight(1f))
        SmallCircleButton(onClick = onQueue, active = false, transparent = true) { tint ->
            QueueGlyph(Modifier.size(25.dp).semantics { contentDescription = com.ketvoice.app.data.AppText.t("File d'attente") }, tint)
        }
    }
}

@Composable
fun SmallCircleButton(
    onClick: () -> Unit,
    active: Boolean = false,
    size: Dp = 46.dp,
    transparent: Boolean = false,
    inner: @Composable (Color) -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    val activeSurface = Color(accent.red * .18f, accent.green * .18f, accent.blue * .18f, .92f)
    val surface by animateColorAsState(if (active) activeSurface else Color.White.copy(alpha = if (transparent) 0f else .10f),
        tween(Motion.duration()), label = "circleBg")
    val ink by animateColorAsState(if (active) accent else Color.White.copy(alpha = .92f),
        tween(Motion.duration()), label = "circleInk")
    Box(
        Modifier.size(maxOf(size, 48.dp)).clip(CircleShape).questPointer()
            .hoverHighlight(source, CircleShape, Color.White)
            .clickable(source, null, role = androidx.compose.ui.semantics.Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(size).clip(CircleShape)
            .background(surface),
            contentAlignment = Alignment.Center) {
            inner(ink)
        }
    }
}

@Composable
fun LyricsGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 2.1.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.06f, h * 0.10f),
            size = Size(w * 0.88f, h * 0.68f),
            cornerRadius = CornerRadius(h * 0.22f),
            style = Stroke(width = stroke)
        )
        val tail = Path().apply {
            moveTo(w * 0.32f, h * 0.77f)
            lineTo(w * 0.26f, h * 0.97f)
            lineTo(w * 0.52f, h * 0.77f)
            close()
        }
        drawPath(tail, tint)
        drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.35f, h * 0.40f))
        drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.63f, h * 0.40f))
        drawLine(tint, Offset(w * 0.35f, h * 0.46f), Offset(w * 0.33f, h * 0.56f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.63f, h * 0.46f), Offset(w * 0.61f, h * 0.56f), stroke, StrokeCap.Round)
    }
}

@Composable
fun QueueActionsBar(
    shuffle: Boolean,
    repeatOne: Boolean,
    autoplay: Boolean,
    repeatAll: Boolean,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onAutoplay: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassPanel(Modifier.weight(1f).height(48.dp), RoundedCornerShape(999.dp),
            GlassParams.navigation(dark = true), enabled = AppSettings.liquidGlass.value) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Ketmix first, then the three ways the queue plays.
            KetmixSegment()
            QueueSegment(shuffle, onShuffle) {
                Icon(Icons.Filled.Shuffle, "Aléatoire", tint = it, modifier = Modifier.size(23.dp))
            }
            // Off, the whole queue, then the current title.
            QueueSegment(repeatOne || repeatAll, onRepeat, effectKey = if (repeatOne) 2 else if (repeatAll) 1 else 0, spin = true) {
                Icon(
                    if (repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    repeatLabel(repeatOne, repeatAll),
                    tint = it,
                    modifier = Modifier.size(24.dp)
                )
            }
            QueueSegment(autoplay, onAutoplay) {
                Icon(Icons.Filled.AllInclusive, "Lecture automatique", tint = it, modifier = Modifier.size(25.dp))
            }
        }
        }
        Spacer(Modifier.width(62.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(noRipple, null) { onQueue() },
            contentAlignment = Alignment.Center
        ) {
            QueueGlyph(Modifier.size(27.dp).semantics { contentDescription = com.ketvoice.app.data.AppText.t("Fermer") })
        }
    }
}

/** Spoken name of the repeat control for its three states. */
internal fun repeatLabel(repeatOne: Boolean, repeatAll: Boolean): String = when {
    repeatOne -> "Répéter le titre"
    repeatAll -> "Répéter la file"
    else -> "Répéter"
}

@Composable
private fun RowScope.QueueSegment(active: Boolean, onClick: () -> Unit, effectKey: Any? = active, spin: Boolean = false,
                                  inner: @Composable (Color) -> Unit) {

    val segAlpha by animateFloatAsState(if (active) 0.20f else 0f, tween(Motion.duration(Motion.FADE)), label = "segBg")
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    // Ket 27: the symbol pulses when its mode is switched (the arrows of "repeat" also go round), with a tick.
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = segAlpha * 2.4f))
            .clickable(noRipple, null) { haptic(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); onClick() },
        contentAlignment = Alignment.Center
    ) {
        val tint = if (active) accent else Color.White.copy(alpha = 0.78f)
        Box(Modifier.symbolPulse(effectKey, spin = spin)) { inner(tint) }
    }
}

@Composable
fun NowPlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    if (!playing || Motion.reduced) {
        NowPlayingBarsCanvas({ .55f }, { .50f }, { .45f }, { .40f }, modifier, color)
        return
    }
    val transition = rememberInfiniteTransition(label = "bars")
    val a = transition.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse), label = "a"
    )
    val b = transition.animateFloat(
        0.9f, 0.2f,
        infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse), label = "b"
    )
    val c = transition.animateFloat(
        0.35f, 0.95f,
        infiniteRepeatable(tween(760, easing = LinearEasing), RepeatMode.Reverse), label = "c"
    )
    val d = transition.animateFloat(
        0.75f, 0.3f,
        infiniteRepeatable(tween(540, easing = LinearEasing), RepeatMode.Reverse), label = "d"
    )
    // The heights are read while drawing (6.6): passed as values they recomposed the bars, and the row holding
    // them, every frame while a title played.
    NowPlayingBarsCanvas({ a.value }, { b.value }, { c.value }, { d.value }, modifier, color)
}

@Composable
private fun NowPlayingBarsCanvas(a: () -> Float, b: () -> Float, c: () -> Float, d: () -> Float, modifier: Modifier, color: Color) {
    Canvas(modifier.size(width = 26.dp, height = 22.dp)) {
        val gap = size.width / 5.2f
        val barW = 2.6.dp.toPx()
        repeat(4) { i ->
            val h = size.height * when (i) { 0 -> a(); 1 -> b(); 2 -> c(); else -> d() }
            drawLine(
                color,
                Offset(gap * (i + 1), size.height - h / 2f),
                Offset(gap * (i + 1), size.height / 2f + h / 2f),
                strokeWidth = barW,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun LyricsPreviewLine(onOpen: () -> Unit) {
    val lyrics by com.ketvoice.app.data.LyricsRepo.state.collectAsState()
    val position = Player.positionMs.collectAsState()
    val trackId = Player.state.collectAsState().value.current?.id
    val offsets by com.ketvoice.app.data.LyricsOffsets.values.collectAsState()
    val offsetKey = remember(trackId) { trackId?.let(com.ketvoice.app.data.LyricsOffsets::key) }
    val offset = offsetKey?.let { offsets[it] } ?: 0
    // 7.6: composed again when the sung line changes, not at each 200 ms position tick.
    val activeText by remember(lyrics, offset) { androidx.compose.runtime.derivedStateOf {
        lyrics.activeText(com.ketvoice.app.data.LyricsOffsets.adjustedPosition(position.value, offset))
    } }
    val text = activeText
        ?: if (AppSettings.lyricsMode.value == 2 || AppSettings.lyricsSource.value == "off")
            "Paroles désactivées"
        else if (lyrics.loading) "Recherche des paroles…" else "Paroles non disponibles"

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(noRipple, null) { onOpen() }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            localize = activeText == null,
            color = Color.White.copy(alpha = if (activeText != null) 0.96f else 0.5f),
            fontSize = if (activeText != null) 16.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "›",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun Modifier.pressScale(min: Float = 0.86f, isPressed: Boolean): Modifier {
    val s by animateFloatAsState(
        targetValue = if (isPressed && !Motion.reduced) min else 1f,
        animationSpec = Motion.pressSpring(isPressed),
        label = "press"
    )
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

fun formatSecs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Scale of a sheet while it appears: it starts a little smaller, never from a dot. */
internal fun sheetEntryScale(progress: Float): Float = .82f + .18f * (if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f)

/**
 * The point a sheet grows from: where the listener last touched, expressed in the
 * sheet's own proportions. Without a known touch, or before the sheet is measured,
 * it grows from its bottom centre.
 */
internal fun sheetOrigin(touch: androidx.compose.ui.geometry.Offset?, bounds: androidx.compose.ui.geometry.Rect): androidx.compose.ui.graphics.TransformOrigin {
    if (touch == null || bounds.width <= 0f || bounds.height <= 0f || !touch.x.isFinite() || !touch.y.isFinite())
        return androidx.compose.ui.graphics.TransformOrigin(.5f, 1f)
    return androidx.compose.ui.graphics.TransformOrigin(
        ((touch.x - bounds.left) / bounds.width).coerceIn(-1f, 2f), ((touch.y - bounds.top) / bounds.height).coerceIn(-1f, 2f))
}

@Composable
fun PlayerSheetScrim(onClose: () -> Unit, content: @Composable () -> Unit) {
    // Ket 27: every sheet rests on detents (half height, full height), follows the finger and settles with a spring.
    if (com.ketvoice.app.ui.common.springSheets) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            com.ketvoice.app.ui.common.SpringSheet(onClose, insets = Modifier.statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures {} }) { content() }
                }
            }
        }
        return
    }

    val appear = remember { androidx.compose.animation.core.Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(Motion.duration(320), easing = androidx.compose.animation.core.CubicBezierEasing(.22f, 1f, .36f, 1f)))
    }
    val touch = remember { com.ketvoice.app.ui.AppUi.lastTouch }
    var sheetBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .35f * appear.value))
    ) {
        val closeLabel = com.ketvoice.app.data.AppText.t("Fermer")
        Box(Modifier.matchParentSize().semantics { contentDescription = closeLabel }
            .clickable(noRipple, null) { onClose() })
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding().navigationBarsPadding()
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .onGloballyPositioned { sheetBounds = it.boundsInRoot() }
                .graphicsLayer {
                    alpha = appear.value
                    if (!Motion.reduced) {
                        // The sheet grows out of the button that opened it, then settles in place.
                        val scale = sheetEntryScale(appear.value)
                        scaleX = scale; scaleY = scale
                        transformOrigin = sheetOrigin(touch, sheetBounds)
                        translationY = (1f - appear.value) * 12.dp.toPx()
                    }
                }
                .pointerInput(Unit) { detectTapGestures {} },
        ) { content() }
    }
}

/**
 * Ketmix at work (6.0.0 Beta 5), between the times under the bar: its sign of two rings that drift
 * together, merge at the hand-over and part again, and its name under a passing sheen. It rises in with a
 * small spring when a mix starts and leaves when it ends. Still under reduced motion.
 */
@Composable
internal fun KetmixMixingBadge(progress: Float?, tint: Color, modifier: Modifier = Modifier) {
    val visible = progress != null
    val presence by animateFloatAsState(if (visible) 1f else 0f,
        if (Motion.reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .62f, stiffness = 380f),
        label = "ketmix-badge")
    if (presence <= .01f) return
    // Read while drawing (6.6): read here it recomposed the badge every frame of a mix.
    val sheenState = if (Motion.reduced) null else rememberInfiniteTransition(label = "ketmix-sheen").animateFloat(
        -1f, 2f, infiniteRepeatable(androidx.compose.animation.core.tween(1_600, easing = LinearEasing)), label = "sheen")
    val sheen = { sheenState?.value ?: 0f }
    val label = com.ketvoice.app.data.AppText.t("Ketmix")
    Row(modifier.then(Modifier.ketmixBadgeTag()).graphicsLayer {
            alpha = presence.coerceIn(0f, 1f)
            val s = .8f + .2f * presence
            scaleX = s; scaleY = s
            translationY = (1f - presence) * 6.dp.toPx()
        }.semantics { contentDescription = com.ketvoice.app.data.AppText.t("Ketmix mixe les titres") },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        AutomixGlyph(tint.copy(alpha = .95f), Modifier.size(14.dp), overlap = MixVisuals.ringOverlap(progress))
        Spacer(Modifier.width(4.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, localize = false,
            modifier = Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .then(Modifier.ketmixSheen(sheen)))
    }
}

private fun Modifier.ketmixBadgeTag(): Modifier = this.testTag("ketmix-badge")

/** A sheen that passes over what is drawn, inside its own letters only. */
private fun Modifier.ketmixSheen(sheenAt: () -> Float): Modifier = this.drawWithContent {
                    drawContent()
                    val sheen = sheenAt()
                    if (sheen != 0f) drawRect(androidx.compose.ui.graphics.Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0f), .5f to Color.White.copy(alpha = .9f), 1f to Color.White.copy(alpha = 0f),
                        start = Offset(size.width * (sheen - .35f), 0f), end = Offset(size.width * (sheen + .35f), size.height)),
                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                }

