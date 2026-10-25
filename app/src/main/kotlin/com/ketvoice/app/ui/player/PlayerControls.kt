@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.ui.player

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
import androidx.compose.material.icons.filled.VolumeUp
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.ketvoice.app.R
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.gradientFor
import com.ketvoice.app.ui.quest.questPointer
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.artworkDepth
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private val noRipple = MutableInteractionSource()

@Composable
fun ArtworkCanvas(
    track: Track,
    canvasPath: String?,
    playing: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = 0.dp,
    badge: Boolean = false,
    depth: Boolean = false
) {
    val art by ArtworkRepo.covers.collectAsState()
    val cover = art["t:${track.id}"] ?: track.coverUrl
    val gradient = remember(track.id) { gradientFor(track.id) }
    data class Art(val id: String, val cover: String?, val canvas: String?)

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(gradient))
    ) {
        AnimatedContent(
            modifier = Modifier.fillMaxSize().then(if (depth) Modifier.artworkDepth() else Modifier),
            targetState = Art(track.id, cover, canvasPath),

            contentKey = { if (it.canvas != null) "${it.id}:video:${it.canvas}" else "${it.id}:art:${it.cover.orEmpty()}" },
            transitionSpec = {
                fadeIn(tween(Motion.duration())).togetherWith(fadeOut(tween(Motion.duration())))
            },
            label = "artwork"
        ) { data ->
            Box(Modifier.fillMaxSize()) {
                when {
                    data.canvas != null && AppSettings.animatedArtwork.value ->
                        CanvasVideo(path = data.canvas, playing = playing, modifier = Modifier.fillMaxSize())
                    data.cover != null ->
                        run {
                            AsyncImage(
                                model = data.cover,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            track.title.take(1).uppercase(),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 92.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
        if (badge && canvasPath != null && AppSettings.animatedArtwork.value) {
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

@Composable
private fun CanvasVideo(path: String, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val exo = remember(path) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            .build()
            .apply {
                volume = 0f
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                prepare()
            }
    }
    LaunchedEffect(playing, visible, exo) { exo.playWhenReady = playing && visible }
    DisposableEffect(exo) { onDispose { runCatching { exo.release() } } }
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.canvas_view, null) as PlayerView).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                player = exo
            }
        },
        modifier = modifier,
        onReset = null,
        onRelease = { it.player = null },
        update = { it.player = exo }
    )
}

@Composable
fun ScrubBar(
    durationMs: Long,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.28f),
    fillColor: Color = com.ketvoice.app.theme.LocalTheme.current.accent,
    timeColor: Color = Color.White.copy(alpha = 0.62f),
    showTimes: Boolean = true,
    onSeek: (Long) -> Unit
) {
    val posMs by Player.positionMs.collectAsState()
    val trackId = Player.state.collectAsState().value.current?.id
    val latestSeek by rememberUpdatedState(onSeek)
    val dur = durationMs.coerceAtLeast(1L)
    var dragFraction by remember(trackId) { mutableStateOf<Float?>(null) }
    val anim = remember(trackId) { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val fraction = (posMs.toFloat() / dur).coerceIn(0f, 1f)
    val seekEpoch by Player.seekEpoch.collectAsState()
    var lastSeek by remember(trackId) { mutableLongStateOf(seekEpoch) }

    LaunchedEffect(durationMs) { anim.snapTo(fraction) }
    LaunchedEffect(fraction, seekEpoch, Motion.reduced) {
        if (dragFraction == null) {
            if (Motion.reduced || lastSeek != seekEpoch || kotlin.math.abs(fraction - anim.value) > .04f ||
                kotlin.math.abs(fraction - anim.value) < 0.0005f) {
                anim.snapTo(fraction)
            } else {
                anim.animateTo(fraction, tween(230, easing = LinearEasing))
            }
            lastSeek = seekEpoch
        }
    }

    val shown = (dragFraction ?: anim.value).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(dur) {
                    detectTapGestures { off ->
                        if (durationMs > 0) latestSeek(((off.x / size.width).coerceIn(0f, 1f) * dur).toLong())
                    }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { off ->
                            dragFraction = (off.x / size.width).coerceIn(0f, 1f)
                        },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val f = dragFraction
                            dragFraction = null
                            if (f != null && durationMs > 0) {
                                dragScope.launch { anim.snapTo(f) }
                                latestSeek((f * dur).toLong())
                            }
                        },
                        onDragCancel = { dragFraction = null }
                    )
                }
        ) {
            val barH = if (dragFraction != null) 8.dp.toPx() else 6.dp.toPx()
            val y = size.height / 2f
            val w = size.width
            val x = w * shown
            drawLine(
                trackColor,
                Offset(0f, y),
                Offset(w, y),
                strokeWidth = barH,
                cap = StrokeCap.Round
            )
            if (shown > 0.001f) {
                drawLine(
                    fillColor,
                    Offset(0f, y),
                    Offset(x.coerceAtLeast(barH / 2f), y),
                    strokeWidth = barH,
                    cap = StrokeCap.Round
                )
            }
            if (dragFraction != null) {
                drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, y))
            }
        }
        if (showTimes) {
            Row(Modifier.fillMaxWidth().padding(top = 1.dp)) {
                Text(formatSecs((shown * dur).toLong()), color = timeColor, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("-" + formatSecs(((1f - shown) * dur).toLong()), color = timeColor, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun VolumeRail(
    value: Float,
    modifier: Modifier = Modifier,
    fillColor: Color = Color.White.copy(alpha = 0.95f),
    trackColor: Color = Color.White.copy(alpha = 0.26f),
    knobColor: Color = Color.White,
    onChange: (Float) -> Unit
) {
    Canvas(
        modifier
            .height(30.dp)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    onChange((off.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val railH = 6.dp.toPx()
        val y = size.height / 2f
        val w = size.width
        val knobR = 9.dp.toPx()
        val x0 = 2.dp.toPx()
        val x1 = w - 2.dp.toPx()
        val span = (x1 - x0).coerceAtLeast(1f)
        val xv = x0 + span * value.coerceIn(0f, 1f)
        drawLine(trackColor, Offset(x0, y), Offset(x1, y), strokeWidth = railH, cap = StrokeCap.Round)
        if (value > 0.01f) {
            drawLine(fillColor, Offset(x0, y), Offset(xv, y), strokeWidth = railH, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun PlayerTransport(
    playing: Boolean,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp, max = 168.dp)
            .fillMaxHeight(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportButton(onPrevious) {
            TransportGlyph("previous", Modifier.size(48.dp))
        }
        val playSrc = remember { MutableInteractionSource() }
        val playPressed by playSrc.collectIsPressedAsState()
        Box(
            Modifier
                .pressScale(min = 0.88f, isPressed = playPressed)
                .size(74.dp)
                .clip(CircleShape)
                .clickable(playSrc, null) { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = {
                    (fadeIn(tween(Motion.duration(Motion.PRESS))) + scaleIn(initialScale = if(Motion.reduced) 1f else .85f, animationSpec = tween(Motion.duration())))
                        .togetherWith(fadeOut(tween(Motion.duration(Motion.PRESS))) + scaleOut(targetScale = if(Motion.reduced) 1f else .85f, animationSpec = tween(Motion.duration())))
                },
                label = "playpause"
            ) { p ->
                TransportGlyph(if (p) "pause" else "play", Modifier.size(46.dp))
            }
        }
        TransportButton(onNext) {
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
private fun TransportButton(onClick: () -> Unit, inner: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier
            .pressScale(min = 0.84f, isPressed = pressed)
            .size(66.dp)
            .clip(CircleShape)
            .clickable(src, null) { onClick() },
        contentAlignment = Alignment.Center
    ) { inner() }
}

@Composable
fun PlayerVolume(modifier: Modifier = Modifier, compact: Boolean = false) {
    if (AppSettings.hideVolumeBar.value) return
    val volume by Player.volume.collectAsState()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 0.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.VolumeDown,
            null,
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(if (compact) 18.dp else 20.dp)
        )
        VolumeRail(
            value = volume,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Player.setVolume(it)
        }
        Icon(
            Icons.Filled.VolumeUp,
            null,
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(if (compact) 20.dp else 22.dp)
        )
    }
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
        Row(
            Modifier
                .height(46.dp)
                .width(140.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.17f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(noRipple, null) { onOutput() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
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
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onAutoplay: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.16f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueSegment(shuffle, onShuffle) {
                Icon(Icons.Filled.Shuffle, "Aléatoire", tint = it, modifier = Modifier.size(23.dp))
            }
            QueueSegment(repeatOne, onRepeat) {
                Icon(
                    if (repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    if (repeatOne) "Répéter le titre" else "Répéter",
                    tint = it,
                    modifier = Modifier.size(24.dp)
                )
            }
            QueueSegment(autoplay, onAutoplay) {
                Icon(Icons.Filled.AllInclusive, "Lecture automatique", tint = it, modifier = Modifier.size(25.dp))
            }
        }
        Spacer(Modifier.width(62.dp))
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(noRipple, null) { onQueue() },
            contentAlignment = Alignment.Center
        ) {
            QueueGlyph(Modifier.size(27.dp))
        }
    }
}

@Composable
private fun RowScope.QueueSegment(active: Boolean, onClick: () -> Unit, inner: @Composable (Color) -> Unit) {

    val segAlpha by animateFloatAsState(if (active) 0.20f else 0f, tween(Motion.duration(Motion.FADE)), label = "segBg")
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = segAlpha * 2.4f))
            .clickable(noRipple, null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        inner(if (active) accent else Color.White.copy(alpha = 0.78f))
    }
}

@Composable
fun NowPlayingBars(playing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val a by transition.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse), label = "a"
    )
    val b by transition.animateFloat(
        0.9f, 0.2f,
        infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse), label = "b"
    )
    val c by transition.animateFloat(
        0.35f, 0.95f,
        infiniteRepeatable(tween(760, easing = LinearEasing), RepeatMode.Reverse), label = "c"
    )
    val d by transition.animateFloat(
        0.75f, 0.3f,
        infiniteRepeatable(tween(540, easing = LinearEasing), RepeatMode.Reverse), label = "d"
    )
    Canvas(modifier.size(width = 26.dp, height = 22.dp)) {
        val values = listOf(a, b, c, d)
        val gap = size.width / 5.2f
        val barW = 2.6.dp.toPx()
        values.forEachIndexed { i, v ->
            val h = if (playing) size.height * v else size.height * (0.55f - i * 0.05f)
            drawLine(
                Color.White,
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
    val posMs by Player.positionMs.collectAsState()
    val activeText = lyrics.activeText(posMs)
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
        animationSpec = tween(Motion.duration(Motion.PRESS)),
        label = "press"
    )
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

fun formatSecs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
fun PlayerSheetScrim(onClose: () -> Unit, content: @Composable () -> Unit) {

    val scrimAlpha by animateFloatAsState(
        targetValue = 0.35f,
        animationSpec = tween(Motion.duration(Motion.FADE)),
        label = "scrim"
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(noRipple, null) { onClose() }
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clickable(noRipple, null) { },
        ) { content() }
    }
}
