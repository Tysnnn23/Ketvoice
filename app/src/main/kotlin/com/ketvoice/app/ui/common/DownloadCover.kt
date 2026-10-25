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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.delay

@Composable
fun DownloadCover(track: Track, url: String?, size: Dp, radius: Dp = 9.dp) {
    val busy by Downloader.busy.collectAsState()
    val progress by Downloader.progress.collectAsState()
    val saved by Downloader.items.collectAsState()
    val active = track.id in busy
    val downloaded = saved.any { it.track.id == track.id }
    var observedJob by remember(track.id) { mutableStateOf(active) }
    var complete by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(active, downloaded) {
        if (active) { observedJob = true; complete = false }
        else if (observedJob) {
            observedJob = false
            if (downloaded) { complete = true; delay(1400); complete = false }
        }
    }
    val fraction by animateFloatAsState((progress[track.id] ?: 0).coerceIn(0, 100) / 100f,
        tween(Motion.duration(180)), label = "download-${track.id}")
    val accent = LocalTheme.current.accent
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(radius))) {
        Cover(track.title, url, size, radius)
        if (active) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)))
            Canvas(Modifier.align(Alignment.Center).size(size * .55f).semantics {
                progressBarRangeInfo = ProgressBarRangeInfo((progress[track.id] ?: 0).coerceIn(0, 100) / 100f, 0f..1f)
            }) {
                val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                drawArc(Color.White.copy(alpha = .30f), -90f, 360f, false, style = stroke)
                drawArc(accent, -90f, 360f * fraction, false, style = stroke)
            }
        }
        AnimatedVisibility(visible = complete, modifier = Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(tween(Motion.duration())) + scaleIn(initialScale = .85f, animationSpec = tween(Motion.duration())),
            exit = fadeOut(tween(Motion.duration()))) {
            Box(Modifier.padding(3.dp).size(24.dp).clip(CircleShape)
                .background(Color(0xFF173F2B)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, "Téléchargé", tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
    }
}
