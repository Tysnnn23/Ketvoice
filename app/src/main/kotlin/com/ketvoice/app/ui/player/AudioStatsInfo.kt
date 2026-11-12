package com.ketvoice.app.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.player.BitPerfectState
import com.ketvoice.app.player.ExactAudioPhase
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.delay

@Composable
internal fun AudioStatsInfo(modifier: Modifier = Modifier) {
    if (!AppSettings.techStats.value) return
    val state by Player.state.collectAsState()
    val exact by BitPerfectState.status.collectAsState()
    val decoded by produceState("", state.current?.id) {
        while (true) { value = Player.decodedAudioInfo(); delay(1000) }
    }
    Column(modifier.padding(vertical = 4.dp)) {
        Text("Stats info", color = LocalTheme.current.secondary, fontSize = 10.sp)
        Text(listOf(AppText.t(state.audioSource.ifBlank { "Source en attente" }), AppText.t(decoded)).filter { it.isNotBlank() }.joinToString(" · "),
            localize = false, color = LocalTheme.current.secondary, fontSize = 10.sp, maxLines = 2)
        if (AppSettings.bitPerfect.value) {
            val headline = when (exact.phase) {
                ExactAudioPhase.Active -> "Bit-Perfect actif · confirmé par Android"
                ExactAudioPhase.Pending -> exact.reason
                ExactAudioPhase.Unavailable -> "Chemin exact indisponible"
                ExactAudioPhase.Standard -> "Sortie standard"
            }
            Text(headline, color = LocalTheme.current.secondary, fontSize = 10.sp, maxLines = 2)
            if (exact.phase == ExactAudioPhase.Unavailable && exact.reason.isNotBlank() && exact.reason != headline) {
                Text("Sortie standard", color = LocalTheme.current.secondary, fontSize = 10.sp)
                Text(exact.reason, localize = false, color = LocalTheme.current.secondary, fontSize = 10.sp, maxLines = 2)
            }
            val path = listOfNotNull(
                exact.source?.let { "${AppText.t("PCM décodé")} ${it.describe()}" },
                exact.output?.let { "→ ${it.describe()}" }
            ).joinToString(" ")
            if (path.isNotBlank()) Text(path, localize = false, color = LocalTheme.current.secondary, fontSize = 10.sp, maxLines = 2)
        }
        if (state.searchingBetter) Text("Recherche d'une meilleure qualité…", color = LocalTheme.current.secondary, fontSize = 10.sp)
        if (state.audioFallback) Text("Repli après une erreur de flux", color = LocalTheme.current.secondary, fontSize = 10.sp)
        state.audioFallbackHttpStatus?.let { status ->
            Text("HTTP $status", localize = false, color = LocalTheme.current.secondary, fontSize = 10.sp)
        }
        if (state.failed) Text(state.quality, color = LocalTheme.current.secondary, fontSize = 10.sp)
    }
}
