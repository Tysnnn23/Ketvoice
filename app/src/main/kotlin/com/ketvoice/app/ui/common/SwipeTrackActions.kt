package com.ketvoice.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
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

@Composable
internal fun SwipeTrackActions(track: Track, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var offset by remember(track.id) { mutableFloatStateOf(0f) }
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
    Box(modifier.fillMaxWidth().background(theme.accent.copy(alpha = if (offset == 0f) 0f else .10f))
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(AppText.t("Lire ensuite")) { act(true); true },
                CustomAccessibilityAction(AppText.t("Ajouter à la file")) { act(false); true })
        }
        .pointerInput(track.id, threshold) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, dx -> change.consume(); offset = (offset + dx).coerceIn(-threshold * 1.4f, threshold * 1.4f) },
                onDragCancel = { offset = 0f },
                onDragEnd = {
                    if (kotlin.math.abs(offset) >= threshold) act(offset > 0f)
                    offset = 0f
                })
        }) {
        if (offset != 0f) Text(if (offset > 0f) "Lire ensuite" else "Ajouter à la file", color = theme.accent,
            modifier = Modifier.align(if (offset > 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 12.dp))
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = if (Motion.reduced) 0f else offset }
            .background(theme.background)) { content() }
    }
}
