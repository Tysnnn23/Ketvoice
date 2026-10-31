package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** Only mounted for a local Canvas whose proportions are close to this viewport. */
@Composable
internal fun CanvasFocusPlayer(track: Track, path: String, playing: Boolean, durationMs: Long,
                               onExit: () -> Unit, onFailure: () -> Unit,
                               onSeek: (Long) -> Unit = Player::seekTo) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("canvas-focus")) {
        // Only the image dismisses on tap. Seeking and text never bubble into an exit gesture.
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClickLabel = AppText.t("Quitter le plein écran"), onClick = onExit
        )) {
            ArtworkCanvas(track, path, playing, Modifier.fillMaxSize(), canvasFill = true,
                onCanvasFailure = onFailure)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .35f), .16f to Color.Transparent,
                .60f to Color.Transparent, .82f to Color.Black.copy(alpha = .52f),
                1f to Color.Black.copy(alpha = .86f))))
        }
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
            SmallCircleButton(onExit, size = 48.dp) {
                Icon(Icons.Filled.Close, "Quitter le plein écran", tint = Color.White,
                    modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
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
