package com.ketvoice.app.ui.common

import com.ketvoice.app.ui.motion.symbolBounce
import com.ketvoice.app.ui.adaptive.largeScreenTrackRow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.rememberHaptics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.launch

internal object TrackActionFeedback { val snackbar = SnackbarHostState() }

/** How far a swipe is towards its action, 0 at rest and 1 once releasing would act. */
internal fun swipeProgress(offsetPx: Float, thresholdPx: Float): Float =
    if (thresholdPx <= 0f || !offsetPx.isFinite()) 0f else (kotlin.math.abs(offsetPx) / thresholdPx).coerceIn(0f, 1f)

/**
 * The same gestures on every list of titles: swipe towards the end to play the title
 * next, towards the start to add it to the queue. The row follows the finger, ticks
 * when releasing would act, and springs back. 9.0: each way may do something else
 * (Settings › Personnalisation › Disposition), or nothing.
 */
@Composable
internal fun SwipeTrackActions(track: Track, modifier: Modifier = Modifier, enabled: Boolean = true,
                               content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val offset = remember(track.id) { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val theme = LocalTheme.current
    val toEnd = SwipeAction.toEnd()
    val toStart = SwipeAction.toStart()
    var confirmRemove by remember(track.id) { mutableStateOf(false) }
    fun say(text: String, undo: (() -> Unit)? = null) {
        scope.launch {
            TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
            val result = TrackActionFeedback.snackbar.showSnackbar(AppText.t(text),
                actionLabel = undo?.let { AppText.t("Annuler") }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    fun act(action: SwipeAction) {
        when (action) {
            SwipeAction.PlayNext, SwipeAction.Enqueue -> {
                val next = action == SwipeAction.PlayNext
                val entry = Player.queueWithUndo(track, next) ?: return
                say(if (next) "Lire ensuite" else "Ajouté à la file") { Player.undoQueuedEntry(entry) }
            }
            SwipeAction.RemoveDownload -> if (swipeApplies(action, track)) confirmRemove = true
            else -> runSwipe(action, track)?.let { say(it) }
        }
    }
    val canEnd = enabled && toEnd != SwipeAction.None
    val canStart = enabled && toStart != SwipeAction.None
    val gesture = if (!canEnd && !canStart) Modifier else Modifier.pointerInput(track.id, threshold, toEnd, toStart) {
        var armed = false
        detectHorizontalDragGestures(
            onDragStart = { armed = false },
            onHorizontalDrag = { change, dx ->
                change.consume()
                val next = (offset.value + dx).coerceIn(if (canStart) -threshold * 1.4f else 0f, if (canEnd) threshold * 1.4f else 0f)
                scope.launch { offset.snapTo(next) }
                val past = kotlin.math.abs(next) >= threshold
                if (past && !armed) haptic(Haptics.Kind.Tick)
                armed = past
            },
            onDragCancel = { scope.launch { offset.animateTo(0f, Motion.gentleSpring()) } },
            onDragEnd = {
                if (kotlin.math.abs(offset.value) >= threshold) act(if (offset.value > 0f) toEnd else toStart)
                scope.launch { offset.animateTo(0f, Motion.gentleSpring()) }
            })
    }
    Box(modifier.fillMaxWidth()
        .semantics {
            customActions = listOfNotNull(toEnd, toStart).filter { it != SwipeAction.None }.distinct().map { action ->
                CustomAccessibilityAction(AppText.t(action.label)) { act(action); true }
            }
        }
        .then(gesture)) {
        val shift = offset.value
        if (shift != 0f) SwipeReveal(if (shift > 0f) toEnd else toStart, shift > 0f, swipeProgress(shift, threshold))
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = if (Motion.reduced) 0f else offset.value }
            .background(theme.background)) { content() }
    }
    if (confirmRemove) RemoveDownloadAlert(track, onDone = { removed ->
        confirmRemove = false
        if (removed) say(SwipeAction.RemoveDownload.done)
    })
}

/** What the swipe uncovers: a capsule of tinted glass with the action's symbol and name, not a flat band. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SwipeReveal(action: SwipeAction, next: Boolean, progress: Float) {
    val theme = LocalTheme.current
    val icon = when (action) {
        SwipeAction.PlayNext -> Icons.AutoMirrored.Filled.PlaylistPlay
        SwipeAction.Enqueue -> Icons.AutoMirrored.Filled.PlaylistAdd
        else -> action.icon
    }
    val label = when (action) {
        SwipeAction.PlayNext -> "Lire ensuite"
        SwipeAction.Enqueue -> "Ajouter à la file"
        SwipeAction.Favorite -> "Favoris"
        else -> action.label
    }
    Row(Modifier.matchParentSize().padding(horizontal = 8.dp, vertical = 3.dp).clip(RoundedCornerShape(18.dp))
        .background(theme.accent.copy(alpha = .10f + .16f * progress))
        .border(.8.dp, theme.accent.copy(alpha = .18f + .30f * progress), RoundedCornerShape(18.dp))
        .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (next) Arrangement.Start else Arrangement.End) {
        Icon(icon, null,
            // 8.0: the symbol bounces the moment the swipe arms the action.
            tint = theme.accent, modifier = Modifier.size(22.dp)
                .symbolBounce(progress >= 1f, enabled = progress >= 1f).graphicsLayer {
                val grow = if (Motion.reduced) 1f else .8f + .2f * progress
                scaleX = grow; scaleY = grow
            })
        Spacer(Modifier.width(8.dp))
        Text(label, color = theme.accent, fontSize = 13.sp,
            fontWeight = if (progress >= 1f) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** A saved title is only removed once the listener confirms it (a swipe is easy to make by mistake). */
@Composable
private fun RemoveDownloadAlert(track: Track, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    AppAlertDialog(onDismissRequest = { if (!deleting) onDone(false) },
        modifier = Modifier.testTag("swipe-remove-alert"),
        title = { Text("Supprimer le téléchargement") },
        text = { Text(track.title, localize = false) },
        confirmButton = { KetTextButton("Supprimer", enabled = !deleting, primary = true, destructive = true, onClick = {
            val item = com.ketvoice.app.data.Downloader.itemFor(track.id) ?: return@KetTextButton onDone(false)
            deleting = true
            scope.launch {
                val removed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.ketvoice.app.data.Downloader.delete(item) }.getOrDefault(false)
                }
                // Deleted by hand: « Hors ligne intelligent » never brings it back on its own.
                if (removed) com.ketvoice.app.data.SmartOffline.noteDeletedByHand(listOf(track.id))
                onDone(removed)
            }
        }) },
        dismissButton = { KetTextButton("Annuler", enabled = !deleting, onClick = { onDone(false) }) })
}

/**
 * Tap and long-press of a title row, the same everywhere: a tap does [onClick], a
 * long-press opens the title's menu with one tick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.trackRowClick(track: Track, enabled: Boolean = true, role: Role? = Role.Button,
                                    /** 7.3: a page's own menu for the long press (Downloads adds « Sélectionner »), from the row's bounds. */
                                    onMenu: ((androidx.compose.ui.geometry.Rect?) -> Unit)? = null,
                                    onClick: () -> Unit): Modifier {
    val haptic = rememberHaptics()
    // Held, the row lifts as a preview of itself and a glass menu unfolds from it; the "more" button still opens the full sheet.
    val anchor = rememberRowAnchor()
    // With a mouse or a trackpad (large screens only): a secondary click opens
    // the same menu, and the title can be dragged onto a playlist of the sidebar (ui/adaptive/PointerExtras).
    // Under the finger the row darkens as an iOS list row does (RowHighlight), and lets go slowly.
    val ink = LocalTheme.current.onBackground
    val highlight = remember(ink) { RowHighlight(ink) }
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val menu = onMenu ?: { bounds -> ContextMenus.open(track, bounds) }
    return rowAnchor(anchor).largeScreenTrackRow(track) { menu(anchor.bounds()) }
        .combinedClickable(interactionSource = source, indication = highlight,
        enabled = enabled, role = role, onLongClickLabel = AppText.t("Options"),
        onLongClick = { haptic(Haptics.Kind.Tick); menu(anchor.bounds()) }, onClick = onClick)
}
