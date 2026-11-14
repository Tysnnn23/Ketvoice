package com.ketvoice.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.TrackActionFeedback
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import com.ketvoice.app.ui.common.TrackTitle
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.openTrackSearch
import com.ketvoice.app.BuildConfig
import kotlinx.coroutines.launch

/**
 * Where a dragged queue row goes: 1 once it passed half of the row below, -1 once it
 * passed half of the row above, 0 while it stays. A null neighbour cannot be crossed.
 */
internal fun queueDragStep(offset: Float, aboveSize: Int?, belowSize: Int?): Int = when {
    belowSize != null && offset > belowSize / 2f -> 1
    aboveSize != null && offset < -aboveSize / 2f -> -1
    else -> 0
}

/** Name of the playlist a saved queue becomes. */
internal fun savedQueueName(label: String, date: java.time.LocalDate, locale: java.util.Locale): String =
    "$label · ${date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", locale))}".take(80)

@Composable
internal fun QueuePane(state: PlayerUiState, modifier: Modifier = Modifier, showModes: Boolean = false) {
    val track = state.current ?: return
    val art by ArtworkRepo.covers.collectAsState()
    val upcoming = state.queue.drop(state.index + 1)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }

    fun announce(message: String, undo: (() -> Unit)? = null) {
        scope.launch {
            TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
            val result = TrackActionFeedback.snackbar.showSnackbar(AppText.t(message),
                actionLabel = undo?.let { AppText.t("Annuler") }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    fun remove(index: Int, removed: Track) {
        Player.removeAt(index)
        announce("Retiré de la file") { Player.insertAt(index, removed) }
    }
    fun clear() {
        val before = Player.state.value
        Player.clearUpcoming()
        announce("File vidée") { Player.restoreQueue(before.queue, before.queueEntryIds, before.index, before.linearOrder) }
    }
    fun save() {
        val tracks = Player.state.value.queue
        if (saving || tracks.isEmpty()) return
        saving = true
        scope.launch {
            val saved = runCatching {
                PlaylistStore.create(savedQueueName(AppText.t("File d'attente"), java.time.LocalDate.now(), java.util.Locale.getDefault()),
                    tracks.map(PlaylistRules::entry),
                    cover = tracks.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl })
            }.isSuccess
            saving = false
            announce(if (saved) "Playlist enregistrée dans la bibliothèque" else "Impossible d'enregistrer la playlist")
        }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("File d'attente", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).asHeading())
            if (state.queue.size > 1) QueueAction("Enregistrer", enabled = !saving, tag = "queue-save", onClick = ::save)
            if (upcoming.isNotEmpty()) QueueAction("Vider", tag = "queue-clear", onClick = ::clear)
        }
        if (showModes) {
            Spacer(Modifier.height(10.dp))
            QueueModes(state)
        }
        Spacer(Modifier.height(if (showModes) 12.dp else 17.dp))
        Text("Lecture en cours", color = Color.White.copy(alpha = .78f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        QueueRow(track, art["t:${track.id}"] ?: track.coverUrl, current = true, playing = state.isPlaying,
            onPlay = { }, onRemove = { Player.removeAt(state.index) })
        Spacer(Modifier.height(16.dp))
        Text("À suivre depuis : ${track.artist}", color = Color(0xFFB7B7BD), fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(9.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("queue-list"), state = list, contentPadding = PaddingValues(bottom = 18.dp)) {
            itemsIndexed(upcoming, key = { i, t -> state.queueEntryIds.getOrNull(state.index + 1 + i) ?: "${t.id}:${state.index + 1 + i}" }) { i, t ->
                val index = state.index + 1 + i
                val key = state.queueEntryIds.getOrNull(index) ?: "${t.id}:$index"
                val dragging = draggingKey == key
                val canMoveUp = i > 0
                val canMoveDown = i < upcoming.lastIndex
                QueueRow(t, art["t:${t.id}"] ?: t.coverUrl, modifier = Modifier
                    // The dragged row follows the finger; its neighbours glide out of the way.
                    .then(if (Motion.reduced || dragging) Modifier else Modifier.animateItem())
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                        val lift = if (dragging && !Motion.reduced) 1.02f else 1f
                        scaleX = lift; scaleY = lift
                    }
                    .then(if (dragging) Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .10f)) else Modifier)
                    .semantics {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(AppText.t("Monter dans la file")) { Player.moveQueue(index, index - 1); true }
                                .takeIf { canMoveUp },
                            CustomAccessibilityAction(AppText.t("Descendre dans la file")) { Player.moveQueue(index, index + 1); true }
                                .takeIf { canMoveDown },
                            CustomAccessibilityAction(AppText.t("Retirer de la file")) { remove(index, t); true })
                    },
                    handle = Modifier.pointerInput(key) {
                        detectVerticalDragGestures(
                            onDragStart = { draggingKey = key; dragOffset = 0f; haptic(Haptics.Kind.Tick) },
                            onVerticalDrag = { change, delta ->
                                change.consume()
                                dragOffset += delta
                                dragOffset -= reorderWhileDragging(key, dragOffset, list) { haptic(Haptics.Kind.Tick) }
                            },
                            onDragEnd = { draggingKey = null; dragOffset = 0f },
                            onDragCancel = { draggingKey = null; dragOffset = 0f })
                    }, swipeKey = key, onPlay = { Player.jumpTo(index) }, onRemove = { remove(index, t) })
            }
            if (upcoming.isEmpty()) item {
                EmptyState("Aucun autre titre dans la file", "Choisissez un titre puis utilisez Lire ensuite pour préparer la suite.",
                    "Ajouter des titres", onAction = ::openTrackSearch)
            }
        }
    }
}

/**
 * Moves the dragged entry past the neighbour it just crossed and returns how many
 * pixels of the drag that move consumed (the row's new resting place).
 */
private fun reorderWhileDragging(key: String, offset: Float, list: LazyListState, onStep: () -> Unit): Float {
    val state = Player.state.value
    val at = state.queueEntryIds.indexOf(key)
    val rows = list.layoutInfo.visibleItemsInfo
    val row = rows.firstOrNull { it.key == key } ?: return 0f
    if (at <= state.index) return 0f
    val above = rows.firstOrNull { it.index == row.index - 1 }?.size?.takeIf { at - 1 > state.index }
    val below = rows.firstOrNull { it.index == row.index + 1 }?.size?.takeIf { at < state.queue.lastIndex }
    val step = queueDragStep(offset, above, below)
    if (step == 0) return 0f
    // A lazy list anchors on its first visible row: without this the list would
    // scroll by one row when that row is the one being swapped.
    val first = list.firstVisibleItemIndex
    val firstOffset = list.firstVisibleItemScrollOffset
    Player.moveQueue(at, at + step)
    if (row.index == first || row.index + step == first) list.requestScrollToItem(first, firstOffset)
    onStep()
    return if (step > 0) below!!.toFloat() else -above!!.toFloat()
}

@Composable
private fun QueueAction(label: String, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(label, color = LocalTheme.current.accent.copy(alpha = if (enabled) 1f else .45f), fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Shuffle, repeat and autoplay for the layouts that have no queue bar under the transport. */
@Composable
private fun QueueModes(state: PlayerUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("queue-modes")) {
        QueueMode(Icons.Filled.Shuffle, "Aléatoire", state.shuffle) { Player.setShuffle(!state.shuffle) }
        QueueMode(if (state.repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            repeatLabel(state.repeatOne, state.repeatAll), state.repeatOne || state.repeatAll) { Player.cycleRepeat() }
        QueueMode(Icons.Filled.AllInclusive, "Lecture automatique", state.autoplay) { Player.setAutoplay(!state.autoplay) }
    }
}

@Composable
private fun QueueMode(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val accent = LocalTheme.current.accent
    Box(Modifier.size(48.dp).clip(CircleShape)
        .background(if (active) accent.copy(alpha = .22f) else Color.White.copy(alpha = .08f))
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (active) accent else Color.White.copy(alpha = .78f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun QueueRow(track: Track, cover: String?, modifier: Modifier = Modifier, current: Boolean = false,
                     playing: Boolean = false, handle: Modifier = Modifier, swipeKey: String? = null,
                     onPlay: () -> Unit, onRemove: () -> Unit) {
    val scope = rememberCoroutineScope()
    val swipe = remember(swipeKey) { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 104.dp.toPx() }
    // The row keeps its gesture while the queue is reordered around it: always remove
    // the entry it shows now, not the position it had when the gesture was installed.
    val currentRemove by rememberUpdatedState(onRemove)
    // Swiping an upcoming title aside removes it; the snackbar offers to put it back.
    val swipeable = if (swipeKey == null) Modifier else Modifier.pointerInput(swipeKey, threshold) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, dx ->
                change.consume()
                scope.launch { swipe.snapTo((swipe.value + dx).coerceIn(-size.width.toFloat(), size.width.toFloat())) }
            },
            onDragCancel = { scope.launch { swipe.animateTo(0f, Motion.gentleSpring()) } },
            onDragEnd = {
                if (kotlin.math.abs(swipe.value) >= threshold) currentRemove()
                else scope.launch { swipe.animateTo(0f, Motion.gentleSpring()) }
            })
    }
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).then(swipeable)
        .graphicsLayer {
            translationX = if (Motion.reduced) 0f else swipe.value
            alpha = 1f - (kotlin.math.abs(swipe.value) / (threshold * 2.2f)).coerceIn(0f, .7f)
        }
        .clickable(onClick = onPlay).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!current) {
            Box(handle.width(48.dp).height(56.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DragHandle, "Déplacer ce titre", tint = Color(0xFF85858C), modifier = Modifier.size(18.dp))
            }
        }
        Cover(track.title, cover, size = 46.dp, radius = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            TrackTitle((if (track.explicit) "ᴱ  " else "") + track.title, color = Color.White,
                selected = current, fontSize = if (BuildConfig.IS_QUEST) 16.sp else 15.sp)
            Text(track.artist, localize = false, color = Color.White.copy(alpha = .78f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) NowPlayingBars(playing, Modifier.padding(horizontal = 8.dp))
        Box(Modifier.size(48.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "Retirer ${track.title}", tint = Color.White.copy(alpha = .78f), modifier = Modifier.size(20.dp))
        }
    }
}
