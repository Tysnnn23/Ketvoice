package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TrackTitle

/**
 * Fullscreen Canvas. The title, artist and duration stay visible permanently;
 * tapping the artwork returns to the normal player, while the scrub bar keeps
 * its own gestures and never triggers the exit.
 */
@Composable
internal fun CanvasFocusPlayer(track: Track, path: String, playing: Boolean, durationMs: Long,
                               onExit: () -> Unit, onFailure: () -> Unit,
                               onSeek: (Long) -> Unit = Player::seekTo) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("canvas-focus")) {
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClickLabel = AppText.t("Revenir à l'écran normal"), onClick = onExit
        ).testTag("canvas-focus-image")) {
            ArtworkCanvas(track, path, playing, Modifier.fillMaxSize(),
                onCanvasFailure = onFailure)
            // Permanent, readable scrims: metadata must never disappear.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .42f), .18f to Color.Transparent,
                .58f to Color.Transparent, .80f to Color.Black.copy(alpha = .55f),
                1f to Color.Black.copy(alpha = .88f))))
        }
        PlayerChrome(true, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
            SmallCircleButton(onExit, size = 48.dp) {
                Icon(Icons.Filled.Close, "Quitter le plein écran", tint = Color.White,
                    modifier = Modifier.size(22.dp))
            }
        }
        PlayerChrome(true, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            Column(Modifier
                .widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp)) {
                TrackTitle(track.title, color = Color.White, fontSize = 21.sp, selected = true, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text(track.artist, localize = false, color = Color.White.copy(alpha = .78f),
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                ScrubBar(durationMs, Modifier.fillMaxWidth().testTag("canvas-focus-seek"),
                    fillColor = Color.White, onSeek = onSeek)
            }
        }
    }
}
