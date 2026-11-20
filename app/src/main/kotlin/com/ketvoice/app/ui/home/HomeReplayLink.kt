package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.ListeningMonths
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.pageMargin
import java.time.LocalDate

/** A short way into the month's Replay: its name, its time and its artist. */
@Composable
internal fun HomeReplayLink(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val months by ListeningHistory.months.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val key = remember { ListeningMonths.key(LocalDate.now()) }
    val recap = remember(months, known) { com.ketvoice.app.data.Wrapped.month(key, months, known) }
    if (recap.plays < 3) return
    Row(modifier.fillMaxWidth().padding(horizontal = pageMargin, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(theme.card)
        .clickable(role = Role.Button) { AppUi.openReplay() }.padding(horizontal = 16.dp, vertical = 14.dp).testTag("home-replay"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(theme.accent.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
            Icon(KetIcons.Clock, null, tint = theme.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Replay · " + com.ketvoice.app.ui.sub.WrappedPeriod.monthName(key), localize = false, color = theme.onBackground, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(com.ketvoice.app.ui.sub.playtimeLabel(recap.minutes * 60_000L).ifBlank { "< 1 min" }, recap.topArtist?.name)
                .joinToString(" · "), localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
    }
}
