package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.*

/** Existing listening state drives the lead card; never request another recommendation. */
@Composable
internal fun HomeEditorial(tracks: List<Track>, resume: Boolean = false) {
    val state by Player.state.collectAsState()
    val track = (if (resume) state.current else null) ?: tracks.firstOrNull() ?: return
    val covers by ArtworkRepo.covers.collectAsState()
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(if (resume) "Reprendre l'écoute" else "À l'affiche", color = theme.secondary,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).testTag("home-editorial")
            .background(Brush.linearGradient(listOf(theme.accent.copy(alpha = .16f), theme.card)))
            .clickable(role = Role.Button) {
                if (resume && state.current?.id == track.id) { if (!state.playWhenReady) Player.toggle() }
                else Player.playQueue(tracks)
                AppUi.showPlayer.value = true
            }.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Cover(track.title, covers["t:${track.id}"] ?: track.coverUrl, 116.dp, 18.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(track.title, localize = false, color = theme.onBackground, fontSize = 23.sp,
                    lineHeight = 27.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(track.artist, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = theme.accent, modifier = Modifier.size(20.dp))
                    Text(if (resume) "Reprendre" else "Écouter", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
