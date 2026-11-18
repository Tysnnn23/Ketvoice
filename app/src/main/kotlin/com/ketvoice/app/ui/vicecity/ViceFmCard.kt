package com.ketvoice.app.ui.vicecity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.ViceFm
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.pageMargin
import kotlinx.coroutines.launch

/**
 * Vice FM at the top of Home, only while the Vice City theme is on: a plain card of the page carrying the edition's
 * one illustration ([ViceCityScene]), the radio's name, a line on what it plays and the accent « Écouter » button,
 * which plays a new mix of synthwave and 80s hits ([ViceFm]). While the mix is gathered a spinner turns beside the
 * button; when nothing answers, a line says so and the button retries.
 */
@Composable
fun ViceFmCard(modifier: Modifier = Modifier, sources: ViceFm.Sources = ViceFm.AppSources) {
    val theme = LocalTheme.current
    if (!theme.viceCity) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val listen: () -> Unit = {
        if (!busy) scope.launch {
            busy = true; failed = false
            val mix = ViceFm.mix(sources, System.currentTimeMillis())
            busy = false
            if (mix.isEmpty()) failed = true else Player.playQueue(mix, 0)
        }
    }
    Column(modifier.padding(horizontal = pageMargin, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
        .background(theme.card).testTag("vicefm-card")) {
        ViceCityScene(Modifier.fillMaxWidth().height(116.dp))
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
            Text("Vice FM", localize = false, color = theme.onBackground, style = Ket27.Type.title3,
                modifier = Modifier.semantics { heading() }.testTag("vicefm-name"))
            Text("Synthwave et tubes des années 80, pris dans le catalogue.", color = theme.secondary,
                style = Ket27.Type.subheadline, modifier = Modifier.padding(top = 2.dp))
            if (failed) Text("Vice FM ne répond pas pour le moment. Réessayez dans un instant.", color = theme.secondary,
                style = Ket27.Type.footnote, modifier = Modifier.padding(top = 8.dp).testTag("vicefm-error")
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCapsuleButton("Écouter", Icons.Filled.PlayArrow, listen, Modifier.width(140.dp), prominent = true, enabled = !busy,
                    tag = "vicefm-play", minHeight = 48.dp)
                if (busy) ActivitySpinner(Modifier.padding(start = 12.dp).testTag("vicefm-busy"), size = 20.dp, color = theme.secondary)
                Spacer(Modifier.width(12.dp))
                Text("Édition limitée, jusqu'au 10 décembre", color = theme.secondary,
                    style = Ket27.Type.caption, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
    }
}
