package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * The pair every collection starts with: play it, or shuffle it. The main action is
 * the prominent button; both are glass capsules of the same height.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CollectionPlaybackActions(tracks: List<Track>, modifier: Modifier = Modifier,
                                       label: String = "Lecture", enabled: Boolean = tracks.isNotEmpty()) {
    val largeFont = LocalDensity.current.fontScale >= 1.2f
    BoxWithConstraints(modifier) {
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (largeFont && maxWidth < 340.dp) 1 else 2,
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCapsuleButton(label, KetIcons.Play, { Player.setShuffle(false); Player.playQueue(tracks) },
                Modifier.weight(1f), prominent = true, enabled = enabled, minHeight = 52.dp)
            GlassCapsuleButton("Aléatoire", KetIcons.Shuffle, { Player.playShuffled(tracks) },
                Modifier.weight(1f), enabled = enabled, minHeight = 52.dp)
        }
    }
}

@Composable
internal fun CollectionQuickActions(tracks: List<Track>, enabled: Boolean = true) {
    val theme = LocalTheme.current
    LiquidGlassButton({ Player.setShuffle(false); Player.playQueue(tracks) }, Modifier.size(48.dp), enabled = enabled) {
        Icon(KetIcons.Play, "Lecture", tint = theme.accent)
    }
    LiquidGlassButton({ Player.playShuffled(tracks) }, Modifier.size(48.dp), enabled = enabled) {
        Icon(KetIcons.Shuffle, "Aléatoire", tint = theme.accent, modifier = Modifier.size(20.dp))
    }
}
