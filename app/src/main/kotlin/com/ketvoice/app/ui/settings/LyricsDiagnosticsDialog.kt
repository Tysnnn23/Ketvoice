package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text

/** Teste réellement chaque source de paroles sur le titre en cours. */
@Composable
fun LyricsDiagnosticsDialog(onClose: () -> Unit) {
    val th = LocalTheme.current
    val playerState by Player.state.collectAsState()
    val track = playerState.current
    var probing by remember { mutableStateOf(track != null) }
    var results by remember { mutableStateOf<Map<String, MusicApi.ProviderStatus>?>(null) }
    LaunchedEffect(track?.id) {
        val target = track ?: return@LaunchedEffect
        probing = true
        results = runCatching { MusicApi.probeProviders(target) }.getOrNull()
        probing = false
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(th.background)
            .padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("Diagnostic des sources de paroles", color = th.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                if (track == null) "Lisez un titre puis relancez le test : chaque source est interrogée sur ce titre."
                else "Chaque source est interrogée sur « ${track.title} — ${track.artist} ».",
                color = th.secondary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
            if (probing) {
                Text("Test en cours…", color = th.accent, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 18.dp))
            } else if (results == null && track != null) {
                Text("Le test n'a pas abouti. Vérifiez la connexion.", color = th.secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 18.dp))
            } else {
                results?.forEach { (source, status) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(
                            if (status.available) Color(0xFF34C759) else Color(0xFF8E8E92)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(MusicApi.sourceName(source), color = th.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    !status.available -> status.detail ?: "Aucune parole"
                                    status.syllableTimed -> "${status.lines} lignes · syllabes natives"
                                    status.wordTimed -> "${status.lines} lignes · mots synchronisés"
                                    status.timed -> "${status.lines} lignes · lignes synchronisées"
                                    else -> "${status.lines} lignes · texte simple"
                                },
                                color = th.secondary, fontSize = 12.sp)
                        }
                    }
                }
            }
            Text("Fermer", color = th.accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.End).clickable(onClick = onClose).padding(top = 12.dp))
        }
    }
}
