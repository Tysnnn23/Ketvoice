package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CollectionPlaybackActions(tracks: List<Track>, modifier: Modifier = Modifier,
                                       label: String = "Lecture", enabled: Boolean = tracks.isNotEmpty()) {
    val theme = LocalTheme.current
    val foreground = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    val largeFont = LocalDensity.current.fontScale >= 1.2f
    BoxWithConstraints(modifier) {
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (largeFont && maxWidth < 340.dp) 1 else 2,
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(false, true).forEach { shuffle ->
                val shape = RoundedCornerShape(18.dp)
                Row(Modifier.weight(1f).clip(shape)
                    .background(if (shuffle) theme.onBackground.copy(alpha = .07f) else theme.accent.copy(alpha = if (enabled) 1f else .35f))
                    .clickable(enabled = enabled, role = Role.Button) {
                        Player.setShuffle(false)
                        Player.playQueue(if (shuffle) tracks.shuffled() else tracks)
                        if (shuffle) Player.setShuffle(true)
                    }.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    val ink = if (shuffle) theme.accent else foreground
                    Icon(if (shuffle) Icons.Filled.Shuffle else Icons.Filled.PlayArrow, null, tint = ink, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (shuffle) "Aléatoire" else label, color = ink, fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun CollectionQuickActions(tracks: List<Track>, enabled: Boolean = true) {
    val theme = LocalTheme.current
    LiquidGlassButton({ Player.setShuffle(false); Player.playQueue(tracks) }, Modifier.size(48.dp), enabled = enabled) {
        Icon(Icons.Filled.PlayArrow, "Lecture", tint = theme.accent)
    }
    LiquidGlassButton({ Player.setShuffle(false); Player.playQueue(tracks.shuffled()); Player.setShuffle(true) }, Modifier.size(48.dp), enabled = enabled) {
        Icon(Icons.Filled.Shuffle, "Aléatoire", tint = theme.accent, modifier = Modifier.size(20.dp))
    }
}
