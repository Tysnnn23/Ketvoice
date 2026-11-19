package com.ketvoice.app.ui.sub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.DownloadPhase
import com.ketvoice.app.data.DownloadTask
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.collectPick
import com.ketvoice.app.ui.common.pick
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** How far a transfer is, 0..1; null while its size is unknown or it is not running. */
internal fun downloadFraction(task: DownloadTask?): Float? = when (task?.phase) {
    null, DownloadPhase.Complete, DownloadPhase.Cancelled, DownloadPhase.Failed -> null
    DownloadPhase.Finalizing -> 1f
    else -> task.total?.takeIf { it > 0 }?.let { (task.bytes.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
}

/**
 * 7.3, as the App Store fills an app's icon: while [trackId] downloads, its cover darkens and a ring fills
 * with what has arrived. One collector per cover, of its own task; the ring is read in the draw phase and
 * glides between the downloader's ticks (snaps under reduced motion).
 */
@Composable
internal fun Modifier.downloadFill(trackId: String, radius: Dp): Modifier {
    val task by remember(trackId) { Downloader.tasks.pick { it[trackId] } }.collectPick()
    val target = downloadFraction(task) ?: return this
    val waiting = task?.phase == DownloadPhase.Paused || task?.phase == DownloadPhase.WaitingWifi
    val shown = remember(trackId) { Animatable(target) }
    LaunchedEffect(target) {
        if (Motion.reduced) shown.snapTo(target) else shown.animateTo(target, tween(Motion.duration(260), easing = LinearOutSlowInEasing))
    }
    return testTag("download-fill").drawWithContent {
        drawContent()
        val corner = radius.toPx()
        drawRoundRect(Color.Black.copy(alpha = .55f), cornerRadius = CornerRadius(corner, corner))
        val stroke = 2.5.dp.toPx()
        val ring = minOf(size.width, size.height) * .27f
        val topLeft = Offset(center.x - ring, center.y - ring)
        val box = Size(ring * 2, ring * 2)
        drawArc(Color.White.copy(alpha = .28f), 0f, 360f, false, topLeft, box, style = Stroke(stroke))
        drawArc(Color.White.copy(alpha = if (waiting) .6f else 1f), -90f, 360f * shown.value, false, topLeft, box,
            style = Stroke(stroke, cap = StrokeCap.Round))
        // The stop square at the centre, as on the App Store.
        val square = ring * .55f
        drawRoundRect(Color.White.copy(alpha = if (waiting) .6f else 1f), Offset(center.x - square / 2, center.y - square / 2),
            Size(square, square), CornerRadius(square * .18f, square * .18f))
    }
}

/** What « Retélécharger en meilleure qualité » asks for each title; the host tests put their own answer here. */
internal object UpgradeChecks {
    var check: suspend (Downloader.Item) -> Downloader.UpgradeOffer? = { Downloader.checkUpgrade(it) }
}

/**
 * Checks each of [items] for a better rendition (three at a time), then says how many can be improved and
 * offers to download those again; titles with nothing better are left as they are, and the alert says so.
 */
@Composable
internal fun UpgradeDialog(items: List<Downloader.Item>, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val theme = LocalTheme.current
    var checked by remember(items) { mutableIntStateOf(0) }
    var results by remember(items) { mutableStateOf<List<Downloader.UpgradeOffer?>?>(null) }
    LaunchedEffect(items) {
        val gate = Semaphore(3)
        results = items.map { item -> async { gate.withPermit { runCatching { UpgradeChecks.check(item) }.getOrNull().also { checked++ } } } }.awaitAll()
    }
    val found = results
    val better = found.orEmpty().filterNotNull().filter { it.offer != null }
    val unknown = found.orEmpty().count { it == null }
    AppAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("downloads-upgrade-alert"),
        title = { Text("Meilleure qualité") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    found == null -> {
                        ActivitySpinner(Modifier.testTag("upgrade-checking"))
                        Text(AppText.t("Vérification de la qualité… $checked / ${items.size}"),
                            localize = false, color = theme.secondary, fontSize = 13.sp)
                    }
                    better.isEmpty() -> Text(if (items.size == 1) "Aucune meilleure qualité disponible pour ce titre : il reste tel quel."
                        else "Aucune meilleure qualité disponible pour ces titres : ils restent tels quels.")
                    else -> {
                        Text(AppText.t(if (better.size == 1) "1 titre sur ${items.size} peut être amélioré" else "${better.size} titres sur ${items.size} peuvent être améliorés"),
                            localize = false)
                        better.take(4).forEach { o ->
                            Text("${o.item.track.title} · ${o.saved.label} → ${o.better?.label}", localize = false, color = theme.secondary,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("upgrade-line"))
                        }
                        if (better.size > 4) Text("…", localize = false, color = theme.secondary, fontSize = 13.sp)
                        Text("Les autres restent tels quels. Le fichier actuel est gardé tant que le nouveau n'est pas complet et vérifié.",
                            color = theme.secondary, fontSize = 12.sp)
                    }
                }
                if (found != null && unknown > 0) Text(AppText.t(if (unknown == 1) "1 titre n'a pas pu être vérifié" else "$unknown titres n'ont pas pu être vérifiés"),
                    localize = false, color = theme.secondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            if (better.isNotEmpty()) KetTextButton("Retélécharger", { Downloader.upgrade(better); onStarted() }, primary = true,
                modifier = Modifier.testTag("upgrade-confirm"))
            else KetTextButton("OK", onDismiss, enabled = found != null)
        },
        dismissButton = if (better.isNotEmpty() || found == null) ({ KetTextButton("Annuler", onDismiss) }) else null)
}
