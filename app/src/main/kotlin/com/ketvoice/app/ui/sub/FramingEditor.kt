package com.ketvoice.app.ui.sub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropOriginal
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Frame
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.rememberHaptics
import com.ketvoice.app.ui.player.ArtworkCanvas
import kotlin.math.min
import kotlin.math.roundToInt

/** How the rectangle may be shaped in the editor. */
internal enum class FramingShape { Custom, Original, Square }

/** What a framing is for: the album page everyone sees, or the music player alone. */
internal enum class FramingScope { Page, Player }

/**
 * Full-screen framing of an album's hero picture or Canvas, in the manner of a photo
 * editor: the whole picture on a dark stage, a rectangle with handles and a grid of
 * thirds over it, an angle wheel, a quarter turn, a mirror, three shapes, cancel and done.
 * What is inside the rectangle is what the album page will show.
 *
 * With [onPlayerDone] the screen has two modes: the album page, and the music player alone.
 * In the player's mode the rectangle keeps the shape of the player's artwork ([playerBoxAspect]):
 * what it holds is exactly what the player will show, and the album page is left as it is.
 */
@Composable
internal fun FramingEditor(track: Track?, cover: String?, canvas: String?, initial: Frame?, pageBoxAspect: Float,
                           onCancel: () -> Unit, onDone: (Frame) -> Unit,
                           playerInitial: Frame? = null, playerBoxAspect: Float = 1f, startScope: FramingScope = FramingScope.Page,
                           onPlayerDone: ((Frame) -> Unit)? = null) {
    val accent = LocalTheme.current.accent
    val haptic = rememberHaptics()
    val density = LocalDensity.current
    // A Canvas is upright, a cover square, until the real picture says otherwise.
    var aspect by remember { mutableFloatStateOf(if (canvas != null) 9f / 16f else 1f) }
    var scope by remember { mutableStateOf(if (onPlayerDone != null) startScope else FramingScope.Page) }
    // What each mode shows before anything is changed: the page's own framing, and for the player
    // its own, else the page's as the player's artwork crops it, else the plain picture.
    fun starting(of: FramingScope, shapeOfPicture: Float): Frame = when (of) {
        FramingScope.Page -> initial?.let { Framing.contain(it, shapeOfPicture) } ?: Framing.default(shapeOfPicture, pageBoxAspect)
        FramingScope.Player -> (playerInitial ?: initial)?.let { Framing.shaped(it, playerBoxAspect, shapeOfPicture) }
            ?: Framing.default(shapeOfPicture, playerBoxAspect)
    }
    fun startsTouched(of: FramingScope) = if (of == FramingScope.Page) initial != null else playerInitial != null
    var frame by remember { mutableStateOf(starting(scope, aspect)) }
    var shape by remember { mutableStateOf(FramingShape.Custom) }
    var touched by remember { mutableStateOf(startsTouched(scope)) }
    // The other mode's rectangle waits here while this one is worked on.
    var parked by remember { mutableStateOf<Frame?>(null) }
    var parkedTouched by remember { mutableStateOf(false) }
    val locked: Float? = if (scope == FramingScope.Player) playerBoxAspect else null
    fun measured(value: Float) {
        if (!value.isFinite() || value <= 0f || kotlin.math.abs(value - aspect) < .005f) return
        aspect = value
        // Until the owner changes something, the rectangle is what is shown for this picture.
        frame = when {
            !touched -> starting(scope, value)
            scope == FramingScope.Player -> Framing.shaped(frame, playerBoxAspect, value)
            else -> Framing.contain(frame, value)
        }
        parked = parked?.takeIf { parkedTouched }?.let { Framing.contain(it, value) }
    }
    fun switchTo(next: FramingScope) {
        if (next == scope) return
        val coming = parked ?: starting(next, aspect)
        val comingTouched = if (parked != null) parkedTouched else startsTouched(next)
        parked = frame; parkedTouched = touched
        scope = next; frame = coming; touched = comingTouched; shape = FramingShape.Custom
        haptic(Haptics.Kind.Tick)
    }
    BackHandler(onBack = onCancel)

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().testTag("framing-editor")) {
        if (onPlayerDone != null) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            FramingScopeChip("Page d'album", scope == FramingScope.Page, accent, "framing-scope-page") { switchTo(FramingScope.Page) }
            FramingScopeChip("Lecteur", scope == FramingScope.Player, accent, "framing-scope-player") { switchTo(FramingScope.Player) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            val stageWidth = with(density) { maxWidth.toPx() }
            val stageHeight = with(density) { maxHeight.toPx() }
            val (boundsWidth, boundsHeight) = Framing.bounds(aspect, frame.rotation)
            // Pixels per width of the picture: the turned picture fits the stage with a margin for the handles.
            val unit = min(stageWidth * .9f / boundsWidth, stageHeight * .9f / boundsHeight)
            Box(Modifier.align(Alignment.Center)
                .size(with(density) { unit.toDp() }, with(density) { (unit / aspect).toDp() })
                .graphicsLayer { scaleX = if (frame.flip) -1f else 1f; rotationZ = frame.rotation }) {
                if (canvas != null && track != null) ArtworkCanvas(track, canvas, playing = true, modifier = Modifier.fillMaxSize(),
                    forceCanvas = true, onMediaAspect = ::measured)
                else AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    onSuccess = { loaded ->
                        val size = loaded.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) measured(size.width / size.height)
                    })
            }
            val latestFrame by rememberUpdatedState(frame)
            val latestShape by rememberUpdatedState(shape)
            val latestLocked by rememberUpdatedState(locked)
            val latestUnit by rememberUpdatedState(unit)
            val latestAspect by rememberUpdatedState(aspect)
            val reach = with(density) { 30.dp.toPx() }
            Canvas(Modifier.fillMaxSize().testTag("framing-stage").pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val now = latestFrame
                    val centreX = size.width / 2f + now.cx * latestUnit
                    val centreY = size.height / 2f + now.cy * latestUnit
                    val grab = Framing.grab(down.position.x, down.position.y, centreX - now.w * latestUnit / 2f,
                        centreY - now.h * latestUnit / 2f, centreX + now.w * latestUnit / 2f, centreY + now.h * latestUnit / 2f, reach)
                    var previous = down.position
                    var spread = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        var next = latestFrame
                        if (pressed.size >= 2) {
                            val distance = (pressed[0].position - pressed[1].position).getDistance()
                            if (spread > 0f && distance > 0f) next = Framing.pinch(next, distance / spread, latestAspect)
                            spread = distance
                            previous = pressed[0].position
                        } else {
                            spread = 0f
                            val position = pressed[0].position
                            if (grab != null) next = Framing.drag(next, grab, (position.x - previous.x) / latestUnit,
                                (position.y - previous.y) / latestUnit, latestAspect,
                                latestLocked ?: if (latestShape == FramingShape.Square) 1f else null)
                            previous = position
                        }
                        if (next != latestFrame) {
                            frame = next; touched = true
                            if (latestShape == FramingShape.Original) shape = FramingShape.Custom
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }) {
                val centre = Offset(size.width / 2f + frame.cx * unit, size.height / 2f + frame.cy * unit)
                val half = Offset(frame.w * unit / 2f, frame.h * unit / 2f)
                val left = centre.x - half.x; val top = centre.y - half.y
                val right = centre.x + half.x; val bottom = centre.y + half.y
                val veil = Color.Black.copy(alpha = .62f)
                // Everything outside the rectangle is dimmed.
                drawRect(veil, Offset.Zero, Size(size.width, top.coerceAtLeast(0f)))
                drawRect(veil, Offset(0f, bottom), Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
                drawRect(veil, Offset(0f, top), Size(left.coerceAtLeast(0f), bottom - top))
                drawRect(veil, Offset(right, top), Size((size.width - right).coerceAtLeast(0f), bottom - top))
                val line = 1.dp.toPx()
                for (i in 1..2) {
                    val x = left + (right - left) * i / 3f; val y = top + (bottom - top) * i / 3f
                    drawLine(Color.White.copy(alpha = .5f), Offset(x, top), Offset(x, bottom), line)
                    drawLine(Color.White.copy(alpha = .5f), Offset(left, y), Offset(right, y), line)
                }
                drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.5.dp.toPx()))
                // Corner brackets and edge bars: where the fingers take hold.
                val thick = 3.5.dp.toPx(); val arm = min(22.dp.toPx(), min(right - left, bottom - top) / 3f)
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                    val corner = Offset(if (sx < 0) left else right, if (sy < 0) top else bottom)
                    drawLine(Color.White, corner, Offset(corner.x - sx * arm, corner.y), thick, StrokeCap.Square)
                    drawLine(Color.White, corner, Offset(corner.x, corner.y - sy * arm), thick, StrokeCap.Square)
                }
                val bar = min(30.dp.toPx(), min(right - left, bottom - top) / 3f) / 2f
                drawLine(Color.White, Offset(centre.x - bar, top), Offset(centre.x + bar, top), thick, StrokeCap.Square)
                drawLine(Color.White, Offset(centre.x - bar, bottom), Offset(centre.x + bar, bottom), thick, StrokeCap.Square)
                drawLine(Color.White, Offset(left, centre.y - bar), Offset(left, centre.y + bar), thick, StrokeCap.Square)
                drawLine(Color.White, Offset(right, centre.y - bar), Offset(right, centre.y + bar), thick, StrokeCap.Square)
            }
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF0E0F12))) {
            AngleWheel(Framing.tilt(frame.rotation), accent) { tilt ->
                val next = Framing.withTilt(frame, tilt, aspect)
                if (next != frame) { frame = next; touched = true; if (shape == FramingShape.Original) shape = FramingShape.Custom }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FramingTool(Icons.Filled.RotateRight, "Pivoter", false, accent, "framing-rotate", Modifier.weight(1f)) {
                    haptic(Haptics.Kind.Tick)
                    val turned = Framing.contain(Framing.quarterTurn(frame), aspect)
                    // The player's rectangle keeps the shape of its artwork through a quarter turn.
                    frame = if (locked != null) Framing.shaped(turned, locked, aspect) else turned
                    touched = true
                }
                FramingTool(Icons.Filled.Flip, "Horizontal", false, accent, "framing-flip", Modifier.weight(1f)) {
                    haptic(Haptics.Kind.Tick)
                    frame = Framing.contain(Framing.mirror(frame), aspect); touched = true
                }
                Box(Modifier.width(1.dp).height(34.dp).background(Color.White.copy(alpha = .14f)))
                if (locked != null) {
                    // One shape only: the player's artwork. Back to what the player shows without a framing of its own.
                    FramingTool(Icons.Filled.CropOriginal, "Par défaut", false, accent, "framing-player-default", Modifier.weight(1.4f)) {
                        haptic(Haptics.Kind.Tick)
                        frame = initial?.let { Framing.shaped(it, locked, aspect) } ?: Framing.default(aspect, locked); touched = true
                    }
                    Text("Le cadre garde la forme de la pochette du lecteur.", color = Color.White.copy(alpha = .62f), fontSize = 11.sp,
                        lineHeight = 14.sp, maxLines = 3, modifier = Modifier.weight(1.85f).padding(horizontal = 8.dp).testTag("framing-player-note"))
                } else {
                FramingTool(Icons.Filled.CropFree, "Personnalisé", shape == FramingShape.Custom, accent, "framing-custom", Modifier.weight(1.25f)) {
                    shape = FramingShape.Custom
                }
                FramingTool(Icons.Filled.CropOriginal, "Original", shape == FramingShape.Original, accent, "framing-original", Modifier.weight(1f)) {
                    shape = FramingShape.Original; touched = true
                    frame = Framing.original(aspect, frame.rotation, frame.flip)
                }
                FramingTool(Icons.Filled.CropSquare, "1:1", shape == FramingShape.Square, accent, "framing-square", Modifier.weight(1f), localize = false) {
                    shape = FramingShape.Square; touched = true
                    frame = Framing.square(aspect, frame.rotation, frame.flip)
                }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF17181C)).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FramingEnd(Icons.Filled.Close, "Annuler", "framing-cancel", onCancel)
            Spacer(Modifier.weight(1f))
            FramingEnd(Icons.Filled.Check, "Valider", "framing-done") {
                if (scope == FramingScope.Player && onPlayerDone != null) onPlayerDone(frame.clamped()) else onDone(frame.clamped())
            }
        }
    }
}

/** The angle wheel: ticks that slide under a fixed mark, one degree at a time. */
@Composable
private fun AngleWheel(tilt: Float, accent: Color, onTilt: (Float) -> Unit) {
    val latest by rememberUpdatedState(tilt)
    val latestChange by rememberUpdatedState(onTilt)
    val description = AppText.t("Angle")
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 28.dp)
            .semantics { contentDescription = description }.testTag("framing-angle")
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { latestChange(0f) }) }
            .pointerInput(Unit) {
                val perDegree = 7.dp.toPx()
                var carried = 0f
                detectHorizontalDragGestures(onDragStart = { carried = latest }) { change, amount ->
                    change.consume()
                    // Dragging the wheel to the left turns the picture clockwise, as on the model.
                    carried = (carried - amount / perDegree).coerceIn(-45f, 45f)
                    latestChange(carried.roundToInt().toFloat())
                }
            }) {
            val perDegree = 7.dp.toPx()
            val middle = size.width / 2f
            for (degree in -45..45) {
                val x = middle + (degree - tilt) * perDegree
                if (x < 0f || x > size.width) continue
                val major = degree % 5 == 0
                val fade = (1f - kotlin.math.abs(x - middle) / middle).coerceIn(.18f, 1f)
                drawLine(Color.White.copy(alpha = (if (major) .95f else .5f) * fade), Offset(x, if (major) 6.dp.toPx() else 10.dp.toPx()),
                    Offset(x, size.height - 6.dp.toPx()), (if (major) 2.dp else 1.2.dp).toPx(), StrokeCap.Round)
            }
            drawLine(accent, Offset(middle, 0f), Offset(middle, size.height), 2.5.dp.toPx(), StrokeCap.Round)
        }
        Text("${tilt.roundToInt()}°", localize = false, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp).testTag("framing-angle-value"))
    }
}

@Composable
private fun FramingTool(icon: ImageVector, label: String, selected: Boolean, accent: Color, tag: String, modifier: Modifier = Modifier,
                        localize: Boolean = true, onClick: () -> Unit) {
    val ink = if (selected) accent else Color.White.copy(alpha = .86f)
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 64.dp).padding(vertical = 8.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(26.dp))
        Text(label, localize = localize, color = ink, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, modifier = Modifier.padding(top = 5.dp))
    }
}

/** One of the two modes at the top of the screen. */
@Composable
private fun FramingScopeChip(label: String, selected: Boolean, accent: Color, tag: String, onClick: () -> Unit) {
    val state = AppText.t(if (selected) "Sélectionné" else "Non sélectionné")
    Box(Modifier.clip(RoundedCornerShape(999.dp))
        .background(if (selected) accent.copy(alpha = .24f) else Color.White.copy(alpha = .08f))
        .clickable(role = Role.Tab, onClick = onClick)
        .semantics { stateDescription = state }
        .heightIn(min = 44.dp).padding(horizontal = 18.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) accent else Color.White.copy(alpha = .86f), fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun FramingEnd(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}
