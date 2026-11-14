package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.player.NowPlayingBars
import com.ketvoice.app.ui.player.formatSecs
import com.ketvoice.app.ui.sub.formatBadge
import com.ketvoice.app.ui.sub.isLosslessFormat

/** The codec badge of a saved title ("FLAC", "MP3"), or null when the title is not on the device. */
internal fun savedFormatBadge(format: String?): String? = format?.takeIf { it.isNotBlank() }?.let(::formatBadge)

/**
 * The row of a title, the same in every list: cover with its download state, title,
 * artist, the format badge of a saved file, then animated bars while it plays (its
 * duration otherwise) and the menu button. A tap plays, a long-press opens the menu.
 */
@Composable
internal fun TrackRow(
    track: Track,
    cover: String?,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val theme = LocalTheme.current
    val small = if (BuildConfig.IS_QUEST) 14.sp else 13.sp
    Row(modifier.fillMaxWidth().trackRowClick(track, onClick = onClick).padding(start = 18.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (rank != null) Text("$rank", localize = false, color = theme.secondary, fontSize = 13.sp, modifier = Modifier.width(26.dp))
        DownloadCover(track, cover, 52.dp, 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            TrackTitle((if (track.explicit) "ᴱ  " else "") + track.title,
                color = if (active) theme.accent else theme.onBackground, selected = active)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                savedFormatBadge(Downloader.itemFor(track.id)?.format)?.let { badge ->
                    val lossless = isLosslessFormat(badge)
                    Text(badge, localize = false, color = if (lossless) theme.accent else theme.secondary, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background((if (lossless) theme.accent else theme.onBackground).copy(alpha = .12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
                Text(track.artist, localize = false, color = theme.secondary, fontSize = small, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (active) NowPlayingBars(playing, Modifier.padding(horizontal = 6.dp))
        else if (trailing == null && track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false,
            color = theme.secondary, fontSize = if (BuildConfig.IS_QUEST) 14.sp else 12.sp)
        if (trailing != null) trailing()
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { AppUi.trackMenuTrack.value = track },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MoreVert, "Options", tint = theme.secondary, modifier = Modifier.size(22.dp))
        }
    }
}
