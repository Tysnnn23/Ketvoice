package com.ketvoice.app.ui.vicecity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ViceFm
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.SeasonPulse
import com.ketvoice.app.theme.ViceCityTheme
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.pageMargin
import kotlinx.coroutines.launch

/**
 * Vice FM on Home (6.3), only while the Vice City theme is on: a night card with the striped sun and the neon floor,
 * its name in the 80s chrome, and a sunset button that plays a new mix of synthwave and 80s hits ([ViceFm]). While
 * the mix is gathered a spinner turns beside the button; when nothing answers, a line says so and the button retries.
 */
@Composable
fun ViceFmCard(modifier: Modifier = Modifier, sources: ViceFm.Sources = ViceFm.AppSources) {
    if (!LocalTheme.current.viceCity) return
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
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.padding(horizontal = pageMargin, vertical = 10.dp).fillMaxWidth()
        .then(with(ViceCityTheme) { Modifier.viceCityNeon(shape, true) })
        .clip(shape).background(ViceCityTheme.Colors.card).testTag("vicefm-card")) {
        ViceFmScenery(Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
            ViceFmName()
            Text("Synthwave et tubes des années 80, pris dans le catalogue.", color = ViceCityTheme.Colors.secondary,
                style = Ket27.Type.subheadline, modifier = Modifier.padding(top = 4.dp, end = 96.dp))
            if (failed) Text("Vice FM ne répond pas pour le moment. Réessayez dans un instant.", color = ViceCityTheme.Colors.secondary,
                style = Ket27.Type.footnote, modifier = Modifier.padding(top = 8.dp).testTag("vicefm-error")
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCapsuleButton("Écouter", Icons.Filled.PlayArrow, listen, Modifier.width(150.dp), prominent = true, enabled = !busy,
                    tag = "vicefm-play", minHeight = 48.dp)
                if (busy) ActivitySpinner(Modifier.padding(start = 14.dp).testTag("vicefm-busy"), size = 22.dp,
                    color = ViceCityTheme.Colors.secondary)
                Spacer(Modifier.width(12.dp))
                Text("Édition limitée, jusqu'au 10 décembre", color = ViceCityTheme.Colors.secondary,
                    style = Ket27.Type.caption, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The card's name: VICE FM in italic chrome, the way a radio's sign was written in the 80s. */
@Composable
private fun ViceFmName() {
    val lineHeight = with(LocalDensity.current) { 34.sp.toPx() }
    val chrome = remember(lineHeight) { NeonTitle.chrome(lineHeight) }
    val name = remember(chrome) { buildAnnotatedString { withStyle(SpanStyle(brush = chrome)) { append("VICE FM") } } }
    Text(name, style = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
        letterSpacing = 1.5.sp), modifier = Modifier.semantics { heading() }.testTag("vicefm-name"))
}

/** The striped sun on the right and the neon floor along the bottom, behind the card's words. */
@Composable
private fun ViceFmScenery(modifier: Modifier) {
    val performance = AppSettings.highPerformanceMode.value
    Box(modifier.drawBehind { with(ViceCityTheme) { bottomGlow(.25f) } }) {
        ViceCitySun(Modifier.matchParentSize())
        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(58.dp)) {
            drawNeonFloor(if (performance) NeonFloor.STILL else NeonFloor.scroll(SeasonPulse.phase.floatValue, false), performance)
        }
    }
}

