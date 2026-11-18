package com.ketvoice.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What one title's cover follows of the downloads (6.6): whether it downloads and whether it is saved, as values
 * that change only for this title. Collecting the whole maps made every cover of every list recompose at each
 * progress step of any download (smart offline and favourites download in the background).
 */
@Composable
private fun rememberDownloadState(trackId: String): Pair<State<Boolean>, State<Boolean>> {
    val (activeFlow, activeNow) = remember(trackId) {
        Downloader.busy.map { trackId in it }.distinctUntilChanged() to (trackId in Downloader.busy.value)
    }
    val (savedFlow, savedNow) = remember(trackId) {
        Downloader.items.map { list -> list.any { it.track.id == trackId } }.distinctUntilChanged() to
            Downloader.items.value.any { it.track.id == trackId }
    }
    return activeFlow.collectAsState(activeNow) to savedFlow.collectAsState(savedNow)
}

@Composable
fun DownloadCover(track: Track, url: String?, size: Dp, radius: Dp = 9.dp) {
    val (activeState, downloadedState) = rememberDownloadState(track.id)
    val active by activeState
    val downloaded by downloadedState
    var observedJob by remember(track.id) { mutableStateOf(active) }
    var complete by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(active, downloaded) {
        if (active) { observedJob = true; complete = false }
        else if (observedJob) {
            observedJob = false
            if (downloaded) { complete = true; delay(1400); complete = false }
        }
    }
    val accent = LocalTheme.current.accent
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(radius))) {
        Cover(track.title, url, size, radius)
        if (active) DownloadingOverlay(track.id, size, accent)
        AnimatedVisibility(visible = complete, modifier = Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(tween(Motion.duration())) + scaleIn(initialScale = .85f, animationSpec = tween(Motion.duration())),
            exit = fadeOut(tween(Motion.duration()))) {
            Box(Modifier.padding(3.dp).size(24.dp).clip(CircleShape)
                .background(Color(0xFF173F2B)), contentAlignment = Alignment.Center) {
                val drawn by animateFloatAsState(if (complete) 1f else 0f,
                    if (Motion.reduced) androidx.compose.animation.core.snap() else tween(320, delayMillis = 90), label = "download-check")
                val done = com.ketvoice.app.data.AppText.t("Téléchargé")
                com.ketvoice.app.ui.motion.DrawnCheck(drawn, Modifier.size(17.dp).semantics { contentDescription = done })
            }
        }
    }
}

/** The veil and the filling arrow of a cover being downloaded: only these follow the download's progress. */
@Composable
private fun BoxScope.DownloadingOverlay(trackId: String, size: Dp, accent: Color) {
    val percent by remember(trackId) { Downloader.progress.map { it[trackId] ?: 0 }.distinctUntilChanged() }
        .collectAsState(Downloader.progress.value[trackId] ?: 0)
    val phase by remember(trackId) { Downloader.tasks.map { it[trackId]?.phase }.distinctUntilChanged() }
        .collectAsState(Downloader.tasks.value[trackId]?.phase)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)))
    val waiting = phase == com.ketvoice.app.data.DownloadPhase.Paused || phase == com.ketvoice.app.data.DownloadPhase.WaitingWifi
    // Ket 27: the download arrow itself fills from the bottom as the file arrives.
    if (!waiting) com.ketvoice.app.ui.motion.FillingDownloadGlyph(
        percent.coerceIn(0, 100) / 100f,
        Modifier.align(Alignment.Center).size(size * .52f).testTag("download-fill").semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(percent.coerceIn(0, 100) / 100f, 0f..1f)
        }, fill = accent)
    if (waiting) {
        Icon(if (phase == com.ketvoice.app.data.DownloadPhase.Paused) Icons.Filled.Pause else Icons.Filled.Wifi,
            if (phase == com.ketvoice.app.data.DownloadPhase.Paused) "En pause" else "En attente du Wi-Fi",
            tint = Color.White, modifier = Modifier.align(Alignment.Center).size(size * .28f))
    }
}
