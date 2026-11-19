package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.ServerClock
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.sync.NowPlayingSync
import com.ketvoice.app.sync.RemoteSession
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.delay

/**
 * « Continuer sur cet appareil »: what another of the listener's devices plays, on Home under the
 * greeting — « En cours sur Pixel 9 » and a button to resume it here, same queue, same moment.
 * Shown only while signed in; read again every 30 s while Home is on screen.
 */
@Composable
internal fun ResumeElsewhereBanner(modifier: Modifier = Modifier) {
    val account by AccountSession.state.collectAsState()
    if (!account.signedIn) return
    LaunchedEffect(Unit) { while (true) { NowPlayingSync.refresh(); delay(30_000) } }
    val session = NowPlayingSync.remote.collectAsState().value ?: return
    ResumeCard(session, modifier)
}

@Composable
internal fun ResumeCard(session: RemoteSession, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(22.dp)
    GlassPanel(modifier.fillMaxWidth().padding(horizontal = com.ketvoice.app.ui.common.pageMargin, vertical = 6.dp).testTag("resume-elsewhere"),
        // A card of Home's content: solid, not glass (7.1).
        shape, GlassParams.card(22f, dark = theme.isDark)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            // Where it plays, on a line of its own so a long device name is never cut by the button.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResumeWhere(session, Modifier.weight(1f))
                androidx.compose.material3.Icon(KetIcons.Close, AppText.t("Masquer"), tint = theme.secondary,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button) { NowPlayingSync.dismiss() }.padding(8.dp))
            }
            Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ResumeArtwork(session)
                ResumeText(session, Modifier.weight(1f).padding(horizontal = 12.dp))
                ResumeButton(session)
            }
        }
    }
}

@Composable
private fun ResumeWhere(session: RemoteSession, modifier: Modifier) {
    val live = session.live(ServerClock.now())
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(KetIcons.Audio, null, tint = LocalTheme.current.accent, modifier = Modifier.size(14.dp))
        Text(if (live) "En cours sur ${session.deviceName}" else "Écouté sur ${session.deviceName}", color = LocalTheme.current.accent,
            style = Ket27.Type.footnote.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ResumeArtwork(session: RemoteSession) {
    val art by ArtworkRepo.covers.collectAsState()
    val track = session.track
    Cover(track.title, com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl), 48.dp, 10.dp)
}

@Composable
private fun ResumeText(session: RemoteSession, modifier: Modifier) {
    val theme = LocalTheme.current
    Column(modifier) {
        Text(session.track.title, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(session.track.artist, localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ResumeButton(session: RemoteSession) {
    val theme = LocalTheme.current
    Box(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(999.dp)).background(theme.accent)
        .clickable(role = Role.Button) { NowPlayingSync.resume(session); com.ketvoice.app.ui.AppUi.showPlayer.value = true }
        .padding(horizontal = 14.dp).testTag("resume-here"), contentAlignment = Alignment.Center) {
        Text("Reprendre ici", color = Color.White, style = Ket27.Type.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}
