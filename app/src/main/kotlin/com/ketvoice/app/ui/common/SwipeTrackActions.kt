package com.ketvoice.app.ui.common

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
 * when releasing would act, and springs back.
 */
@Composable
internal fun SwipeTrackActions(track: Track, modifier: Modifier = Modifier, enabled: Boolean = true,
                               content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val offset = remember(track.id) { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val theme = LocalTheme.current
    fun act(next: Boolean) {
        val entry = Player.queueWithUndo(track, next) ?: return
        scope.launch {
            TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
            val result = TrackActionFeedback.snackbar.showSnackbar(
                AppText.t(if (next) "Lire ensuite" else "Ajouté à la file"),
                actionLabel = AppText.t("Annuler"), duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) Player.undoQueuedEntry(entry)
        }
    }
    val gesture = if (!enabled) Modifier else Modifier.pointerInput(track.id, threshold) {
        var armed = false
        detectHorizontalDragGestures(
            onDragStart = { armed = false },
            onHorizontalDrag = { change, dx ->
                change.consume()
                val next = (offset.value + dx).coerceIn(-threshold * 1.4f, threshold * 1.4f)
                scope.launch { offset.snapTo(next) }
                val past = kotlin.math.abs(next) >= threshold
                if (past && !armed) haptic(Haptics.Kind.Tick)
                armed = past
            },
            onDragCancel = { scope.launch { offset.animateTo(0f, Motion.gentleSpring()) } },
            onDragEnd = {
                if (kotlin.math.abs(offset.value) >= threshold) act(offset.value > 0f)
                scope.launch { offset.animateTo(0f, Motion.gentleSpring()) }
            })
    }
    Box(modifier.fillMaxWidth()
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(AppText.t("Lire ensuite")) { act(true); true },
                CustomAccessibilityAction(AppText.t("Ajouter à la file")) { act(false); true })
        }
        .then(gesture)) {
        val shift = offset.value
        if (shift != 0f) {
            val progress = swipeProgress(shift, threshold)
            val next = shift > 0f
            Row(Modifier.matchParentSize().background(theme.accent.copy(alpha = .08f + .14f * progress)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (next) Arrangement.Start else Arrangement.End) {
                Icon(if (next) Icons.AutoMirrored.Filled.PlaylistPlay else Icons.AutoMirrored.Filled.PlaylistAdd, null,
                    tint = theme.accent, modifier = Modifier.size(22.dp).graphicsLayer {
                        val grow = if (Motion.reduced) 1f else .8f + .2f * progress
                        scaleX = grow; scaleY = grow
                    })
                Spacer(Modifier.width(8.dp))
                Text(if (next) "Lire ensuite" else "Ajouter à la file", color = theme.accent, fontSize = 13.sp,
                    fontWeight = if (progress >= 1f) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = if (Motion.reduced) 0f else offset.value }
            .background(theme.background)) { content() }
    }
}

/**
 * Tap and long-press of a title row, the same everywhere: a tap does [onClick], a
 * long-press opens the title's menu with one tick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.trackRowClick(track: Track, enabled: Boolean = true, role: Role? = Role.Button,
                                    onClick: () -> Unit): Modifier {
    val haptic = rememberHaptics()
    return combinedClickable(enabled = enabled, role = role, onLongClickLabel = AppText.t("Options"),
        onLongClick = { haptic(Haptics.Kind.Tick); AppUi.trackMenuTrack.value = track }, onClick = onClick)
}
