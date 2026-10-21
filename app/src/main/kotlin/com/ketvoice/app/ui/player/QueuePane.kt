package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.ui.common.Cover
import kotlin.math.roundToInt

@Composable
internal fun QueuePane(state: PlayerUiState, modifier: Modifier = Modifier) {
    val track = state.current ?: return
    val art by ArtworkRepo.covers.collectAsState()
    val upcoming = state.queue.drop(state.index + 1)
    Column(modifier) {
        Text("File d'attente", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp)
        Spacer(Modifier.height(17.dp))
        Text("Lecture en cours", color = Color(0xFF919199), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        QueueRow(track, art["t:${track.id}"] ?: track.coverUrl, current = true, playing = state.isPlaying,
            onPlay = { }, onRemove = { Player.removeAt(state.index) })
        Spacer(Modifier.height(16.dp))
        Text("À suivre depuis : ${track.artist}", color = Color(0xFFB7B7BD), fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(9.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 18.dp)) {
            itemsIndexed(upcoming, key = { i, t -> "${t.id}:${state.index + 1 + i}" }) { i, t ->
                val index = state.index + 1 + i
                var drag by remember(t.id, index) { mutableFloatStateOf(0f) }
                val rowHeight = with(LocalDensity.current) { 58.dp.toPx() }
                QueueRow(t, art["t:${t.id}"] ?: t.coverUrl, modifier = Modifier.graphicsLayer { translationY = drag },
                    handle = Modifier.pointerInput(t.id, index) {
                        detectVerticalDragGestures(onDragStart = { drag = 0f },
                            onVerticalDrag = { change, delta -> change.consume(); drag += delta },
                            onDragEnd = {
                                val target = (index + (drag / rowHeight).roundToInt()).coerceIn(state.index + 1, state.queue.lastIndex)
                                Player.moveQueue(index, target)
                                drag = 0f
                            }, onDragCancel = { drag = 0f })
                    }, onPlay = { Player.jumpTo(index) }, onRemove = { Player.removeAt(index) })
            }
            if (upcoming.isEmpty()) item {
                Text("Aucun autre titre dans la file", color = Color.White.copy(alpha = .4f), fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 20.dp))
            }
        }
    }
}

@Composable
private fun QueueRow(track: Track, cover: String?, modifier: Modifier = Modifier, current: Boolean = false,
                     playing: Boolean = false, handle: Modifier = Modifier,
                     onPlay: () -> Unit, onRemove: () -> Unit) {
    Row(modifier.fillMaxWidth().height(58.dp).clickable(onClick = onPlay), verticalAlignment = Alignment.CenterVertically) {
        if (!current) {
            Box(handle.width(26.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.Filled.DragHandle, "Déplacer ce titre", tint = Color(0xFF85858C), modifier = Modifier.size(18.dp))
            }
        }
        Cover(track.title, cover, size = 46.dp, radius = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text((if (track.explicit) "ᴱ  " else "") + track.title, localize = false, color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, localize = false, color = Color(0xFF8B8B93), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) NowPlayingBars(playing, Modifier.padding(horizontal = 8.dp))
        Box(Modifier.size(32.dp).clickable(onClick = onRemove), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Filled.Close, "Retirer ${track.title}", tint = Color(0xFF85858C), modifier = Modifier.size(20.dp))
        }
    }
}
