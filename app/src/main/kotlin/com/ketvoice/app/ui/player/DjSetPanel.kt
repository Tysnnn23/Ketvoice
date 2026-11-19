package com.ketvoice.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.automix.DjSet
import com.ketvoice.app.automix.SetExport
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetProgressBar
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text

/**
 * « Mode DJ set » in the queue (7.0.0): while a set plays, a « DJ set » header with its energy curve, « Réordonner »
 * and « Exporter le set »; otherwise, with a few titles to come, « Lancer en DJ set ».
 */
@Composable
internal fun DjSetQueueSection(state: PlayerUiState, modifier: Modifier = Modifier) {
    val set by DjSet.live.collectAsState()
    val live = set
    if (live != null && DjSet.on(state, live)) DjSetHeader(state, live, modifier)
    else if (state.queue.size - state.index - 1 >= 2) Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        KetTextButton("Lancer en DJ set", { DjSet.fromQueue() }, Modifier.testTag("queue-dj-set"))
    }
}

@Composable
private fun DjSetHeader(state: PlayerUiState, live: DjSet.Live, modifier: Modifier) {
    val context = LocalContext.current
    val export by SetExport.state.collectAsState()
    val inSet = state.queue.filter { it.id in live.ids }
    val here = inSet.indexOfFirst { it.id == state.queue.getOrNull(state.index)?.id }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .07f))
        .padding(horizontal = 14.dp, vertical = 12.dp).testTag("queue-dj-set-header")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DJ set", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val sub = if (live.measuring > 0) AppText.t("Mesure des titres · {0} sur {1}").replace("{0}", "${live.measured}").replace("{1}", "${live.measuring}")
                    else AppText.t("{0} titres · l'énergie monte, culmine puis redescend").replace("{0}", "${inSet.size}")
                Text(sub, localize = false, color = Color.White.copy(alpha = .62f), fontSize = 12.sp, maxLines = 2)
            }
        }
        EnergyCurve(DjSet.curve(state.queue, live), here, Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp))
        ExportLine(export, context, inSet)
    }
}

@Composable
private fun ExportLine(export: SetExport.State, context: android.content.Context, set: List<Track>) {
    when (export) {
        is SetExport.State.Working -> Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(export.note, color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
                KetProgressBar({ export.fraction }, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            KetTextButton("Annuler", SetExport::cancel, Modifier.testTag("dj-set-export-cancel"))
        }
        else -> {
            val note = when (export) {
                is SetExport.State.Failed -> AppText.t(export.message)
                is SetExport.State.Done -> if (export.skipped == 0) AppText.t("Set exporté.")
                    else AppText.t("Set exporté sans les {0} titres en streaming.").replace("{0}", "${export.skipped}")
                else -> null
            }
            if (note != null) Text(note, localize = false, color = Color.White.copy(alpha = .72f), fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                KetTextButton("Réordonner", DjSet::arrange, Modifier.testTag("dj-set-reorder"))
                if (export is SetExport.State.Done) KetTextButton("Partager", { SetExport.share(context, export) })
                else KetTextButton("Exporter le set", { SetExport.start(context, set) }, Modifier.testTag("dj-set-export"))
            }
        }
    }
}

/** The set's energy title by title (the accent line) over the curve it aims for (the faint one); the title heard is marked. */
@Composable
private fun EnergyCurve(energy: List<Float>, here: Int, modifier: Modifier) {
    val accent = LocalTheme.current.accent
    val label = AppText.t("Courbe d'énergie du set")
    Canvas(modifier.semantics { contentDescription = label }) {
        val n = energy.size
        if (n < 2) return@Canvas
        fun x(i: Int) = size.width * i / (n - 1)
        fun y(v: Float) = size.height * (1f - v.coerceIn(0f, 1f) * .9f - .05f)
        val aim = Path()
        for (step in 0..48) {
            val t = step / 48f
            val point = Offset(size.width * t, y(DjSet.target(t)))
            if (step == 0) aim.moveTo(point.x, point.y) else aim.lineTo(point.x, point.y)
        }
        drawPath(aim, Color.White.copy(alpha = .22f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        val ranks = DjSet.ranks(energy)
        val line = Path()
        var open = false
        ranks.forEachIndexed { i, v ->
            if (v.isNaN()) { open = false; return@forEachIndexed }
            if (open) line.lineTo(x(i), y(v)) else line.moveTo(x(i), y(v))
            open = true
        }
        drawPath(line, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        if (here in 0 until n) {
            val v = ranks[here].takeIf { !it.isNaN() } ?: DjSet.target(here.toFloat() / (n - 1))
            drawCircle(accent, 4.dp.toPx(), Offset(x(here), y(v)))
        }
    }
}

/** « Lancer en DJ set » under a collection's play buttons. */
@Composable
internal fun DjSetLaunchButton(tracks: List<Track>, modifier: Modifier = Modifier) {
    if (tracks.size < 3) return
    com.ketvoice.app.ui.common.GlassCapsuleButton("Lancer en DJ set", KetIcons.Sliders, { DjSet.play(tracks) },
        modifier.fillMaxWidth().testTag("collection-dj-set"), minHeight = 46.dp)
}
